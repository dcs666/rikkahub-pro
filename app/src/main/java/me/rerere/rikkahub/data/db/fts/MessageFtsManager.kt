package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        // [PERF] 整个 DELETE + 批量 INSERT 包进单个事务。
        // 原来每条 execSQL 各自一个隐式事务（每次 fsync），长对话上千条消息会触发上千次
        // fsync，生成结束/打开长对话时索引这一步可达秒级。单事务只 fsync 一次，提速数十倍。
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
            conversation.messageNodes.forEach { node ->
                node.messages.forEach { message ->
                    val text = message.extractFtsText()
                    if (text.isNotBlank()) {
                        db.execSQL(
                            "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                            arrayOf(
                                text,
                                node.id.toString(),
                                message.id.toString(),
                                conversationId,
                                conversation.title,
                                conversation.updateAt.toEpochMilli().toString(),
                            )
                        )
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    /**
     * [F2] 节点级增量索引：只重建变更/新增节点的 FTS 行，删除已移除节点的行。
     * 原来 updateConversation 后全量 DELETE + 全量 INSERT（长对话每次保存都重新
     * 索引全部消息）；增量后只处理变化节点（通常 1-2 个），长对话保存路径
     * 从"全量重索引"降到"常量级"。
     *
     * 与全量 [indexConversation] 语义等价（按 node_id 精确替换），单事务内完成。
     *
     * @param upsertNodes   新增/内容变更的节点（先删同 node_id 旧行再插新行）
     * @param deletedNodeIds 已移除的节点（删除其行）
     * @param title/updateAt 当前会话标题与更新时间（FTS 行冗余存储，随节点刷新）
     */
    suspend fun indexMessageNodesDelta(
        conversationId: String,
        title: String,
        updateAt: Long,
        upsertNodes: List<MessageNode>,
        deletedNodeIds: List<String>,
    ) = withContext(Dispatchers.IO) {
        if (upsertNodes.isEmpty() && deletedNodeIds.isEmpty()) return@withContext
        db.beginTransaction()
        try {
            if (deletedNodeIds.isNotEmpty()) {
                deleteFtsRowsByNodeIds(deletedNodeIds)
            }
            upsertNodes.forEach { node ->
                // 先删同 node_id 的旧行（节点内容变更/顺序调整后重插保证一致）
                deleteFtsRowsByNodeIds(listOf(node.id.toString()))
                node.messages.forEach { message ->
                    val text = message.extractFtsText()
                    if (text.isNotBlank()) {
                        db.execSQL(
                            "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                            arrayOf(
                                text,
                                node.id.toString(),
                                message.id.toString(),
                                conversationId,
                                title,
                                updateAt.toString(),
                            )
                        )
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 按 node_id 批量删 FTS 行（SQLite 绑定变量上限 999，分批执行）。 */
    private fun deleteFtsRowsByNodeIds(nodeIds: List<String>) {
        nodeIds.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.execSQL(
                "DELETE FROM message_fts WHERE node_id IN ($placeholders)",
                chunk.toTypedArray(),
            )
        }
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        limit: Int = 50,
        offset: Int = 0,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        val safeLimit = limit.coerceIn(1, 200)
        val safeOffset = offset.coerceAtLeast(0)
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id, title, update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            WHERE text MATCH jieba_query(?)
            ORDER BY ${sort.orderBy}
            LIMIT ? OFFSET ?
            """.trimIndent(),
            arrayOf(keyword, safeLimit.toString(), safeOffset.toString())
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }
}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
