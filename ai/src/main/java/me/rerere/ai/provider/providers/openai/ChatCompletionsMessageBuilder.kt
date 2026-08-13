package me.rerere.ai.provider.providers.openai
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.buildEndpoint
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.isUiNotice
import me.rerere.ai.ui.metadataAs

/**
 * [拆分] ChatCompletionsAPI 的消息构建域：将 UIMessage 组装为 chat/completions
 * 的 messages 数组（assistant reasoning/content/tool_calls 回传、多模态 content）。
 * 纯扩展函数，从 ChatCompletionsAPI.kt 提取。
 */

internal fun JsonArrayBuilder.addAssistantMessages(
    message: UIMessage,
    includeReasoning: Boolean,
    supportInputModalities: List<Modality>,
    forcePlaceholderReasoning: Boolean = false,
) {
    val groups = groupPartsByToolBoundary(message.parts)
    val contentBuffer = mutableListOf<UIMessagePart>()
    var reasoningPart: UIMessagePart.Reasoning? = null

    // DeepSeek 思考模式文档：携带 tools 参数的请求，在后续所有请求中必须完整回传
    // reasoning_content，否则 API 返回 400。因此只要该 assistant 消息含工具调用
    // （Tool part，含已执行的工具输出），无论用户是否关闭 includeHistoryReasoning
    // 都必须回传其思维链。
    val forceIncludeReasoning = message.parts.any { it is UIMessagePart.Tool }

    for (group in groups) {
        when (group) {
            is PartGroup.Content -> {
                // 从当前 group 中提取 reasoning（保持顺序）
                if (includeReasoning || forceIncludeReasoning) {
                    group.parts.filterIsInstance<UIMessagePart.Reasoning>().firstOrNull()?.let {
                        reasoningPart = it
                    }
                }
                group.parts
                    .filter { (it is UIMessagePart.Text || it is UIMessagePart.Image) && !it.isUiNotice }
                    .forEach { contentBuffer.add(it) }
            }

            is PartGroup.Tools -> {
                // 输出 assistant 消息（包含累积的内容 + tool_calls）
                buildAssistantMessageJson(
                    contentParts = contentBuffer,
                    tools = group.tools,
                    reasoningPart = reasoningPart,
                    forcePlaceholderReasoning = forcePlaceholderReasoning,
                )?.let { assistantMessage ->
                    add(assistantMessage)
                }
                contentBuffer.clear()
                reasoningPart = null // 清空，下一个 group 可能有新的 reasoning

                // 紧跟 tool 结果消息
                group.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("role", "tool")
                        put("name", tool.toolName)
                        put("tool_call_id", tool.toolCallId)
                        put("content", tool.toToolResultContent(supportInputModalities))
                    })
                }
            }
        }
    }

    // 输出剩余内容
    if (contentBuffer.isNotEmpty() || reasoningPart != null) {
        buildAssistantMessageJson(
            contentParts = contentBuffer,
            tools = emptyList(),
            reasoningPart = reasoningPart,
            forcePlaceholderReasoning = forcePlaceholderReasoning,
        )?.let { assistantMessage ->
            add(assistantMessage)
        }
    }
}

internal fun buildAssistantMessageJson(
    contentParts: List<UIMessagePart>,
    tools: List<UIMessagePart.Tool>,
    reasoningPart: UIMessagePart.Reasoning?,
    forcePlaceholderReasoning: Boolean = false,
): JsonObject? {
    val hasUsableContent = contentParts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image -> part.url.isNotBlank()
            else -> false
        }
    }
    val hasReasoning = !reasoningPart?.reasoning.isNullOrBlank()
    if (!hasUsableContent && !hasReasoning && tools.isEmpty()) {
        return null
    }

    return buildJsonObject {
        put("role", "assistant")

        // reasoning_content
        if (hasReasoning) {
            put("reasoning_content", reasoningPart.reasoning)
        } else if (forcePlaceholderReasoning && tools.isNotEmpty()) {
            // Console Go 网关（opencode.ai）thinking mode：带工具调用的 assistant 消息
            // 必须携带 reasoning_content（实测空字符串可接受）。历史消息未捕获思维链时
            // 补空字符串占位，否则网关 400（错误：The reasoning_content in the thinking
            // mode must be passed back to the API）
            put("reasoning_content", "")
        }

        // content
        if (contentParts.isEmpty()) {
            put("content", "")
        } else if (contentParts.size == 1 && contentParts[0] is UIMessagePart.Text) {
            put("content", (contentParts[0] as UIMessagePart.Text).text)
        } else {
            putJsonArray("content") {
                contentParts.forEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", part.text)
                            })
                        }

                        is UIMessagePart.Image -> {
                            add(buildJsonObject {
                                part.encodeBase64().onSuccess { encodedImage ->
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", encodedImage.base64)
                                    })
                                }.onFailure {
                                    it.printStackTrace()
                                    put("type", "text")
                                    put("text", "")
                                }
                            })
                        }

                        else -> {}
                    }
                }
            }
        }

        // tool_calls
        if (tools.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("id", tool.toolCallId)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.toolName)
                            // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
                            put("arguments", tool.inputAsJson().toString())
                        })
                    })
                }
            })
        }
    }
}

internal fun JsonArrayBuilder.addNonAssistantMessage(message: UIMessage) {
    add(buildJsonObject {
        put("role", JsonPrimitive(message.role.name.lowercase()))

        if (message.parts.isOnlyTextPart()) {
            put("content", message.parts.filterIsInstance<UIMessagePart.Text>().first().text)
        } else {
            putJsonArray("content") {
                message.parts.forEach { part ->
                    if (part.isUiNotice) return@forEach
                    when (part) {
                        is UIMessagePart.Text -> {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", part.text)
                            })
                        }

                        is UIMessagePart.Image -> {
                            add(buildJsonObject {
                                part.encodeBase64().onSuccess { encodedImage ->
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", encodedImage.base64)
                                    })
                                }.onFailure {
                                    it.printStackTrace()
                                    put("type", "text")
                                    put("text", "")
                                }
                            })
                        }

                        else -> {}
                    }
                }
            }
        }
    })
}

internal fun UIMessagePart.Tool.toToolResultContent(supportInputModalities: List<Modality>): JsonElement {
    // 只考虑文字和图片;只有模型支持图片输入时,图片才作为多模态内容回传,否则以文本占位,避免发给不支持的模型报错
    val supportsImageInput = Modality.IMAGE in supportInputModalities
    val hasImageToSend = output.any { it is UIMessagePart.Image && supportsImageInput }
    return if (!hasImageToSend) {
        val text = output.mapNotNull { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                is UIMessagePart.Image -> "[Image output omitted: current model does not support image input]"
                else -> null
            }
        }.joinToString("\n")
        // [FIX] DeepSeek 等部分提供商不接受空的 tool_result content，
        // 空输出时给一个占位文本避免 API 400。
        JsonPrimitive(text.ifBlank { "[Tool returned no output]" })
    } else {
        buildJsonArray {
            output.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        if (part.text.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", part.text)
                            })
                        }
                    }

                    is UIMessagePart.Image -> {
                        add(buildJsonObject {
                            part.encodeBase64().onSuccess { encodedImage ->
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", encodedImage.base64)
                                })
                            }.onFailure {
                                Log.w(CHAT_COMPLETIONS_TAG, "encode tool result image failed: ${part.url}", it)
                                put("type", "text")
                                put("text", "Error: Failed to encode image to base64")
                            }
                        })
                    }

                    else -> {}
                }
            }
        }
    }
}

