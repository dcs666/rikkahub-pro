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
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

/**
 * 聊天服务门面。
 *
 * [拆分]（Strangler Fig 渐进重构）：
 * - 会话生命周期域 → [ChatSessionManager]（sessions/引用/flows/文件夹）
 * - 错误管理域 → [ChatErrorManager]（errors StateFlow）
 * - 工具构建域 → [ChatToolBuilder]（指纹缓存/工具集构建）
 * - 消息操作域 → [ChatMessageOps]（翻译/编辑/fork/select/delete）
 * - 后台 LLM 处理域 → [ConversationBackgroundProcessor]（标题/建议/压缩）
 * - 生成执行核心域 → [ChatGenerationCore]（handleMessageComplete/无效消息清理）
 * - 用户消息动作域 → [ChatMessageActions]（sendMessage/regenerate/toolApproval/stop）
 *
 * 本类保留域对象组装、门面转发与持久化（saveConversation），
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

    // [拆分] 生成执行核心域（消息补全主流程/无效消息清理）
    private val generationCore = ChatGenerationCore(
        context = context,
        appScope = appScope,
        appEventBus = appEventBus,
        settingsStore = settingsStore,
        memoryRepository = memoryRepository,
        generationHandler = generationHandler,
        templateTransformer = templateTransformer,
        workspaceRepository = workspaceRepository,
        mcpManager = mcpManager,
        sessionManager = sessionManager,
        toolBuilder = toolBuilder,
        onAddError = ::addError,
        onSaveConversation = ::saveConversation,
        onUpdateConversation = ::updateConversation,
        onGenerateTitle = ::generateTitle,
        onGenerateSuggestion = ::generateSuggestion,
    )

    // [拆分] 用户消息动作域（发送/重生成/工具审批/停止）
    private val messageActions = ChatMessageActions(
        context = context,
        appScope = appScope,
        settingsStore = settingsStore,
        sessionManager = sessionManager,
        messageOps = messageOps,
        core = generationCore,
        onAddError = ::addError,
        onSaveConversation = ::saveConversation,
        onEmitGenerationDone = { _generationDoneFlow.emit(it) },
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

    // ---- 发送消息（门面转发到 messageActions） ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        messageActions.sendMessage(conversationId, content, answer)
    }

    // ---- 重新生成消息（门面转发到 messageActions） ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        messageActions.regenerateAtMessage(conversationId, message, regenerateAssistantMsg)
    }

    // ---- 处理工具调用审批（门面转发到 messageActions） ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        messageActions.handleToolApproval(conversationId, toolCallId, approved, reason, answer)
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

    // 停止当前会话生成任务（不清理会话缓存）（门面转发到 messageActions）
    suspend fun stopGeneration(conversationId: Uuid) {
        messageActions.stopGeneration(conversationId)
    }
}
