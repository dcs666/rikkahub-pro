package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

    private fun body(input: List<JsonElement>): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("model", "test-model")
            put("input", kotlinx.serialization.json.JsonArray(input))
        }

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
        val (prevId, delta) = sessions.resolve(input, body(input))
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `second request with same prefix gets increment`() {
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        // 首次请求：记录会话（模拟服务端返回 output item）
        sessions.update(body(firstInput), "resp_1", listOf(parse("""{"type":"message","role":"assistant","content":[]}""")[0]))

        // 第二轮：完整 input = 首轮 input + 首轮输出 items + 新 user 消息
        val fullInput = firstInput + listOf(
            parse("""{"type":"message","role":"assistant","content":[]}""")[0],
            userMsg("继续"),
        )
        val (prevId, delta) = sessions.resolve(fullInput, body(fullInput))
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
        sessions.update(body(firstInput), "resp_1", listOf(parse("""{"type":"message","role":"assistant","content":[]}""")[0]))

        // 历史被编辑（首条消息内容变了）→ 前缀不匹配 → 无法增量
        val editedInput = listOf(userMsg("你好吗")) + listOf(
            parse("""{"type":"message","role":"assistant","content":[]}""")[0],
            userMsg("继续"),
        )
        val (prevId, delta) = sessions.resolve(editedInput, body(editedInput))
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `shorter input falls back to full send`() {
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        sessions.update(body(firstInput), "resp_1", listOf(parse("""{"type":"message","role":"assistant","content":[]}""")[0]))

        // 输入比已知状态短（不可能前缀匹配）→ 全量
        val shortInput = listOf(userMsg("你好"))
        val (prevId, delta) = sessions.resolve(shortInput, body(shortInput))
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
        val (prevId, delta) = sessions.resolve(fullInput, body(fullInput))
        // 已知状态含 function_call → 无法增量 → 全量发送
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `pure text round trip still increments after tool session is cleared`() {
        val sessions = IncrementalSessions()
        // 第一轮：纯文本
        val firstInput = listOf(userMsg("你好"))
        sessions.update(body(firstInput), "resp_1", emptyList())
        // 第二轮：纯文本增量可用
        val secondInput = firstInput + listOf(
            parse("""{"type":"message","role":"assistant","content":[]}""")[0],
            userMsg("继续"),
        )
        val (prevId, delta) = sessions.resolve(secondInput, body(secondInput))
        assertNotNull(prevId)
        assertEquals(1, delta!!.size)
    }

    @Test
    fun `different conversations do not share increments`() {
        val sessions = IncrementalSessions()
        // 会话 A
        sessions.update(body(listOf(userMsg("A问"))), "resp_A", emptyList())
        // 会话 B（不同首条消息）
        val bInput = listOf(userMsg("B问"), userMsg("B再问"))
        val (prevId, delta) = sessions.resolve(bInput, body(bInput))
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `request property change falls back to full send`() {
        // [codex 对齐] 非 input 属性（model/reasoning/tools 等）变化时不能复用
        // previous_response_id（否则服务端沿用旧参数）
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        sessions.update(body(firstInput), "resp_1", emptyList())

        // 第二次请求换模型（signature 不同）→ 回退全量
        val secondInput = firstInput + listOf(userMsg("继续"))
        val differentBody = kotlinx.serialization.json.buildJsonObject {
            put("model", "different-model")
            put("input", kotlinx.serialization.json.JsonArray(secondInput))
        }
        val (prevId, delta) = sessions.resolve(secondInput, differentBody)
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `invalidate removes session`() {
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        sessions.update(body(firstInput), "resp_1", emptyList())
        assertEquals(1, sessions.size())

        sessions.invalidate(firstInput)
        assertEquals(0, sessions.size())

        // 失效后无法增量
        val secondInput = firstInput + listOf(userMsg("继续"))
        val (prevId, delta) = sessions.resolve(secondInput, body(secondInput))
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `stripItemIds removes id but keeps call_id`() {
        val input = json.parseToJsonElement(
            """
            [
              {"role":"user","content":"你好"},
              {"type":"message","id":"msg_abc","role":"assistant","content":[]},
              {"type":"function_call","id":"fc_xyz","call_id":"call_1","name":"t","arguments":"{}"}
            ]
            """.trimIndent()
        ).jsonArray
        val stripped = input.stripItemIds()
        // message 的 id 被移除
        assertEquals(null, stripped[1].jsonObject["id"])
        // function_call 的 id 被移除、call_id 保留
        assertEquals(null, stripped[2].jsonObject["id"])
        assertEquals("call_1", stripped[2].jsonObject["call_id"]!!.jsonPrimitive.content)
        // user 消息原样
        assertEquals("你好", stripped[0].jsonObject["content"]!!.jsonPrimitive.content)
        // 无 id 的 item 不受影响
        assertEquals(3, stripped.size)
    }
}
