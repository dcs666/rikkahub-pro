package me.rerere.rikkahub.service

import android.app.Application
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
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
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toText
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.throttleLatest
import java.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "ChatGenerationCore"

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
 * 生成执行核心域。
 *
 * [拆分]（Strangler Fig）：从 ChatService 拆出，承载消息补全主流程
 * （handleMessageComplete）与消息有效性清理（checkInvalidMessages /
 * finishInterruptedPendingTools / cancelToolByUser）。用户动作（发送/重生成/
 * 工具审批/停止）在 [ChatMessageActions]，门面转发与域组装留在 ChatService。
 *
 * 依赖通过构造注入：会话域（[ChatSessionManager]）/ 工具域（[ChatToolBuilder]）
 * 以实例传入，持久化/错误上报以回调传入（避免与 ChatService 循环依赖）。
 */
internal class ChatGenerationCore(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val workspaceRepository: WorkspaceRepository,
    private val mcpManager: McpManager,
    private val sessionManager: ChatSessionManager,
    private val toolBuilder: ChatToolBuilder,
    private val onAddError: (Throwable, Uuid?, String?, ChatErrorSolution?) -> Unit,
    private val onSaveConversation: suspend (Uuid, Conversation) -> Unit,
    private val onUpdateConversation: (Uuid, Conversation) -> Unit,
    private val onGenerateTitle: suspend (Uuid, Conversation) -> Unit,
    private val onGenerateSuggestion: suspend (Uuid, Conversation) -> Unit,
) {
    // workspace 系统提示注入（依赖 workspaceRepository，故在本域内构造）
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // ---- 处理消息补全 ----

    suspend fun handleMessageComplete(
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
            onUpdateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // [PERF] MCP 工具列表只取一次（下方两处复用，避免重复遍历 settings）
            val allMcpTools = mcpManager.getAllAvailableTools(assistant)

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (assistant.enableWebSearch || allMcpTools.isNotEmpty()) {
                    onAddError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        context.getString(R.string.error_title_tool_unavailable),
                        null,
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
                processingStatus = sessionManager.getProcessingStatusMutable(conversationId),
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
                onUpdateConversation(conversationId, updatedConversation)

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
                        onUpdateConversation(conversationId, updatedConversation)

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
            onAddError(it, conversationId, enhancedTitle, null)
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            // 必须全量落库：regenerate(messageRange) 等场景下新生成的节点落在中间 index，
            // 末尾可能是残留旧节点；"只写末节点"会写错节点并丢失真正的新节点，故不可优化。
            onSaveConversation(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                onGenerateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                onGenerateSuggestion(conversationId, finalConversation)
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

        onUpdateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
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

    suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
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
        onSaveConversation(conversationId, updatedConversation)
    }

    // ---- 内部辅助 ----

    private fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> =
        sessionManager.getConversationFlow(conversationId)

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ) = sessionManager.launchWithConversationReference(conversationId, block)
}
