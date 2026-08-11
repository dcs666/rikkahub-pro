package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock

private val THINKING_REGEX = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)

/**
 * [E] 一次 find 完成 think 块拆分（原来 containsMatchIn + replace + find +
 * containsMatchIn 扫 4 遍同一文本，流式渲染每 32ms 全量跑一遍 output
 * transformers，长对话累计扫描成本可观）。
 * - 未命中返回 null
 * - reasoning = 块内内容（trim）
 * - stripped = 去掉 <think>...</think> 后的剩余文本（未闭合时删除到串尾，与
 *   原 replace 语义一致）
 * - hasClosingTag = 块是否闭合（THINKING_REGEX 非贪婪：文本含 </think> 时
 *   首个匹配必然以 </think> 收尾，用 match.value 判断与原来全局
 *   containsMatchIn 在"首个 think 块"语义上等价）
 */
private fun splitThinkBlock(text: String): ThinkSplit? {
    val match = THINKING_REGEX.find(text) ?: return null
    return ThinkSplit(
        reasoning = match.groupValues.getOrNull(1)?.trim() ?: "",
        stripped = text.removeRange(match.range),
        hasClosingTag = match.value.endsWith("</think>"),
    )
}

private data class ThinkSplit(
    val reasoning: String,
    val stripped: String,
    val hasClosingTag: Boolean,
)

// 部分供应商不会返回reasoning parts, 所以需要这个transformer
object ThinkTagTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { message ->
            if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
                message.copy(
                    parts = message.parts.flatMap { part ->
                        if (part is UIMessagePart.Text) {
                            splitThinkBlock(part.text)?.let { split ->
                                listOf(
                                    UIMessagePart.Reasoning(
                                        reasoning = split.reasoning,
                                        createdAt = message.createdAt.toInstant(timeZone = TimeZone.currentSystemDefault()),
                                        finishedAt = if (split.hasClosingTag) Clock.System.now() else null,
                                    ),
                                    part.copy(text = split.stripped),
                                )
                            } ?: listOf(part)
                        } else {
                            listOf(part)
                        }
                    }
                )
            } else {
                message
            }
        }
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val now = Clock.System.now()
        return messages.map { message ->
            if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
                message.copy(
                    parts = message.parts.flatMap { part ->
                        if (part is UIMessagePart.Text) {
                            splitThinkBlock(part.text)?.let { split ->
                                listOf(
                                    UIMessagePart.Reasoning(
                                        reasoning = split.reasoning,
                                        createdAt = message.createdAt.toInstant(timeZone = TimeZone.currentSystemDefault()),
                                        finishedAt = now,
                                    ),
                                    part.copy(text = split.stripped),
                                )
                            } ?: listOf(part)
                        } else {
                            listOf(part)
                        }
                    }
                )
            } else {
                message
            }
        }
    }
}
