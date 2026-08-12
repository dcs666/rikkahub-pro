package me.rerere.ai.provider.providers

import android.content.Context
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
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

// [拆分] Claude 请求体构建域（拆自 ClaudeProvider.kt，Strangler Fig）
private const val TAG = "ClaudeRequestBodyBuilder"

internal fun buildMessageRequest(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        return buildJsonObject {
            put("model", params.model.modelId)
            put(
                "messages",
                buildMessages(messages, providerSetting.promptCaching, providerSetting.promptCacheTtl)
            )
            put("max_tokens", params.maxTokens ?: 64_000)

            // 顶层 cache_control: 让 Anthropic 自动管理缓存断点
            if (providerSetting.promptCaching) {
                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
            }

            if (params.temperature != null && !params.reasoningLevel.isEnabled) put(
                "temperature",
                params.temperature
            )
            if (params.topP != null) put("top_p", params.topP)

            put("stream", stream)

            // system prompt
            val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
            val systemTextParts = systemMessage?.parts?.filterIsInstance<UIMessagePart.Text>().orEmpty()
            if (systemTextParts.isNotEmpty()) {
                put("system", buildJsonArray {
                    systemTextParts.forEachIndexed { index, part ->
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                            if (providerSetting.promptCaching && index == systemTextParts.lastIndex) {
                                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
                            }
                        })
                    }
                })
            }

            // 处理 thinking
            // Anthropic 新 API: adaptive 模式 + output_config.effort 控制强度
            // 旧的 type=enabled + budget_tokens 在 Opus 4.7+ 上已不支持
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val host = providerSetting.baseUrl.toHttpUrl().host
                if (host == "api.deepseek.com") {
                    // DeepSeek Anthropic 兼容层（base_url=api.deepseek.com/anthropic）：
                    // 官方文档支持 thinking 开关（budget_tokens 被忽略）与 output_config.effort
                    // （仅 none/low/high/max）。App 的 XHIGH("xhigh")/MEDIUM("medium") 需映射到
                    // 官方枚举，否则思考强度控制静默失效；adaptive/display 为 Anthropic 新格式，
                    // 兼容层未声明支持，此处用老格式 type=enabled/disabled。
                    when (params.reasoningLevel) {
                        ReasoningLevel.OFF -> {
                            put("thinking", buildJsonObject { put("type", "disabled") })
                        }

                        ReasoningLevel.AUTO -> {
                            put("thinking", buildJsonObject { put("type", "enabled") })
                        }

                        else -> {
                            put("thinking", buildJsonObject { put("type", "enabled") })
                            put("output_config", buildJsonObject {
                                put(
                                    "effort",
                                    when (params.reasoningLevel) {
                                        ReasoningLevel.XHIGH -> "max"
                                        ReasoningLevel.MEDIUM -> "high"
                                        else -> params.reasoningLevel.effort // low/high
                                    }
                                )
                            })
                        }
                    }
                } else {
                    when (params.reasoningLevel) {
                        ReasoningLevel.OFF -> {
                            put("thinking", buildJsonObject { put("type", "disabled") })
                        }

                        ReasoningLevel.AUTO -> {
                            put("thinking", buildJsonObject {
                                put("type", "adaptive")
                                put("display", "summarized")
                            })
                        }

                        else -> {
                            put("thinking", buildJsonObject {
                                put("type", "adaptive")
                                put("display", "summarized")
                            })
                            put("output_config", buildJsonObject {
                                put("effort", params.reasoningLevel.effort)
                            })
                        }
                    }
                }
            }

            // 处理工具
            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEachIndexed { index, tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", json.encodeToJsonElement(tool.parameters()))
                            if (providerSetting.promptCaching && index == params.tools.lastIndex) {
                                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
                            }
                        })
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

internal fun cacheControlEphemeral(promptCacheTtl: ClaudePromptCacheTtl) = buildJsonObject {
        put("type", "ephemeral")
        promptCacheTtl.apiValue?.let { put("ttl", it) }
    }

internal fun buildMessages(
        messages: List<UIMessage>,
        promptCaching: Boolean,
        promptCacheTtl: ClaudePromptCacheTtl
    ) = buildJsonArray {
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantMessage(message)
                } else {
                    addClaudeUserMessage(message)
                }
            }
    }.let { messagesArray ->
        if (!promptCaching) return@let messagesArray
        insertMessagesCacheControl(messagesArray, promptCacheTtl)
    }

    /**
     * 在倒数第二条非 tool_result 的 user message 的最后一个 content block 上插入 cache_control
     */
internal fun insertMessagesCacheControl(
        messages: JsonArray,
        promptCacheTtl: ClaudePromptCacheTtl
    ): JsonArray {
        // 找出所有非 tool_result 的 user message 的索引
        val realUserIndices = messages.mapIndexedNotNull { index, msg ->
            val obj = msg.jsonObject
            if (obj["role"]?.jsonPrimitive?.contentOrNull == "user") {
                val content = obj["content"]?.jsonArray
                val isToolResult = content?.any {
                    it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_result"
                } == true
                if (!isToolResult) index else null
            } else null
        }

        // 取倒数第二条
        val targetIndex = if (realUserIndices.size >= 2) {
            realUserIndices[realUserIndices.size - 2]
        } else return messages

        // 在目标 message 的最后一个 content block 上添加 cache_control
        return JsonArray(messages.mapIndexed { index, msg ->
            if (index == targetIndex) {
                val obj = msg.jsonObject
                val content = obj["content"]?.jsonArray ?: return@mapIndexed msg
                val newContent = JsonArray(content.mapIndexed { contentIndex, block ->
                    if (contentIndex == content.lastIndex) {
                        JsonObject(
                            block.jsonObject + mapOf("cache_control" to cacheControlEphemeral(promptCacheTtl))
                        )
                    } else block
                })
                JsonObject(obj + mapOf("content" to newContent))
            } else msg
        })
    }

internal fun JsonArrayBuilder.addAssistantMessage(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.mapNotNull { it.toContentBlock() }.forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 tool_use 到内容缓冲
                    group.tools.forEach { contentBuffer.add(it.toToolUseBlock()) }

                    // 输出 assistant 消息
                    add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") { contentBuffer.forEach { add(it) } }
                    })
                    contentBuffer.clear()

                    // 紧跟 tool_result
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            group.tools.forEach { add(it.toToolResultBlock()) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") { contentBuffer.forEach { add(it) } }
            })
        }
    }

internal fun JsonArrayBuilder.addClaudeUserMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", message.role.name.lowercase())
            putJsonArray("content") {
                message.parts.mapNotNull { it.toContentBlock() }.forEach { add(it) }
            }
        })
    }

internal fun UIMessagePart.toContentBlock(): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        is UIMessagePart.Image -> buildJsonObject {
            encodeBase64(withPrefix = false).onSuccess { encoded ->
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", encoded.mimeType)
                    put("data", encoded.base64)
                })
            }.onFailure {
                Log.w(TAG, "encode image failed: $url", it)
                put("type", "text")
                put("text", "")
            }
        }

        is UIMessagePart.Reasoning -> buildJsonObject {
            put("type", "thinking")
            put("thinking", reasoning)
            metadataAs<ClaudeReasoningMetadata>()?.signature?.let { put("signature", it) }
        }

        else -> null
    }

internal fun UIMessagePart.Tool.toToolUseBlock() = buildJsonObject {
        put("type", "tool_use")
        put("id", toolCallId)
        put("name", toolName)
        put("input", inputAsJson())
    }

internal fun UIMessagePart.Tool.toToolResultBlock() = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", toolCallId)
        val contentBlocks = output.mapNotNull { it.toContentBlock() }
        if (contentBlocks.isEmpty()) {
            // [FIX] Anthropic API 不接受空 content 数组，空输出给占位文本
            put("content", "[Tool returned no output]")
        } else {
            putJsonArray("content") {
                contentBlocks.forEach { add(it) }
            }
        }
    }
