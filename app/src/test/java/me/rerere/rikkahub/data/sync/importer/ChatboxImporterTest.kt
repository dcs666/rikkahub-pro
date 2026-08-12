package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：Chatbox 导出 provider 导入（importProviders(root: JsonObject)）。
 *
 * detect-changes 报 importProviders 测试缺口（极致优化专项遗留）：本测试锁定
 * settings.providers.openai/claude/gemini 的字段映射（apiHost/apiKey/models、
 * capabilities → modalities/abilities、baseUrl 拼接、空 key 跳过、缺省值）。
 * 文件级入口（android.util.JsonReader 流式）依赖 Android 无法 JVM 单测，豁免。
 */
class ChatboxImporterTest {

    private fun providerBlock(
        openai: JsonObject? = null,
        claude: JsonObject? = null,
        gemini: JsonObject? = null,
    ): JsonObject = JsonObject(
        buildMap {
            put("providers", JsonObject(buildMap {
                openai?.let { put("openai", it) }
                claude?.let { put("claude", it) }
                gemini?.let { put("gemini", it) }
            }))
        }
    )

    private fun root(settings: JsonObject?): JsonObject = JsonObject(
        buildMap {
            settings?.let { put("settings", it) }
        }
    )

    private fun modelEntry(
        modelId: String,
        capabilities: List<String> = emptyList(),
    ): JsonObject = JsonObject(
        buildMap {
            put("modelId", modelId)
            put("capabilities", JsonArray(capabilities.map { JsonPrimitive(it) }))
        }
    )

    // ---- 空/缺省 ----

    @Test
    fun `empty root returns empty providers`() {
        assertTrue(ChatboxImporter.importProviders(buildJsonObject {}).isEmpty())
        assertTrue(ChatboxImporter.importProviders(root(null)).isEmpty())
        assertTrue(ChatboxImporter.importProviders(root(providerBlock())).isEmpty())
    }

    // ---- OpenAI ----

    @Test
    fun `openai maps apiHost apiKey and models with capability mapping`() {
        val result = ChatboxImporter.importProviders(
            root(
                providerBlock(
                    openai = buildJsonObject {
                        put("apiHost", "https://my-gateway.example.com/")
                        put("apiKey", "sk-123")
                        put(
                            "models",
                            JsonArray(
                                listOf(
                                    modelEntry("gpt-5", listOf("vision", "tool_use", "reasoning")),
                                    modelEntry("gpt-mini"),
                                )
                            )
                        )
                    }
                )
            )
        )

        assertEquals(1, result.size)
        val provider = result.single() as ProviderSetting.OpenAI
        assertEquals("https://my-gateway.example.com/v1", provider.baseUrl)
        assertEquals("sk-123", provider.apiKey)
        assertEquals(2, provider.models.size)

        val gpt5 = provider.models.first()
        assertEquals("gpt-5", gpt5.modelId)
        assertTrue(Modality.IMAGE in gpt5.inputModalities)
        assertTrue(ModelAbility.TOOL in gpt5.abilities)
        assertTrue(ModelAbility.REASONING in gpt5.abilities)

        val gptMini = provider.models[1]
        assertTrue(Modality.IMAGE !in gptMini.inputModalities)
        assertTrue(gptMini.abilities.isEmpty())
    }

    @Test
    fun `openai default host and blank key skipped`() {
        val withKey = ChatboxImporter.importProviders(
            root(providerBlock(openai = buildJsonObject { put("apiKey", "sk-x") }))
        )
        assertEquals(1, withKey.size)
        assertEquals("https://api.openai.com/v1", (withKey.single() as ProviderSetting.OpenAI).baseUrl)

        val noKey = ChatboxImporter.importProviders(
            root(providerBlock(openai = buildJsonObject { put("apiKey", "") }))
        )
        assertTrue(noKey.isEmpty())
    }

    // ---- Claude ----

    @Test
    fun `claude maps host and key`() {
        val result = ChatboxImporter.importProviders(
            root(
                providerBlock(
                    claude = buildJsonObject {
                        put("apiHost", "https://claude.example.com")
                        put("apiKey", "sk-ant-456")
                    }
                )
            )
        )

        assertEquals(1, result.size)
        val provider = result.single() as ProviderSetting.Claude
        assertEquals("https://claude.example.com/v1", provider.baseUrl)
        assertEquals("sk-ant-456", provider.apiKey)
    }

    @Test
    fun `claude blank key skipped`() {
        val result = ChatboxImporter.importProviders(
            root(providerBlock(claude = buildJsonObject { put("apiKey", "") }))
        )
        assertTrue(result.isEmpty())
    }

    // ---- Gemini ----

    @Test
    fun `gemini maps host and key`() {
        val result = ChatboxImporter.importProviders(
            root(
                providerBlock(
                    gemini = buildJsonObject {
                        put("apiHost", "https://gemini.example.com/")
                        put("apiKey", "AIza789")
                    }
                )
            )
        )

        assertEquals(1, result.size)
        val provider = result.single() as ProviderSetting.Google
        assertEquals("https://gemini.example.com/v1beta", provider.baseUrl)
        assertEquals("AIza789", provider.apiKey)
    }

    @Test
    fun `gemini blank key skipped`() {
        val result = ChatboxImporter.importProviders(
            root(providerBlock(gemini = buildJsonObject { put("apiKey", " ") }))
        )
        assertTrue(result.isEmpty())
    }

    // ---- 混合 ----

    @Test
    fun `all three providers imported together`() {
        val result = ChatboxImporter.importProviders(
            root(
                providerBlock(
                    openai = buildJsonObject { put("apiKey", "sk-1") },
                    claude = buildJsonObject { put("apiKey", "sk-ant-2") },
                    gemini = buildJsonObject { put("apiKey", "AIza3") },
                )
            )
        )
        assertEquals(3, result.size)
        assertTrue(result[0] is ProviderSetting.OpenAI)
        assertTrue(result[1] is ProviderSetting.Claude)
        assertTrue(result[2] is ProviderSetting.Google)
    }
}
