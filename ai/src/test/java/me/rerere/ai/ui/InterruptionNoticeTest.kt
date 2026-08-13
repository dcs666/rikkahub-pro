package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [打断标记] 生成被新消息/停止打断时，末尾 assistant 消息追加可见警示：
 * 幂等、仅 assistant、仅非空消息、uiNotice 元数据标记（请求构建时过滤）。
 */
class InterruptionNoticeTest {

    @Test
    fun `assistant 消息被打断后追加警示标记`() {
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("half answer")))
        )
        val (updated, appended) = messages.appendInterruptionNotice()
        assertTrue("应追加打断警示", appended)
        val notice = updated.last().parts.last() as UIMessagePart.Text
        assertTrue("警示应带 uiNotice 元数据", notice.isUiNotice)
        assertTrue("警示文案应提示打断", notice.text.contains("打断"))
    }

    @Test
    fun `幂等——已有打断标记不再追加`() {
        val (marked, first) = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("half")))
        ).appendInterruptionNotice()
        assertTrue(first)
        val (again, second) = marked.appendInterruptionNotice()
        assertFalse("重复调用不应再追加", second)
        assertEquals("消息列表应保持不变", marked, again)
    }

    @Test
    fun `非 assistant 消息不追加`() {
        val (_, appended) = listOf(UIMessage.user("hi")).appendInterruptionNotice()
        assertFalse(appended)
    }

    @Test
    fun `空内容 assistant 消息不追加`() {
        val (_, appended) = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        ).appendInterruptionNotice()
        assertFalse(appended)
    }

    @Test
    fun `普通文本不带 uiNotice 标记`() {
        assertFalse(UIMessagePart.Text("plain text").isUiNotice)
    }

    @Test
    fun `截断提示带 uiNotice 标记（与打断提示同机制过滤）`() {
        // 截断提示由 GenerationHandler 追加：truncatedNotice + uiNotice 双标记
        val part = UIMessagePart.Text(
            text = "> ⚠️ 截断",
            metadata = kotlinx.serialization.json.buildJsonObject {
                put("truncatedNotice", kotlinx.serialization.json.JsonPrimitive(true))
                put("uiNotice", kotlinx.serialization.json.JsonPrimitive(true))
            }
        )
        assertTrue("截断提示应被识别为 UI 提示", part.isUiNotice)
    }
}
