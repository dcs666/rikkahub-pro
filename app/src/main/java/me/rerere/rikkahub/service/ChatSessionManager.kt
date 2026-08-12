package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatSessionManager"

/**
 * 会话生命周期域：ConversationSession 的创建/销毁、引用计数、状态流访问、
 * 内存态更新（含文件清理）、文件夹归属操作。拆自 ChatService（Strangler Fig）。
 *
 * 注意：会话移除时通过 [onSessionRemoved] 回调通知上层清理会话级缓存
 * （工具构建域的 toolCache），避免残留。
 */
class ChatSessionManager(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val folderRepository: FolderRepository,
    private val filesManager: FilesManager,
    private val onSessionRemoved: (Uuid) -> Unit = {},
) {
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
        // [P2] 会话移除时同步清理其工具缓存，避免残留
        onSessionRemoved(conversationId)
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getSessionConversation(conversationId: Uuid): Conversation? {
        return sessions[conversationId]?.state?.value
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    /** 生成执行需要直接写入 processingStatus（GenerationHandler 更新进度文案）。 */
    fun getProcessingStatusMutable(conversationId: Uuid): MutableStateFlow<String?> {
        return getOrCreateSession(conversationId).processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    fun getGenerationJob(conversationId: Uuid): Job? = sessions[conversationId]?.getJob()

    fun setGenerationJob(conversationId: Uuid, job: Job?) {
        sessions[conversationId]?.setJob(job)
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        // [FIX] 竞态：生成期间内存态是最新权威（DB 仅在生成完成时落库），
        // 无条件用 DB 数据覆盖内存会丢失正在生成的消息（典型场景：手机生成中，
        // 电脑 web 打开同一会话触发 initialize → 内存回退 → 完成时基于旧内存落库丢数据）。
        // 仅当内存态为空（新会话）时从 DB 加载；内存已有内容则保持内存优先。
        if (session.state.value.messageNodes.isNotEmpty()) return
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 对话状态更新 ----

    fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        // [FIX] 原子读-改-写：原实现基于快照计算新值，UI（主线程）与 web 路由
        // （Ktor IO 线程）并发调用时后写覆盖先写（lost update）。
        // StateFlow.update 用 CAS 循环保证原子性；update lambda 需纯函数
        // （调用方都是 copy 操作，幂等）。
        val session = getOrCreateSession(conversationId)
        var previous: Conversation? = null
        session.state.update { current ->
            previous = current
            update(current)
        }
        // checkFilesDelete 需要新旧对比，移出 CAS lambda（其副作用在重试时会重复执行）
        previous?.let { old ->
            checkFilesDelete(session.state.value, old)
        }
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    // ---- 文件夹操作 ----

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 指定会话当前是否正在生成回复。
     * 任务结果注入用：生成中不抢占（避免打断用户正在看的回答），只注入消息。
     */
    fun isGenerating(conversationId: Uuid): Boolean {
        return sessions[conversationId]?.isGenerating == true
    }

    /**
     * 等待会话的当前生成结束（最长 [timeoutMs]）。
     * 任务结果注入前调用：sendMessage 会无条件 cancel 当前生成任务，
     * 若 AI 正在写代码/执行工具时注入，会直接掐断回复（代码不完整、工具中断）。
     * 返回 true = 已空闲可安全注入；false = 超时仍在生成（调用方应跳过注入）。
     */
    suspend fun awaitGenerationIdle(conversationId: Uuid, timeoutMs: Long = 120_000): Boolean {
        if (!isGenerating(conversationId)) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (isGenerating(conversationId) && System.currentTimeMillis() < deadline) {
            delay(500)
        }
        return !isGenerating(conversationId)
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }
}
