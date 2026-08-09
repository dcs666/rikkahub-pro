package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for OpenCode Zen gateway (opencode.ai) chat completions thinking handling:
 * - reasoning_effort: App's XHIGH is mapped to "max", MEDIUM to "high"
 *   (zen 网关 chat/completions 端点代理 DeepSeek V4 系模型，官方枚举 low/high/max，
 *   见 https://opencode.ai/docs/zh-cn/zen/)
 */
class ChatCompletionsAPIOpenCodeTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    // Helper to invoke private buildChatCompletionRequest via reflection
    private fun buildRequest(
        modelId: String,
        reasoningLevel: ReasoningLevel,
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        val model = Model(
            modelId = modelId,
            abilities = listOf(ModelAbility.REASONING)
        )
        val params = TextGenerationParams(
            model = model,
            reasoningLevel = reasoningLevel,
        )
        val providerSetting = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/zen/v1")
        return method.invoke(
            api,
            listOf(UIMessage.user("hi")),
            params,
            providerSetting,
            true
        ) as JsonObject
    }

    @Test
    fun `xhigh maps to reasoning_effort max on opencode zen`() {
        val body = buildRequest("deepseek-v4-pro", ReasoningLevel.XHIGH)
        assertEquals("max", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `medium maps to reasoning_effort high on opencode zen`() {
        val body = buildRequest("deepseek-v4-pro", ReasoningLevel.MEDIUM)
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `high keeps reasoning_effort high on opencode zen`() {
        val body = buildRequest("deepseek-v4-pro", ReasoningLevel.HIGH)
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `low keeps reasoning_effort low on opencode zen`() {
        val body = buildRequest("deepseek-v4-pro", ReasoningLevel.LOW)
        assertEquals("low", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `off sends reasoning_effort none on opencode zen`() {
        val body = buildRequest("deepseek-v4-pro", ReasoningLevel.OFF)
        assertEquals("none", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `auto omits reasoning_effort on opencode zen`() {
        val body = buildRequest("deepseek-v4-pro", ReasoningLevel.AUTO)
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun `xhigh mapping applies to v4-flash on opencode zen`() {
        val body = buildRequest("deepseek-v4-flash", ReasoningLevel.XHIGH)
        assertEquals("max", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `opencode tool message without reasoning gets empty reasoning_content`() {
        // Console Go 网关 thinking mode：带工具调用的 assistant 消息必须携带 reasoning_content
        // （实测空字符串可接受；错误：The reasoning_content in the thinking mode must be passed
        // back to the API）。历史消息未捕获思维链时补空字符串占位。
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        val model = Model(
            modelId = "deepseek-v4-flash",
            abilities = listOf(ModelAbility.REASONING)
        )
        val params = TextGenerationParams(model = model, reasoningLevel = ReasoningLevel.XHIGH)
        val providerSetting = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/zen/v1")
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "add",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("2")),
                )
            )
        )
        val body = method.invoke(
            api,
            listOf(UIMessage.user("1+1=?"), assistant),
            params,
            providerSetting,
            false
        ) as JsonObject
        val messages = body["messages"]?.jsonArray
        val assistantMsg = messages!!.first {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }.jsonObject
        assertEquals("", assistantMsg["reasoning_content"]?.jsonPrimitive?.content)
    }
}
