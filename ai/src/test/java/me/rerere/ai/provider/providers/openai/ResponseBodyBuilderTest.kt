package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseBodyBuilderTest {

    private fun tool(name: String, description: String = "desc") = Tool(
        name = name,
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("q", buildJsonObject {
                        put("type", "string")
                        put("description", "query")
                    })
                },
                required = listOf("q"),
            )
        },
        execute = { emptyList() },
    )

    private fun model(
        abilities: List<ModelAbility> = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        tools: Set<BuiltInTools> = emptySet(),
    ) = Model(
        modelId = "test-model",
        abilities = abilities,
        tools = tools,
    )

    private fun buildBody(
        model: Model = model(),
        tools: List<Tool> = emptyList(),
        baseUrl: String = "https://opencode.ai/zen",
        reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    ): JsonObject = buildRequestBody(
        providerSetting = ProviderSetting.OpenAI(baseUrl = baseUrl, apiKey = "test"),
        messages = emptyList(),
        params = TextGenerationParams(
            model = model,
            tools = tools,
            reasoningLevel = reasoningLevel,
        ),
        stream = true,
    )

    private fun toolsArray(body: JsonObject): JsonArray? =
        body["tools"]?.jsonArray

    @Test
    fun `空名工具被跳过 正常工具保留`() {
        val body = buildBody(
            tools = listOf(
                tool(name = ""),
                tool(name = "  "),
                tool(name = "good_tool"),
            )
        )

        val tools = toolsArray(body)!!
        assertEquals(1, tools.size)
        val first = tools[0].jsonObject
        assertEquals("function", first["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("good_tool", first["name"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `正常工具字段完整`() {
        val body = buildBody(tools = listOf(tool(name = "search", description = "Search the web")))

        val tools = toolsArray(body)!!
        val first = tools[0].jsonObject
        assertEquals("function", first["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("search", first["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Search the web", first["description"]?.jsonPrimitive?.contentOrNull)
        val params = first["parameters"]?.jsonObject
        assertTrue(params != null)
        assertEquals("object", params!!["type"]?.jsonPrimitive?.contentOrNull)
        assertTrue(params["properties"]?.jsonObject?.containsKey("q") == true)
        assertEquals(listOf("q"), params["required"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull })
    }

    @Test
    fun `无 TOOL 能力时函数工具不发`() {
        val body = buildBody(
            model = model(abilities = emptyList()),
            tools = listOf(tool(name = "search")),
        )

        assertNull("无 TOOL 能力不应有 tools 键", body["tools"])
    }

    @Test
    fun `built-in 工具追加在函数工具后`() {
        val body = buildBody(
            model = model(tools = setOf(BuiltInTools.Search, BuiltInTools.ImageGeneration)),
            tools = listOf(tool(name = "fn_tool")),
        )

        val tools = toolsArray(body)!!
        assertEquals(3, tools.size)
        // 函数工具在前
        assertEquals("function", tools[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        // 内置工具在后
        assertEquals("web_search", tools[1].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image_generation", tools[2].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("gpt-image-2", tools[2].jsonObject["model"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `UrlContext 内置工具跳过且不发空 tools 数组`() {
        val body = buildBody(model = model(tools = setOf(BuiltInTools.UrlContext)))

        // UrlContext 不支持：不应发空数组 "tools": []（部分网关 schema 拒绝空数组）
        assertNull(body["tools"])
    }

    @Test
    fun `UrlContext 与其他内置混用时跳过 UrlContext`() {
        val body = buildBody(
            model = model(tools = setOf(BuiltInTools.UrlContext, BuiltInTools.Search)),
        )

        val tools = toolsArray(body)!!
        assertEquals(1, tools.size)
        assertEquals("web_search", tools[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `无工具时无 tools 键`() {
        val body = buildBody()
        assertNull(body["tools"])
    }

    @Test
    fun `opencode 网关 store 为 true`() {
        val body = buildBody(baseUrl = "https://opencode.ai/zen")
        assertEquals(true, body["store"]?.jsonPrimitive?.contentOrNull?.toBoolean())
    }

    @Test
    fun `非 opencode 网关且未开启增量时 store 为 false`() {
        val body = buildBody(baseUrl = "https://api.openai.com/v1")
        assertEquals(false, body["store"]?.jsonPrimitive?.contentOrNull?.toBoolean())
    }

    @Test
    fun `deepseek 思考模式不发送 temperature`() {
        val body = buildBody(
            baseUrl = "https://api.deepseek.com",
            reasoningLevel = ReasoningLevel.HIGH,
        )
        assertFalse("deepseek thinking 模式不应有 temperature", body.containsKey("temperature"))
    }

    @Test
    fun `model 和 stream 字段正确`() {
        val body = buildBody()
        assertEquals("test-model", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, body["stream"]?.jsonPrimitive?.contentOrNull?.toBoolean())
    }
}
