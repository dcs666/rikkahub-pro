package me.rerere.rikkahub.service

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import kotlin.uuid.Uuid

/**
 * 用户消息动作域。
 *
 * [拆分]（Strangler Fig）：从 ChatService 拆出，承载用户触发的生成动作——
 * 发送消息（[sendMessage]）、重生成（[regenerateAtMessage]）、工具审批
 * （[handleToolApproval]）、停止生成（[stopGeneration]）。动作内部的生成主流程
 * 委托给 [ChatGenerationCore]，会话状态读写经 [ChatSessionManager]，
 * 消息预处理经 [ChatMessageOps]。
 *
 * 持久化与错误上报以回调注入（避免与 ChatService 循环依赖）。
 */
internal class ChatMessageActions(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val sessionManager: ChatSessionManager,
    private val messageOps: ChatMessageOps,
    private val core: ChatGenerationCore,
    private val onAddError: (Throwable, Uuid?, String?, ChatErrorSolution?) -> Unit,
    private val onSaveConversation: suspend (Uuid, Conversation) -> Unit,
    private val onEmitGenerationDone: suspend (Uuid) -> Unit,
) {
    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        val previousJob = sessionManager.getGenerationJob(conversationId)
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                core.finishInterruptedPendingTools(conversationId)

                val currentConversation = sessionManager.getConversationFlow(conversationId).value
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
                onSaveConversation(conversationId, newConversation)

                // 开始补全
                if (answer) {
                    core.handleMessageComplete(conversationId)
                }

                onEmitGenerationDone(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                onAddError(e, conversationId, context.getString(R.string.error_title_send_message), null)
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
                val conversation = sessionManager.getConversationFlow(conversationId).value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    onSaveConversation(conversationId, newConversation)
                    core.handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        core.handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        onSaveConversation(conversationId, conversation)
                    }
                }

                onEmitGenerationDone(conversationId)
            } catch (e: Exception) {
                onAddError(e, conversationId, context.getString(R.string.error_title_regenerate_message), null)
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
                val conversation = sessionManager.getConversationFlow(conversationId).value
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
                onSaveConversation(conversationId, updatedConversation)

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    core.handleMessageComplete(conversationId)
                }

                onEmitGenerationDone(conversationId)
            } catch (e: Exception) {
                onAddError(e, conversationId, context.getString(R.string.error_title_tool_approval), null)
            }
        }

        sessionManager.setGenerationJob(conversationId, job)
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessionManager.getGenerationJob(conversationId) ?: return
        job.cancel()
        runCatching { job.join() }
        core.finishInterruptedPendingTools(conversationId)
    }
}
