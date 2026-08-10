package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    private val host = "opencode.ai"

    private fun body(input: List<JsonElement>): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("model", "test-model")
            put("input", kotlinx.serialization.json.JsonArray(input))
        }

    private fun userMsg(text: String) = buildJsonObject {
        put("role", "user")
        put("content", text)
    }

    private fun assistantMsg(text: String) = buildJsonObject {
        put("role", "assistant")
        put("content", text)
    }

    private fun parse(text: String): JsonObject =
        json.parseToJsonElement(text).jsonObject

    @Test
    fun `first request has no increment`() {
        val sessions = IncrementalSessions()
        val input = listOf(userMsg("你好"))
        val (prevId, delta) = sessions.resolve(host, input, body(input))
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `second request with same prefix gets increment`() {
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        // 首次请求：记录会话（模拟服务端返回 output item——纯文本 assistant 消息）
        sessions.update(
            host, body(firstInput), "resp_1",
            listOf(parse("""{"type":"message","role":"assistant","content":[{"type":"output_text","text":"你好呀"}]}"""))
        )

        // 第二轮：完整 input = 首轮 input + 首轮输出回显 + 新 user 消息
        val fullInput = firstInput + listOf(
            assistantMsg("你好呀"),
            userMsg("继续"),
        )
        val (prevId, delta) = sessions.resolve(host, fullInput, body(fullInput))
        assertNotNull(prevId)
        assertEquals("resp_1", prevId)
        // 增量 = 仅新 user 消息（assistant 回显已被过滤——服务端已保存上次 output）
        assertEquals(1, delta!!.size)
        assertEquals("继续", delta[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `edited history falls back to full send`() {
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        sessions.update(host, body(firstInput), "resp_1", listOf(parse("""{"type":"message","role":"assistant","content":[]}""")))

        // 历史被编辑（首条消息内容变了）→ 前缀不匹配 → 无法增量
        val editedInput = listOf(userMsg("你好吗")) + listOf(
            parse("""{"type":"message","role":"assistant","content":[]}"""),
            userMsg("继续"),
        )
        val (prevId, delta) = sessions.resolve(host, editedInput, body(editedInput))
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `shorter input falls back to full send`() {
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        sessions.update(host, body(firstInput), "resp_1", listOf(parse("""{"type":"message","role":"assistant","content":[]}""")))

        // 输入比已知状态短（不可能前缀匹配）→ 全量
        val shortInput = listOf(userMsg("你好"))
        val (prevId, delta) = sessions.resolve(host, shortInput, body(shortInput))
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `tool call round trip falls back to full send`() {
        // [修复 P0-1] 工具轮次增量会导致服务端上下文 fc 重复 + 占位 "…" 累积
        //（消息追加语义），一律回退全量
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("查天气"))
        val fcItem = parse("""{"type":"function_call","call_id":"c1","name":"get_weather","arguments":"{}"}""")
        sessions.update(host, body(firstInput), "resp_1", listOf(fcItem))

        // 工具执行后：完整 input = 首轮 + function_call + function_call_output + 新消息
        val fcoItem = parse("""{"type":"function_call_output","call_id":"c1","output":"ok"}""")
        val fullInput = firstInput + listOf(fcItem, fcoItem, userMsg("继续"))
        val (prevId, delta) = sessions.resolve(host, fullInput, body(fullInput))
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `two tool calls fall back to full send`() {
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("查天气"))
        val fc1 = parse("""{"type":"function_call","call_id":"c1","name":"get_weather","arguments":"{}"}""")
        val fc2 = parse("""{"type":"function_call","call_id":"c2","name":"get_weather","arguments":"{}"}""")
        sessions.update(host, body(firstInput), "resp_1", listOf(fc1, fc2))

        val fco1 = parse("""{"type":"function_call_output","call_id":"c1","output":"ok"}""")
        val fco2 = parse("""{"type":"function_call_output","call_id":"c2","output":"ok"}""")
        val fullInput = firstInput + listOf(fc1, fco1, fc2, fco2)
        val (prevId, delta) = sessions.resolve(host, fullInput, body(fullInput))
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `pure text round trip increments with echo filtered`() {
        val sessions = IncrementalSessions()
        // 第一轮：纯文本
        val firstInput = listOf(userMsg("你好"))
        val out1 = parse("""{"type":"message","role":"assistant","content":[{"type":"output_text","text":"在的"}]}""")
        sessions.update(host, body(firstInput), "resp_1", listOf(out1))
        // 第二轮：纯文本增量可用，且上一轮 output 回显被过滤
        val secondInput = firstInput + listOf(assistantMsg("在的"), userMsg("继续"))
        val (prevId, delta) = sessions.resolve(host, secondInput, body(secondInput))
        assertNotNull(prevId)
        assertEquals(1, delta!!.size)
        assertEquals("继续", delta[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `different conversations do not share increments`() {
        val sessions = IncrementalSessions()
        // 会话 A
        sessions.update(host, body(listOf(userMsg("A问"))), "resp_A", emptyList())
        // 会话 B（不同首条消息）
        val bInput = listOf(userMsg("B问"), userMsg("B再问"))
        val (prevId, delta) = sessions.resolve(host, bInput, body(bInput))
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `different hosts do not share increments`() {
        // [修复 P1-4] host 隔离：同一 input 在不同 host 不共享增量会话
        val sessions = IncrementalSessions()
        sessions.update("opencode.ai", body(listOf(userMsg("你好"))), "resp_A", emptyList())
        val sameInput = listOf(userMsg("你好"), userMsg("继续"))
        val (prevId, delta) = sessions.resolve("api.deepseek.com", sameInput, body(sameInput))
        assertNull(prevId)
        assertNull(delta)
        // 同 host 仍可增量
        val (prevId2, delta2) = sessions.resolve("opencode.ai", sameInput, body(sameInput))
        assertNotNull(prevId2)
        assertEquals("resp_A", prevId2)
        assertEquals(1, delta2!!.size)
    }

    @Test
    fun `request property change falls back to full send`() {
        // [codex 对齐] 非 input 属性（model/reasoning/tools 等）变化时不能复用
        // previous_response_id（否则服务端沿用旧参数）
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        sessions.update(host, body(firstInput), "resp_1", emptyList())

        // 第二次请求换模型（signature 不同）→ 回退全量
        val secondInput = firstInput + listOf(userMsg("继续"))
        val differentBody = kotlinx.serialization.json.buildJsonObject {
            put("model", "different-model")
            put("input", kotlinx.serialization.json.JsonArray(secondInput))
        }
        val (prevId, delta) = sessions.resolve(host, secondInput, differentBody)
        assertNull(prevId)
        assertNull(delta)
    }

    @Test
    fun `invalidate removes session`() {
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        sessions.update(host, body(firstInput), "resp_1", emptyList())
        assertEquals(1, sessions.size())

        sessions.invalidate(host, firstInput)
        assertEquals(0, sessions.size())

        // 失效后无法增量
        val secondInput = firstInput + listOf(userMsg("继续"))
        val (prevId, delta) = sessions.resolve(host, secondInput, body(secondInput))
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

    @Test
    fun `assistant echo with array content is filtered`() {
        // [修复 F11] 数组形式 content（多部分输出）的回显也能被识别过滤
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        val out1 = parse("""{"type":"message","role":"assistant","content":[{"type":"output_text","text":"第一段"},{"type":"output_text","text":"第二段"}]}""")
        sessions.update(host, body(firstInput), "resp_1", listOf(out1))

        // App 回显 assistant 消息（数组形式 content）
        val echo = buildJsonObject {
            put("role", "assistant")
            put("content", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("type", "output_text"); put("text", "第一段") },
                buildJsonObject { put("type", "output_text"); put("text", "第二段") },
            )))
        }
        val fullInput = firstInput + listOf(echo, userMsg("继续"))
        val (prevId, delta) = sessions.resolve(host, fullInput, body(fullInput))
        assertNotNull(prevId)
        assertEquals(1, delta!!.size)
        assertEquals("继续", delta[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `user message with same text as last output is not filtered`() {
        // [修复 F11] 用户消息即使内容与上次输出相同也不能被过滤（role 不同）
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        val out1 = parse("""{"type":"message","role":"assistant","content":[{"type":"output_text","text":"继续"}]}""")
        sessions.update(host, body(firstInput), "resp_1", listOf(out1))

        val fullInput = firstInput + listOf(assistantMsg("继续"), userMsg("继续"))
        val (prevId, delta) = sessions.resolve(host, fullInput, body(fullInput))
        assertNotNull(prevId)
        // 只有 assistant 回显被过滤，user 消息（内容相同但 role 不同）保留
        assertEquals(1, delta!!.size)
        assertEquals("user", delta[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reasoning echo is filtered`() {
        // [修复 F16] thinking 模式：上次 output 的 reasoning（思维链）回显必须被过滤，
        // 否则增量退化为"思维链照样重传"（服务端上下文思维链重复 + 增量收益丢失）
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        // 服务端 output：reasoning item + message item（思考模式典型响应）
        val outReasoning = parse("""{"type":"reasoning","id":"r1","content":[{"type":"reasoning_text","text":"让我想想"}]}""")
        val outMessage = parse("""{"type":"message","role":"assistant","content":[{"type":"output_text","text":"在的"}]}""")
        sessions.update(host, body(firstInput), "resp_1", listOf(outReasoning, outMessage))

        // 第二轮：App 回显 reasoning（无 id、content 数组）+ message 回显 + 新 user 消息
        val echoReasoning = parse("""{"type":"reasoning","content":[{"type":"reasoning_text","text":"让我想想"}]}""")
        val fullInput = firstInput + listOf(echoReasoning, assistantMsg("在的"), userMsg("继续"))
        val (prevId, delta) = sessions.resolve(host, fullInput, body(fullInput))
        assertNotNull(prevId)
        assertEquals("resp_1", prevId)
        // 增量 = 仅新 user 消息（reasoning 回显 + message 回显都被过滤）
        assertEquals(1, delta!!.size)
        assertEquals("继续", delta[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reasoning echo with plain content is filtered`() {
        // [修复 F16] DeepSeek 明文 content 形式（usePlainReasoningContent）的回显也能过滤
        val sessions = IncrementalSessions()
        val firstInput = listOf(userMsg("你好"))
        val outReasoning = parse("""{"type":"reasoning","id":"r1","content":"明文思维链"}""")
        val outMessage = parse("""{"type":"message","role":"assistant","content":[{"type":"output_text","text":"在的"}]}""")
        sessions.update(host, body(firstInput), "resp_1", listOf(outReasoning, outMessage))

        val echoReasoning = parse("""{"type":"reasoning","content":"明文思维链"}""")
        val fullInput = firstInput + listOf(echoReasoning, assistantMsg("在的"), userMsg("继续"))
        val (prevId, delta) = sessions.resolve(host, fullInput, body(fullInput))
        assertNotNull(prevId)
        assertEquals(1, delta!!.size)
        assertEquals("继续", delta[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `lru evicts oldest session when full`() {
        // [修复 F10] 超过上限时淘汰最久未用，而非整体清空
        val sessions = IncrementalSessions()
        // 填满 32 个会话
        for (i in 0 until 32) {
            sessions.update(host, body(listOf(userMsg("会话$i"))), "resp_$i", emptyList())
        }
        assertEquals(32, sessions.size())
        // 再更新会话 0（触碰 lastUsedAt 无法直接控制，但 update 同 bucket 覆盖后仍为 32）
        sessions.update(host, body(listOf(userMsg("会话0"))), "resp_0_new", emptyList())
        assertEquals(32, sessions.size())
        // 第 33 个会话插入 → 淘汰 1 个（最久未用的"会话1"）
        sessions.update(host, body(listOf(userMsg("会话32"))), "resp_32", emptyList())
        assertEquals(32, sessions.size())
        // 会话 1 的增量已失效
        val input1 = listOf(userMsg("会话1"), userMsg("继续"))
        val (prevId1, _) = sessions.resolve(host, input1, body(input1))
        assertNull(prevId1)
        // 会话 0（刚更新过）仍有效
        val input0 = listOf(userMsg("会话0"), userMsg("继续"))
        val (prevId0, _) = sessions.resolve(host, input0, body(input0))
        assertNotNull(prevId0)
    }
}
