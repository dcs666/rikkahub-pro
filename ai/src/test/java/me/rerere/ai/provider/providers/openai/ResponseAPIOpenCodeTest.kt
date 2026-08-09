package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for OpenCode Zen gateway (opencode.ai) Responses API handling:
 * - reasoning.effort: App's XHIGH is mapped to "max", MEDIUM to "high"
 *   (与 DeepSeek 官方一致，zen 网关代理 DeepSeek 系模型，官方枚举 low/high/max)
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
}
