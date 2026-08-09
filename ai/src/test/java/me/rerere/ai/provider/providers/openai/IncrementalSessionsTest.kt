package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IncrementalSessionsTest {

    private val json = Json

    private fun userMsg(text: String) = buildJsonObject {
        put("role", "user")
        put("content", text)
    }

    private fun parse(text: String): JsonArray =
        json.parseToJsonElement(text).jsonArray

    @Test
    fun `first request has no increment`() {
        val sessions = IncrementalSessions()
        val input = listOf(userMsg("你好"))
        val (prevId, delta) = sessions.resolve(input)
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `second request with same prefix gets increment`() {
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        // 首次请求：记录会话（模拟服务端返回 output item）
        sessions.update(firstInput, "resp_1", listOf(parse("""{"type":"message","role":"assistant","content":[]}""")[0]))

        // 第二轮：完整 input = 首轮 input + 首轮输出 items + 新 user 消息
        val fullInput = firstInput + listOf(
            parse("""{"type":"message","role":"assistant","content":[]}""")[0],
            userMsg("继续"),
        )
        val (prevId, delta) = sessions.resolve(fullInput)
        assertNotNull(prevId)
        assertEquals("resp_1", prevId)
        // 增量只有新 user 消息
        assertEquals(1, delta!!.size)
        assertEquals("继续", delta[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `edited history falls back to full send`() {
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        sessions.update(firstInput, "resp_1", listOf(parse("""{"type":"message","role":"assistant","content":[]}""")[0]))

        // 历史被编辑（首条消息内容变了）→ 前缀不匹配 → 无法增量
        val editedInput = listOf(userMsg("你好吗")) + listOf(
            parse("""{"type":"message","role":"assistant","content":[]}""")[0],
            userMsg("继续"),
        )
        val (prevId, delta) = sessions.resolve(editedInput)
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `shorter input falls back to full send`() {
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        sessions.update(firstInput, "resp_1", listOf(parse("""{"type":"message","role":"assistant","content":[]}""")[0]))

        // 输入比已知状态短（不可能前缀匹配）→ 全量
        val shortInput = listOf(userMsg("你好"))
        val (prevId, delta) = sessions.resolve(shortInput)
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `tool call round trip falls back to full send`() {
        // [实测] opencode.ai 网关 previous_response_id 不支持工具输出关联：
        // 已知状态含 function_call 时禁用增量，回退全量（工具循环正确性不受影响）
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("查天气"))
        val fcItem = parse("""{"type":"function_call","call_id":"c1","name":"get_weather","arguments":"{}"}""")[0]
        sessions.update(firstInput, "resp_1", listOf(fcItem))

        // 工具执行后：完整 input = 首轮 + function_call + function_call_output + 新消息
        val fullInput = firstInput + listOf(
            fcItem,
            parse("""{"type":"function_call_output","call_id":"c1","output":"ok"}""")[0],
        )
        val (prevId, delta) = sessions.resolve(fullInput)
        // 已知状态含 function_call → 无法增量 → 全量发送
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `pure text round trip still increments after tool session is cleared`() {
        val sessions = IncrementalSessions()
        // 第一轮：纯文本
        val firstInput = listOf(userMsg("你好"))
        sessions.update(firstInput, "resp_1", emptyList())
        // 第二轮：纯文本增量可用
        val secondInput = firstInput + listOf(
            parse("""{"type":"message","role":"assistant","content":[]}""")[0],
            userMsg("继续"),
        )
        val (prevId, delta) = sessions.resolve(secondInput)
        assertNotNull(prevId)
        assertEquals(1, delta!!.size)
    }

    @Test
    fun `different conversations do not share increments`() {
        val sessions = IncrementalSessions()
        // 会话 A
        sessions.update(listOf(userMsg("A问")), "resp_A", emptyList())
        // 会话 B（不同首条消息）
        val bInput = listOf(userMsg("B问"), userMsg("B再问"))
        val (prevId, delta) = sessions.resolve(bInput)
        assertNull(prevId)
        assertNull(delta)
    }
}
