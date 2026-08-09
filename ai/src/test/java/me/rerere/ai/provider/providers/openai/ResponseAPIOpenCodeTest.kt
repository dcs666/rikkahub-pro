package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for OpenCode Zen gateway (opencode.ai) Responses API handling:
 * - reasoning.effort: App's XHIGH is mapped to "max", MEDIUM to "high"
 *   (与 DeepSeek 官方一致，zen 网关代理 DeepSeek 系模型，官方枚举 low/high/max)
 * - reasoning history items use content array of reasoning_text (网关 Console provider
 *   要求：The reasoning_text in the thinking mode must be passed back to the API)
 * - 不请求 reasoning.summary / reasoning.encrypted_content（网关不支持）
 */
class ResponseAPIOpenCodeTest {

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
        )
    }

    private fun openCodeSetting() = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/zen/v1")

    @Test
    fun `xhigh maps to effort max on opencode zen responses api`() {
        val body = invokeBuildRequestBody(openCodeSetting(), reasoningParams(ReasoningLevel.XHIGH))
        assertEquals("max", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `medium maps to effort high on opencode zen responses api`() {
        val body = invokeBuildRequestBody(openCodeSetting(), reasoningParams(ReasoningLevel.MEDIUM))
        assertEquals("high", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `low keeps effort low on opencode zen responses api`() {
        val body = invokeBuildRequestBody(openCodeSetting(), reasoningParams(ReasoningLevel.LOW))
        assertEquals("low", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `auto omits effort on opencode zen responses api`() {
        val body = invokeBuildRequestBody(openCodeSetting(), reasoningParams(ReasoningLevel.AUTO))
        assertNull(body["reasoning"]?.jsonObject?.get("effort"))
    }

    @Test
    fun `off sends effort none on opencode zen responses api`() {
        val body = invokeBuildRequestBody(openCodeSetting(), reasoningParams(ReasoningLevel.OFF))
        assertEquals("none", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `opencode does not request summary or encrypted content`() {
        val body = invokeBuildRequestBody(openCodeSetting(), reasoningParams(ReasoningLevel.HIGH))
        assertNull(body["reasoning"]?.jsonObject?.get("summary"))
        assertNull(body["include"])
    }

    @Test
    fun `opencode reasoning history uses reasoning_text array content`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking trace"),
                UIMessagePart.Text("answer")
            )
        )
        val items = api.buildMessages(listOf(assistant), useReasoningTextArray = true)
        val reasoningItem = items.jsonArray.first { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        assertNull(reasoningItem.jsonObject["summary"])
        val content = reasoningItem.jsonObject["content"]?.jsonArray
        assertTrue(content != null && content.isNotEmpty())
        assertEquals("reasoning_text", content!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("thinking trace", content[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `opencode host buildRequestBody uses reasoning_text for history`() {
        // 走真实路径：host=opencode.ai 时历史 reasoning item 必须是 reasoning_text 数组
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking trace"),
                UIMessagePart.Text("answer")
            )
        )
        val body = api.buildRequestBody(
            openCodeSetting(),
            listOf(UIMessage.user("next"), assistant),
            reasoningParams(ReasoningLevel.HIGH),
            stream = false
        )
        val input = body["input"]?.jsonArray
        assertTrue(input != null)
        val reasoningItem = input!!.first { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        val content = reasoningItem.jsonObject["content"]?.jsonArray
        assertTrue(content != null && content.isNotEmpty())
        assertEquals("reasoning_text", content!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertNull(reasoningItem.jsonObject["summary"])
    }

    @Test
    fun `opencode tool message without captured reasoning gets placeholder reasoning item`() {
        // Console Go 网关 thinking mode：带工具调用的 assistant 消息必须回传非空 reasoning_text
        // （错误：The reasoning_text in the thinking mode must be passed back to the API）。
        // 历史消息未捕获思维链时用占位符补上，且 reasoning item 必须在 function_call 之前。
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(""),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "add",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("2")),
                )
            )
        )
        val body = api.buildRequestBody(
            openCodeSetting(),
            listOf(UIMessage.user("1+1=?"), assistant),
            reasoningParams(ReasoningLevel.XHIGH),
            stream = false
        )
        val input = body["input"]?.jsonArray!!
        val reasoningIdx = input.indexOfFirst { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        val fcIdx = input.indexOfFirst { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" }
        assertTrue(reasoningIdx >= 0)
        assertTrue(reasoningIdx < fcIdx)
        val text = input[reasoningIdx].jsonObject["content"]?.jsonArray
            ?.getOrNull(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
        assertEquals("…", text)
    }

    @Test
    fun `opencode blank reasoning part is replaced by placeholder`() {
        // 思维链 part 存在但文本为空时，网关同样拒绝（空 reasoning_text 实测 400），
        // useReasoningTextArray 分支必须用占位符兜底
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = ""),
                UIMessagePart.Text("answer"),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "add",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("2")),
                )
            )
        )
        val body = api.buildRequestBody(
            openCodeSetting(),
            listOf(UIMessage.user("1+1=?"), assistant),
            reasoningParams(ReasoningLevel.HIGH),
            stream = false
        )
        val input = body["input"]?.jsonArray!!
        val reasoningItem = input.first { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        val text = reasoningItem.jsonObject["content"]?.jsonArray
            ?.getOrNull(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
        assertEquals("…", text)
    }
}
