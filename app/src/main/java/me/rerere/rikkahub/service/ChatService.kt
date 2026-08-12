package me.rerere.rikkahub.service

import android.app.Application
import android.database.sqlite.SQLiteBlobTooBigException
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.throttleLatest
import java.time.Instant
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

/**
 * 聊天服务门面。
 *
 * [拆分]（Strangler Fig 渐进重构）：
 * - 会话生命周期域 → [ChatSessionManager]（sessions/引用/flows/文件夹）
 * - 错误管理域 → [ChatErrorManager]（errors StateFlow）
 * - 工具构建域 → [ChatToolBuilder]（指纹缓存/工具集构建）
 * - 消息操作域 → [ChatMessageOps]（翻译/编辑/fork/select/delete）
 * - 后台 LLM 处理域 → [ConversationBackgroundProcessor]（标题/建议/压缩）
 *
 * 本类保留生成执行核心（sendMessage/handleMessageComplete）与门面转发，
 * 对外公开 API 签名不变。
 */
class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // [拆分] 错误管理域
    private val errorManager = ChatErrorManager()
    val errors: StateFlow<List<ChatError>> = errorManager.errors

    // [拆分] 会话生命周期域
    private val sessionManager = ChatSessionManager(
        appScope = appScope,
        settingsStore = settingsStore,
        conversationRepo = conversationRepo,
        folderRepository = folderRepository,
        filesManager = filesManager,
        onSessionRemoved = { toolBuilder.clearCache(it) },
    )

    // [拆分] 工具构建域
    private val toolBuilder = ChatToolBuilder(
        context = context,
        workspaceRepository = workspaceRepository,
        skillManager = skillManager,
        localTools = localTools,
        mcpManager = mcpManager,
        conversationRepo = conversationRepo,
        onAddError = ::addError,
    )

    // [拆分] 消息操作域
    private val messageOps = ChatMessageOps(
        context = context,
        appScope = appScope,
        settingsStore = settingsStore,
        generationHandler = generationHandler,
        filesManager = filesManager,
        onGetConversation = { id -> getConversationFlow(id).value },
        onUpdateConversation = { id, c -> updateConversation(id, c) },
        onSaveConversation = { id, c -> saveConversation(id, c) },
        onAddError = ::addError,
    )

    // [拆分] 后台 LLM 会话处理域（标题/建议/压缩）委托给独立类，门面语义不变
    private val backgroundProcessor = ConversationBackgroundProcessor(
        context = context,
        settingsStore = settingsStore,
        providerManager = providerManager,
        conversationRepo = conversationRepo,
        onSaveConversation = { id, c -> saveConversation(id, c) },
        onUpdateConversation = { id, c -> updateConversation(id, c) },
        onGetSessionConversation = { id ->
            sessionManager.getSessionConversation(id) ?: conversationRepo.getConversationById(id)
        },
    )

    // ---- 错误管理（门面） ----

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        errorManager.addError(error, conversationId, title, solution)
    }

    fun dismissError(id: Uuid) {
        errorManager.dismissError(id)
    }

    fun clearAllErrors() {
        errorManager.clearAllErrors()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() {
        sessionManager.cleanup()
    }

    // ---- Session 管理（门面） ----

    fun addConversationReference(conversationId: Uuid) {
        sessionManager.addConversationReference(conversationId)
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessionManager.removeConversationReference(conversationId)
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return sessionManager.getConversationFlow(conversationId)
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        return sessionManager.getGenerationJobStateFlow(conversationId)
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        return sessionManager.getProcessingStatusFlow(conversationId)
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return sessionManager.getConversationJobs()
    }

    suspend fun initializeConversation(conversationId: Uuid) {
        sessionManager.initializeConversation(conversationId)
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        val previousJob = sessionManager.getGenerationJob(conversationId)
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val currentConversation = getConversationFlow(conversationId).value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = messageOps.preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        sessionManager.setGenerationJob(conversationId, job)
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        sessionManager.getGenerationJob(conversationId)?.cancel()

        val job = appScope.launch {
            try {
                val conversation = getConversationFlow(conversationId).value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        sessionManager.setGenerationJob(conversationId, job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        sessionManager.getGenerationJob(conversationId)?.cancel()

        val job = appScope.launch {
            try {
                val conversation = getConversationFlow(conversationId).value
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                // Update the tool approval state
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    when {
                                        part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                            part.copy(approvalState = newApprovalState)
                                        }

                                        else -> part
                                    }
                                }
                            )
                        }
                    )
                }
                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                saveConversation(conversationId, updatedConversation)

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        sessionManager.setGenerationJob(conversationId, job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // [PERF] MCP 工具列表只取一次（下方两处复用，避免重复遍历 settings）
            val allMcpTools = mcpManager.getAllAvailableTools(assistant)

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (assistant.enableWebSearch || allMcpTools.isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // start generating
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = getProcessingStatusFlow(conversationId),
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = toolBuilder.buildCachedTools(
                    conversationId = conversationId,
                    assistant = assistant,
                    conversation = conversation,
                    settings = settings,
                    allMcpTools = allMcpTools,
                ) ?: return,
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            // [TURBO R1] 流式 UI 更新从 60fps(16ms) 降到 ~30fps(32ms)。
            // 长回答生成时主线程每帧要重组正在流式的那条 markdown 消息，30fps 给每帧
            // 多一倍的预算，直接缓解"生成时顿"。多数人对 30fps 文本流无感；若装上后
            // 觉得流式不够跟手，可调回 16 或折中 24。
            // [TURBO R2] 用 throttleLatest 替代官方 sample：官方 sample 在采样窗口内
            // 未发出的最后值会被静默丢弃（KDoc 明确 "the latest element is not emitted
            // if it does not fit into the sampling window"），流式生成末尾 32ms 内的
            // chunk 丢失 → 回复尾部消失 + onSuccess 持久化缺尾部版本（#1296）。
            // throttleLatest 节流语义相同，但上游完成后必 flush 最后一个值。
            }.throttleLatest(32L).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            it.printStackTrace()
            // [FIX] 错误信息中包含实际原因，帮助诊断问题
            val errorMessage = it.message ?: it.javaClass.name
            val enhancedTitle = context.getString(R.string.error_title_generation) + ": $errorMessage"
            addError(it, conversationId, title = enhancedTitle)
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            // 必须全量落库：regenerate(messageRange) 等场景下新生成的节点落在中间 index，
            // 末尾可能是残留旧节点；"只写末节点"会写错节点并丢失真正的新节点，故不可优化。
            saveConversation(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----（门面转发到 backgroundProcessor）

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        backgroundProcessor.generateTitle(conversationId, conversation, force)
    }

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        backgroundProcessor.generateSuggestion(conversationId, conversation)
    }

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = backgroundProcessor.compressConversation(
        conversationId,
        conversation,
        additionalPrompt,
        targetTokens,
        keepRecentMessages
    )

    // ---- 对话状态更新（门面） ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        sessionManager.updateConversation(conversationId, conversation)
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        sessionManager.updateConversationState(conversationId, update)
    }

    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        sessionManager.moveConversationToFolder(conversationId, folderId)
    }

    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessionManager.hasGeneratingConversationInFolder(folderId)
    }

    fun isGenerating(conversationId: Uuid): Boolean {
        return sessionManager.isGenerating(conversationId)
    }

    suspend fun awaitGenerationIdle(conversationId: Uuid, timeoutMs: Long = 120_000): Boolean {
        return sessionManager.awaitGenerationIdle(conversationId, timeoutMs)
    }

    suspend fun deleteFolder(folderId: Uuid) {
        sessionManager.deleteFolder(folderId)
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = sessionManager.launchWithConversationReference(conversationId, block)

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        // [FIX] 超长消息兜底：单节点 JSON 超过 SQLite/CursorWindow 限制（约 2MB）时
        // insertAll 抛 SQLiteBlobTooBigException，原来会向上冒泡导致"生成成功但保存失败"，
        // 消息只在内存中、重启后丢失且用户无感知。此处捕获并转为用户可见错误；
        // 会话内存态不受影响（本次会话内仍可继续查看/操作）。
        try {
            val exists = conversationRepo.existsConversationById(conversation.id)
            if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
                return // 新会话且为空时不保存
            }

            val updatedConversation = conversation.copy()
            updateConversation(conversationId, updatedConversation)

            if (!exists) {
                conversationRepo.insertConversation(updatedConversation)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        } catch (e: SQLiteBlobTooBigException) {
            Log.e(TAG, "saveConversation: node too large, conversation not persisted", e)
            addError(e, conversationId, title = context.getString(R.string.error_title_save_conversation))
        } catch (e: Exception) {
            Log.e(TAG, "saveConversation failed", e)
            addError(e, conversationId, title = context.getString(R.string.error_title_operation))
        }
    }

    // ---- 消息操作（门面转发到 messageOps） ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        messageOps.translateMessage(conversationId, message, targetLanguage)
    }

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        messageOps.editMessage(conversationId, messageId, parts)
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        return messageOps.forkConversationAtMessage(conversationId, messageId)
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        messageOps.selectMessageNode(conversationId, nodeId, selectIndex)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        messageOps.deleteMessage(conversationId, messageId, failIfMissing)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        messageOps.deleteMessage(conversationId, message)
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        messageOps.clearTranslationField(conversationId, messageId)
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessionManager.getGenerationJob(conversationId) ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
    }
}
