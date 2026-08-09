package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.encodeBase64
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChatCompletionsAPI.parseMessage content handling:
 * - 字符串 content（OpenAI 标准）保持原行为
 * - content part 数组（OpenAI 新格式 / Mistral / MiniMax / 部分网关归一化输出）：
 *   text/output_text 元素按序拼接为正文，thinking/reasoning 元素提取为思维链
 * - 修复前数组格式正文被整体丢弃，导致回答"少一部分/缺字"
 */
class ChatCompletionsAPIParseMessageTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    // Helper to invoke private parseMessage via reflection
    private fun parseMessage(jsonObject: JsonObject): UIMessage {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "parseMessage",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(api, jsonObject) as UIMessage
    }

    private fun textOf(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    private fun reasoningOf(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Reasoning>().joinToString("") { it.reasoning }

    @Test
    fun `string content keeps original behavior`() {
        val message = parseMessage(buildJsonObject {
            put("content", "hello world")
            put("reasoning_content", "thinking...")
        })
        assertEquals("hello world", textOf(message))
        assertEquals("thinking...", reasoningOf(message))
    }

    @Test
    fun `content array text parts are joined`() {
        val message = parseMessage(buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "Hello")
                })
                add(buildJsonObject {
                    put("type", "text")
                    put("text", " World")
                })
            }
        })
        assertEquals("Hello World", textOf(message))
    }

    @Test
    fun `content array output_text parts are joined`() {
        val message = parseMessage(buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "output_text")
                    put("text", "答")
                })
                add(buildJsonObject {
                    put("type", "output_text")
                    put("text", "案")
                })
            }
        })
        assertEquals("答案", textOf(message))
    }

    @Test
    fun `content array thinking extracted as reasoning`() {
        val message = parseMessage(buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "thinking")
                    putJsonArray("thinking") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "让我想想")
                        })
                    }
                })
            }
        })
        assertEquals("让我想想", reasoningOf(message))
        assertTrue(textOf(message).isEmpty())
    }

    @Test
    fun `content array mixed thinking and text parts`() {
        val message = parseMessage(buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "thinking")
                    putJsonArray("thinking") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "思考1")
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "思考2")
                        })
                    }
                })
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "最终答案")
                })
            }
        })
        assertEquals("思考1思考2", reasoningOf(message))
        assertEquals("最终答案", textOf(message))
    }

    @Test
    fun `content array reasoning type extracted`() {
        val message = parseMessage(buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "reasoning")
                    putJsonArray("reasoning") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "R1")
                        })
                    }
                })
            }
        })
        assertEquals("R1", reasoningOf(message))
    }

    @Test
    fun `content array empty and unknown types are ignored`() {
        val message = parseMessage(buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "refusal")
                    put("text", "refused")
                })
            }
        })
        assertTrue(textOf(message).isEmpty())
        assertTrue(reasoningOf(message).isEmpty())
    }

    @Test
    fun `images keep full data uri prefix for any mime`() {
        // 修复前 substringAfter("data:image/png;base64,") 剥掉前缀：
        // png 存成无前缀 base64 → Coil 无法渲染、encodeBase64 回传时抛
        // Unsupported URL format 降级；jpeg/webp 因找不到分隔符反而保留原串。
        // 修复后统一保留完整 data URI（Coil 与 encodeBase64 的 data: 分支均支持）。
        val png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
        val jpeg = "data:image/jpeg;base64,/9j/4AAQSkZJRg=="

        val message = parseMessage(buildJsonObject {
            putJsonArray("images") {
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", png)
                    })
                })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", jpeg)
                    })
                })
            }
        })

        val images = message.parts.filterIsInstance<UIMessagePart.Image>()
        assertEquals(2, images.size)
        assertEquals(png, images[0].url)
        assertEquals(jpeg, images[1].url)
        // 回传链路：data URI 能被 encodeBase64 的 data: 分支原样接受
        assertTrue(images[0].encodeBase64().isSuccess)
        assertTrue(images[1].encodeBase64().isSuccess)
    }
}
