package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * [P1 流式视觉转换 last-only 优化] 守护测试。
 *
 * GenerationHandler 流式热路径现在只对最后一条消息做 visualTransform（前面消息
 * 引用不变 + 视觉转换是逐条纯函数/幂等 → 结果与全量 visualTransforms 一致）。
 * 本测试验证该前提：流式两帧之间只有最后一条消息变化时，
 * "last-only 转换"与"全量转换"的每一帧结果完全相同。
 * 若未来有人给 output transformer 的 visualTransform 引入跨消息状态依赖，
 * 本测试会失败——提醒同步调整 last-only 优化。
 */
class StreamingVisualTransformTest {

    private fun ctx(assistant: Assistant = Assistant()): TransformerContext {
        // visualTransform 实现不使用 context 字段（ThinkTag 只看消息，Regex 只看 assistant），
        // JVM 单测中传 null 即可；若未来用到 context 此处会 NPE 暴露。
        return TransformerContext(
            context = null as Context,
            model = Model(),
            assistant = assistant,
            settings = Settings(),
        )
    }

    private fun assistant(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
    )

    private fun user(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    /** 模拟 GenerationHandler 的 last-only 视觉转换（当前实现） */
    private suspend fun transformLastOnly(
        messages: List<UIMessage>,
        transformers: List<MessageTransformer>,
    ): List<UIMessage> {
        val last = messages.lastOrNull() ?: return messages
        return messages.dropLast(1) + listOf(last).visualTransforms(
            transformers = transformers,
            context = null as Context,
            model = Model(),
            assistant = Assistant(),
            settings = Settings(),
        ).single()
    }

    /** 模拟原全量视觉转换（优化前实现） */
    private suspend fun transformFull(
        messages: List<UIMessage>,
        transformers: List<MessageTransformer>,
    ): List<UIMessage> {
        return messages.visualTransforms(
            transformers = transformers,
            context = null as Context,
            model = Model(),
            assistant = Assistant(),
            settings = Settings(),
        )
    }

    // ---- ThinkTagTransformer：流式两帧 last-only 与全量一致 ----

    @Test
    fun `think tag - last-only equals full across streaming frames`() = runTestSuspend {
        val transformers: List<MessageTransformer> = listOf(ThinkTagTransformer)

        // 帧 1：最后一条 assistant 消息含未闭合 <think>（流式进行中）
        val frame1 = listOf(
            user("hi"),
            assistant(UIMessagePart.Text("already done")),
            assistant(UIMessagePart.Text("<think>reasoning in progress")),
        )
        // 帧 2：最后一条消息追加文本（流式推进）
        val frame2 = listOf(
            user("hi"),
            assistant(UIMessagePart.Text("already done")),
            assistant(UIMessagePart.Text("<think>reasoning in progress, more tokens")),
        )

        val lastOnly1 = transformLastOnly(frame1, transformers)
        val full1 = transformFull(frame1, transformers)
        assertEquals("frame1 last-only must equal full", full1, lastOnly1)

        val lastOnly2 = transformLastOnly(frame2, transformers)
        val full2 = transformFull(frame2, transformers)
        assertEquals("frame2 last-only must equal full", full2, lastOnly2)

        // 前面的消息在帧间没有被重新转换/改写
        assertEquals("earlier messages must be untouched", full1[0], lastOnly2[0])
        assertEquals("earlier assistant must be untouched", full1[1], lastOnly2[1])
    }

    @Test
    fun `think tag - closed block yields reasoning part with finishedAt`() = runTestSuspend {
        val transformers: List<MessageTransformer> = listOf(ThinkTagTransformer)
        val messages = listOf(
            user("hi"),
            assistant(UIMessagePart.Text("answer <think>done</think> tail")),
        )

        val result = transformLastOnly(messages, transformers)
        val last = result.last()
        val parts = last.parts

        assertEquals(2, parts.size)
        val reasoning = parts[0] as UIMessagePart.Reasoning
        assertEquals("done", reasoning.reasoning)
        assert(reasoning.finishedAt != null) { "closed think block must be finished" }
        val text = parts[1] as UIMessagePart.Text
        assertEquals("answer  tail", text.text)
    }

    // ---- RegexOutputTransformer：带正则的 assistant 流式两帧一致 ----

    @Test
    fun `regex output - last-only equals full with assistant regexes`() = runTestSuspend {
        val assistantWithRegex = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    name = "replace token",
                    findRegex = "\\\\[TOKEN\\\\]",
                    replaceString = "42",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                )
            )
        )
        val ctxWithRegex = ctx(assistantWithRegex)
        val transformers: List<MessageTransformer> = listOf(RegexOutputTransformer)

        val frame1 = listOf(
            user("hi"),
            assistant(UIMessagePart.Text("previous answer")),
            assistant(UIMessagePart.Text("value is [TOKEN]")),
        )
        val frame2 = listOf(
            user("hi"),
            assistant(UIMessagePart.Text("previous answer")),
            assistant(UIMessagePart.Text("value is [TOKEN], plus [TOKEN]")),
        )

        val lastOnly1 = transformLastOnly(frame1, transformers)
        val full1 = transformFull(frame1, transformers)
        assertEquals("frame1 last-only must equal full", full1, lastOnly1)

        val lastOnly2 = transformLastOnly(frame2, transformers)
        val full2 = transformFull(frame2, transformers)
        assertEquals("frame2 last-only must equal full", full2, lastOnly2)

        val lastText = (lastOnly2.last().parts.single() as UIMessagePart.Text).text
        assertEquals("value is 42, plus 42", lastText)
    }
}

/** 轻量 suspend 测试执行器（无协程测试依赖时使用） */
private fun runTestSuspend(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
