package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 回归测试：Chatbox 导出解析（流式导入路径 parseSession）。
 *
 * 背景：importConversations 整树加载旧路径（eb1f491b 流式改造后遗留）已删除，
 * parseSession 成为唯一会话解析入口（极致优化专项）。本测试锁定其行为，
 * 防止后续改动破坏 system prompt 合并 / image 跳过计数 / 空消息计数 / tool 解析。
 */
class ChatboxSessionParseTest {

    private val assistantId = Uuid.random()

    private fun textPart(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun session(
        id: String = "sess-1",
        name: String = "Fallback Name",
        threadName: String = "My Thread",
        messages: JsonArray,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("threadName", threadName)
        put("messages", messages)
    }

    @Test
    fun `basic session parses user and assistant messages`() {
        val result = ChatboxImporter.parseSession(
            session(
                messages = JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("timestamp", 1700000000000L)
                            putJsonArray("contentParts") { add(textPart("Hello")) }
                        },
                        buildJsonObject {
                            put("role", "assistant")
                            put("timestamp", 1700000001000L)
                            putJsonArray("contentParts") { add(textPart("Hi there")) }
                        },
                    )
                )
            ),
            assistantId = assistantId,
            providers = emptyList(),
        )

        val conversation = result.conversation
        assertNotNull(conversation)
        assertEquals("My Thread", conversation!!.title)
        assertEquals(2, conversation.messageNodes.size)
        assertEquals(MessageRole.USER, conversation.messageNodes[0].messages[0].role)
        assertEquals(MessageRole.ASSISTANT, conversation.messageNodes[1].messages[0].role)
        assertEquals(
            listOf("Hello", "Hi there"),
            conversation.messageNodes.map { it.messages[0].parts.single() as UIMessagePart.Text }.map { it.text },
        )
        assertEquals(0, result.skippedImageParts)
        assertEquals(0, result.skippedEmptyMessages)
    }

    @Test
    fun `leading system messages merge into customSystemPrompt and are dropped`() {
        val result = ChatboxImporter.parseSession(
            session(
                messages = JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "system")
                            putJsonArray("contentParts") { add(textPart("You are helpful.")) }
                        },
                        buildJsonObject {
                            put("role", "system")
                            putJsonArray("contentParts") { add(textPart("Be concise.")) }
                        },
                        buildJsonObject {
                            put("role", "user")
                            put("timestamp", 1700000000000L)
                            putJsonArray("contentParts") { add(textPart("Hi")) }
                        },
                    )
                )
            ),
            assistantId = assistantId,
            providers = emptyList(),
        )

        val conversation = result.conversation!!
        assertEquals("You are helpful.\n\nBe concise.", conversation.customSystemPrompt)
        assertEquals(1, conversation.messageNodes.size)
    }

    @Test
    fun `image parts are skipped and counted`() {
        val result = ChatboxImporter.parseSession(
            session(
                messages = JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("timestamp", 1700000000000L)
                            putJsonArray("contentParts") {
                                add(textPart("Look at this"))
                                add(buildJsonObject {
                                    put("type", "image")
                                    put("storageKey", "blob/x.png")
                                })
                            }
                        },
                    )
                )
            ),
            assistantId = assistantId,
            providers = emptyList(),
        )

        assertNotNull(result.conversation)
        assertEquals(1, result.skippedImageParts)
        // 图片被跳过但文本保留 → 会话仍有效
        assertEquals(1, result.conversation!!.messageNodes.size)
    }

    @Test
    fun `empty message is skipped and counted`() {
        val result = ChatboxImporter.parseSession(
            session(
                messages = JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("timestamp", 1700000000000L)
                            putJsonArray("contentParts") { add(buildJsonObject { put("type", "image") }) }
                        },
                        buildJsonObject {
                            put("role", "assistant")
                            put("timestamp", 1700000001000L)
                            putJsonArray("contentParts") { add(textPart("Still here")) }
                        },
                    )
                )
            ),
            assistantId = assistantId,
            providers = emptyList(),
        )

        val conversation = result.conversation!!
        assertEquals(1, conversation.messageNodes.size)
        assertEquals(1, result.skippedImageParts)
        assertEquals(1, result.skippedEmptyMessages)
    }

    @Test
    fun `content fallback is used when contentParts is empty`() {
        val result = ChatboxImporter.parseSession(
            session(
                messages = JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("timestamp", 1700000000000L)
                            put("content", "Plain text message")
                        },
                    )
                )
            ),
            assistantId = assistantId,
            providers = emptyList(),
        )

        val conversation = result.conversation!!
        val part = conversation.messageNodes[0].messages[0].parts.single() as UIMessagePart.Text
        assertEquals("Plain text message", part.text)
    }

    @Test
    fun `tool call part is parsed with id name and args`() {
        val result = ChatboxImporter.parseSession(
            session(
                messages = JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("timestamp", 1700000000000L)
                            putJsonArray("contentParts") { add(textPart("Search for X")) }
                        },
                        buildJsonObject {
                            put("role", "assistant")
                            put("timestamp", 1700000001000L)
                            putJsonArray("contentParts") {
                                add(buildJsonObject {
                                    put("type", "tool-call")
                                    put("toolCallId", "call_123")
                                    put("toolName", "web_search")
                                    putJsonObject("args") {
                                        put("query", "X")
                                    }
                                })
                            }
                        },
                    )
                )
            ),
            assistantId = assistantId,
            providers = emptyList(),
        )

        val conversation = result.conversation!!
        val toolPart = conversation.messageNodes[1].messages[0].parts.single() as UIMessagePart.Tool
        assertEquals("call_123", toolPart.toolCallId)
        assertEquals("web_search", toolPart.toolName)
        assertTrue(toolPart.input.contains("\"query\""))
        assertTrue(toolPart.input.contains("X"))
    }

    @Test
    fun `session without id returns null conversation`() {
        val result = ChatboxImporter.parseSession(
            session(
                id = "",
                messages = JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("timestamp", 1700000000000L)
                            putJsonArray("contentParts") { add(textPart("Hi")) }
                        },
                    )
                )
            ),
            assistantId = assistantId,
            providers = emptyList(),
        )

        assertNull(result.conversation)
    }

    @Test
    fun `session with no parseable messages returns null conversation`() {
        val result = ChatboxImporter.parseSession(
            session(
                messages = JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("timestamp", 1700000000000L)
                            putJsonArray("contentParts") {
                                add(buildJsonObject { put("type", "image") })
                            }
                        },
                    )
                )
            ),
            assistantId = assistantId,
            providers = emptyList(),
        )

        assertNull(result.conversation)
        assertEquals(1, result.skippedImageParts)
    }

    @Test
    fun `timestamps drive createAt and updateAt`() {
        val result = ChatboxImporter.parseSession(
            session(
                messages = JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("timestamp", 1000L)
                            putJsonArray("contentParts") { add(textPart("First")) }
                        },
                        buildJsonObject {
                            put("role", "assistant")
                            put("timestamp", 9000L)
                            putJsonArray("contentParts") { add(textPart("Last")) }
                        },
                    )
                )
            ),
            assistantId = assistantId,
            providers = emptyList(),
        )

        val conversation: Conversation = result.conversation!!
        assertEquals(1000L, conversation.createAt.toEpochMilli())
        assertEquals(9000L, conversation.updateAt.toEpochMilli())
    }

    @Test
    fun `threadName takes precedence over name for title`() {
        val result = ChatboxImporter.parseSession(
            session(
                name = "Old Name",
                threadName = "New Thread Name",
                messages = JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("timestamp", 1700000000000L)
                            putJsonArray("contentParts") { add(textPart("Hi")) }
                        },
                    )
                )
            ),
            assistantId = assistantId,
            providers = emptyList(),
        )

        assertEquals("New Thread Name", result.conversation!!.title)
    }

    @Test
    fun `blank threadName falls back to name`() {
        val result = ChatboxImporter.parseSession(
            session(
                name = "Fallback Name",
                threadName = "",
                messages = JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("timestamp", 1700000000000L)
                            putJsonArray("contentParts") { add(textPart("Hi")) }
                        },
                    )
                )
            ),
            assistantId = assistantId,
            providers = emptyList(),
        )

        assertEquals("Fallback Name", result.conversation!!.title)
    }

    @Test
    fun `importProviders extracts openai claude gemini`() {
        val root = buildJsonObject {
            putJsonObject("settings") {
                putJsonObject("providers") {
                    putJsonObject("openai") {
                        put("apiHost", "https://api.openai.com")
                        put("apiKey", "sk-test")
                        putJsonArray("models") {
                            add(buildJsonObject {
                                put("modelId", "gpt-4o")
                                putJsonArray("capabilities") {
                                    add(JsonPrimitive("vision"))
                                    add(JsonPrimitive("tool_use"))
                                }
                            })
                        }
                    }
                    putJsonObject("claude") {
                        put("apiHost", "https://api.anthropic.com")
                        put("apiKey", "sk-ant-test")
                    }
                    putJsonObject("gemini") {
                        put("apiHost", "https://generativelanguage.googleapis.com")
                        put("apiKey", "gem-test")
                    }
                }
            }
        }

        val providers = ChatboxImporter.importProviders(root)
        assertEquals(3, providers.size)
        assertTrue(providers.any { it.name == "OpenAI" })
        assertTrue(providers.any { it.name == "Claude" })
        assertTrue(providers.any { it.name == "Gemini" })
        // OpenAI 模型能力映射
        val openai = providers.first { it.name == "OpenAI" }
        assertEquals(1, openai.models.size)
        assertTrue(ModelAbility.TOOL in openai.models[0].abilities)
        assertTrue(ModelAbility.REASONING !in openai.models[0].abilities)
    }

    @Test
    fun `importProviders skips provider without apiKey`() {
        val root = buildJsonObject {
            putJsonObject("settings") {
                putJsonObject("providers") {
                    putJsonObject("openai") {
                        put("apiHost", "https://api.openai.com")
                        put("apiKey", "")
                    }
                }
            }
        }

        val providers = ChatboxImporter.importProviders(root)
        assertEquals(0, providers.size)
    }
}
