package me.rerere.rikkahub.service

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale

/**
 * [拆分] 从 ChatService 提取的"后台 LLM 会话处理"域：
 * generateTitle / generateSuggestion / compressConversation。
 *
 * 三个功能共享同一模式：读设置 → 选模型/Provider → LLM 生成 → 保存会话，
 * 与 ChatService 的 session 管理/生成主流程解耦，仅通过回调解耦状态更新。
 * 回调注入（而非直接依赖 ChatService）避免构造环，保持门面语义不变。
 */
class ConversationBackgroundProcessor(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val conversationRepo: ConversationRepository,
    private val onSaveConversation: suspend (Uuid, Conversation) -> Unit,
    private val onUpdateConversation: (Uuid, Conversation) -> Unit,
    private val onGetSessionConversation: suspend (Uuid) -> Conversation?,
) {
    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                onSaveConversation(
                    conversationId,
                    it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
                )
            }
        }.onFailure {
            it.printStackTrace()
        }
    }

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            onGetSessionConversation(conversationId)?.let { sessionConversation ->
                onUpdateConversation(
                    conversationId,
                    sessionConversation.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: onGetSessionConversation(conversationId)
                ?: conversation
            onSaveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )

            return result.choices[0].message?.toText()?.trim()
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        // [FIX] 限并发压缩：原实现所有 chunk 全并行 async（长对话可同时打十几个
        // 请求），容易触发 API 速率限制且失败重试成本高。Semaphore(2) 限制同时
        // 最多 2 个压缩请求，其余排队。
        val compressedSummaries = coroutineScope {
            val chunks = splitMessages(messagesToCompress)
            val compressionSlots = Semaphore(2)
            chunks.map { chunk ->
                async {
                    compressionSlots.withPermit { compressMessages(chunk) }
                }
            }.awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        // [FIX] 合并最新状态而非整体覆盖：压缩要跑多个 LLM 请求（大对话可达数十秒~数分钟），
        // 期间用户可能继续发消息/生成回复（追加在会话末尾）。旧实现用压缩开始时的快照
        // copy 后整体 saveConversation，会把压缩期间的新消息连内存带库一起覆盖丢失。
        // 现改为：保留快照之后追加的新节点；同时基于 latest 而非快照做 copy，
        // 避免把期间更新的 title/folderId 等字段回滚成旧值。
        val latest = onGetSessionConversation(conversationId) ?: conversation
        val appendedNodes = latest.messageNodes.drop(conversation.messageNodes.size)
        val newConversation = latest.copy(
            messageNodes = newMessageNodes + appendedNodes,
            chatSuggestions = emptyList(),
        )

        onSaveConversation(conversationId, newConversation)
    }
}
