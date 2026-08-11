package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for DeepSeek Responses API (api.deepseek.com) handling:
 * - reasoning.effort: App's XHIGH is mapped to "max", MEDIUM to "high"
 *   (DeepSeek only supports low/high/max, see https://api-docs.deepseek.com/zh-cn/guides/thinking_mode/)
 * - temperature/top_p are omitted in thinking mode (not supported per docs)
 * - history reasoning items use plaintext content (summary not supported by DeepSeek)
 */
class ResponseAPIDeepSeekTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    private fun invokeBuildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
    ): JsonObject {
        return api.buildRequestBody(providerSetting, listOf(UIMessage.user("hi")), params, stream = false)
    }

    private fun reasoningParams(reasoningLevel: ReasoningLevel): TextGenerationParams {
        return TextGenerationParams(
            model = Model(
                modelId = "deepseek-v4-pro",
                abilities = listOf(ModelAbility.REASONING)
            ),
            reasoningLevel = reasoningLevel,
            temperature = 0.7f,
            topP = 0.9f,
        )
    }

    private fun deepSeekSetting() = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com")

    @Test
    fun `xhigh maps to effort max on deepseek responses api`() {
        val body = invokeBuildRequestBody(deepSeekSetting(), reasoningParams(ReasoningLevel.XHIGH))
        assertEquals("max", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `medium maps to effort high on deepseek responses api`() {
        val body = invokeBuildRequestBody(deepSeekSetting(), reasoningParams(ReasoningLevel.MEDIUM))
        assertEquals("high", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `low keeps effort low on deepseek responses api`() {
        val body = invokeBuildRequestBody(deepSeekSetting(), reasoningParams(ReasoningLevel.LOW))
        assertEquals("low", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `off sends effort none on deepseek responses api`() {
        val body = invokeBuildRequestBody(deepSeekSetting(), reasoningParams(ReasoningLevel.OFF))
        assertEquals("none", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `auto omits effort on deepseek responses api`() {
        val body = invokeBuildRequestBody(deepSeekSetting(), reasoningParams(ReasoningLevel.AUTO))
        assertNull(body["reasoning"]?.jsonObject?.get("effort"))
    }

    @Test
    fun `non deepseek host keeps passthrough effort for xhigh`() {
        // OpenAI 官方 Responses API 支持 xhigh 语义，透传不被破坏
        val body = invokeBuildRequestBody(
            ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            reasoningParams(ReasoningLevel.XHIGH)
        )
        assertEquals("xhigh", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `temperature and top_p omitted in deepseek thinking mode`() {
        val body = invokeBuildRequestBody(deepSeekSetting(), reasoningParams(ReasoningLevel.HIGH))
        assertNull(body["temperature"])
        assertNull(body["top_p"])
    }

    @Test
    fun `temperature sent when deepseek thinking disabled`() {
        val body = invokeBuildRequestBody(deepSeekSetting(), reasoningParams(ReasoningLevel.OFF))
        assertEquals("0.7", body["temperature"]?.jsonPrimitive?.content)
        assertEquals("0.9", body["top_p"]?.jsonPrimitive?.content)
    }

    @Test
    fun `deepseek request omits unsupported summary and encrypted content params`() {
        // DeepSeek Responses API 文档：reasoning.summary 可传入但不生成摘要；encrypted_content 不支持
        // → 不发送 reasoning.summary，也不请求 reasoning.encrypted_content 输出
        val body = invokeBuildRequestBody(deepSeekSetting(), reasoningParams(ReasoningLevel.HIGH))
        assertNull(body["reasoning"]?.jsonObject?.get("summary"))
        assertNull(body["include"])
    }

    @Test
    fun `non deepseek host keeps summary and encrypted content params`() {
        // OpenAI 官方 Responses API 不受影响：仍请求 summary 与 encrypted content
        val body = invokeBuildRequestBody(
            ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            reasoningParams(ReasoningLevel.HIGH)
        )
        assertEquals("auto", body["reasoning"]?.jsonObject?.get("summary")?.jsonPrimitive?.content)
        assertEquals("reasoning.encrypted_content", body["include"]?.jsonArray?.first()?.jsonPrimitive?.content)
    }

    @Test
    fun `deepseek reasoning history uses plaintext content not summary`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking trace"),
                UIMessagePart.Text("answer")
            )
        )
        val items = api.buildMessages(listOf(assistant), usePlainReasoningContent = true)
        val reasoningItem = items.jsonArray.first { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals("thinking trace", reasoningItem.jsonObject["content"]?.jsonPrimitive?.content)
        assertNull(reasoningItem.jsonObject["summary"])
    }

    @Test
    fun `default reasoning history keeps summary format`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking trace"),
                UIMessagePart.Text("answer")
            )
        )
        val items = api.buildMessages(listOf(assistant))
        val reasoningItem = items.jsonArray.first { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        assertNull(reasoningItem.jsonObject["content"])
        val summary = reasoningItem.jsonObject["summary"]?.jsonArray
        assertTrue(summary != null && summary.isNotEmpty())
        assertEquals("thinking trace", summary!![0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    // ---- 非流式响应解析（parseResponseOutput）----

    private fun responseWithReasoningAndMessage(reasoningJson: String): JsonObject {
        return buildJsonObject {
            put("output", buildJsonArray {
                add(Json.parseToJsonElement(reasoningJson).jsonObject)
                add(buildJsonObject {
                    put("type", "message")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "output_text")
                            put("text", "answer")
                        })
                    })
                })
            })
        }
    }

    private fun reasoningParts(chunk: MessageChunk): List<UIMessagePart.Reasoning> {
        return chunk.choices[0].message!!.parts.filterIsInstance<UIMessagePart.Reasoning>()
    }

    @Test
    fun `non-stream deepseek reasoning uses plaintext content string`() {
        // DeepSeek 非流式响应：reasoning item 无 summary，content 是明文字符串
        val response = responseWithReasoningAndMessage(
            """{"type":"reasoning","id":"rs_1","content":"thinking trace"}"""
        )
        val chunk = parseResponseOutput(response)
        val reasoning = reasoningParts(chunk)
        assertEquals(1, reasoning.size)
        assertEquals("thinking trace", reasoning[0].reasoning)
    }

    @Test
    fun `non-stream opencode reasoning uses content array of reasoning_text`() {
        // OpenCode Zen 网关非流式响应：content 是 reasoning_text 数组
        val response = responseWithReasoningAndMessage(
            """{"type":"reasoning","id":"rs_1","content":[{"type":"reasoning_text","text":"thinking trace"}]}"""
        )
        val chunk = parseResponseOutput(response)
        val reasoning = reasoningParts(chunk)
        assertEquals(1, reasoning.size)
        assertEquals("thinking trace", reasoning[0].reasoning)
    }

    @Test
    fun `non-stream openai reasoning uses summary array`() {
        // OpenAI 官方非流式响应：summary 数组（原有行为保持不变）
        val response = responseWithReasoningAndMessage(
            """{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"thinking trace"}]}"""
        )
        val chunk = parseResponseOutput(response)
        val reasoning = reasoningParts(chunk)
        assertEquals(1, reasoning.size)
        assertEquals("thinking trace", reasoning[0].reasoning)
    }

    @Test
    fun `non-stream reasoning with empty content does not crash`() {
        // 网关返回空 reasoning item（无 summary/content）时不抛异常
        val response = responseWithReasoningAndMessage(
            """{"type":"reasoning","id":"rs_1"}"""
        )
        val chunk = parseResponseOutput(response)
        assertTrue(reasoningParts(chunk).isEmpty())
    }

    // ---- 截断 / 失败事件（DeepSeek 官方：流无 [DONE]，以 completed/incomplete/failed 结束）----

    @Test
    fun `stream incomplete event carries length finish reason`() {
        // response.incomplete：达到 max_output_tokens 截断时的最后一个事件
        val event = Json.parseToJsonElement(
            """{"type":"response.incomplete","item_id":"evt_1","response":{"status":"incomplete"}}"""
        ).jsonObject
        val chunk = parseResponseDelta(event)
        assertEquals("length", chunk?.choices?.getOrNull(0)?.finishReason)
    }

    @Test(expected = RuntimeException::class)
    fun `stream failed event throws`() {
        // response.failed：响应失败时的最后一个事件，携带 error 详情 → 抛给调用方
        val event = Json.parseToJsonElement(
            """{"type":"response.failed","response":{"error":{"type":"server_error","message":"boom"}}}"""
        ).jsonObject
        parseResponseDelta(event)
    }

    @Test
    fun `non-stream incomplete status carries length finish reason`() {
        // 非流式响应 status=incomplete（截断）→ finishReason=length → #51 截断提示生效
        val response = responseWithReasoningAndMessage(
            """{"type":"reasoning","id":"rs_1","content":"thinking trace"}"""
        )
        val body = buildJsonObject {
            put("status", "incomplete")
            put("output", response["output"]!!)
        }
        val chunk = parseResponseOutput(body)
        assertEquals("length", chunk.choices[0].finishReason)
        assertEquals("thinking trace", reasoningParts(chunk)[0].reasoning)
    }

    @Test(expected = RuntimeException::class)
    fun `non-stream failed status throws`() {
        // 非流式响应 status=failed → 抛出 error 详情
        val response = buildJsonObject {
            put("status", "failed")
            put("output", buildJsonArray { })
            put("error", buildJsonObject {
                put("type", "server_error")
                put("message", "boom")
            })
        }
        parseResponseOutput(response)
    }
}
