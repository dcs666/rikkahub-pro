package me.rerere.ai.provider.providers.openai
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.common.android.Logging
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

/**
 * [拆分] ResponseAPI 的响应解析域：将 Responses API 的 SSE delta / 完整 output
 * JSON 解析为 MessageChunk（含思维链、工具调用、token 用量）。
 * 纯函数、无类状态，从 ResponseAPI.kt 提取以缩小主类（1411 → 1010 行）。
 */

internal fun parseResponseDelta(jsonObject: JsonObject): MessageChunk? {
    val chunkType = jsonObject["type"]?.jsonPrimitive?.content ?: error("chunk type not found")

    when (chunkType) {
        "response.output_text.delta" -> {
            return MessageChunk(
                id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                model = "",
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = UIMessage.assistant(
                            jsonObject["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                        ),
                        message = null,
                        finishReason = null
                    )
                )
            )
        }

        "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
            return MessageChunk(
                id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                model = "",
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(
                                UIMessagePart.Reasoning(
                                    reasoning = jsonObject["delta"]?.jsonPrimitive?.contentOrNull
                                        ?: "",
                                    createdAt = Clock.System.now(),
                                    finishedAt = null
                                )
                            )
                        ),
                        message = null,
                        finishReason = null
                    )
                )
            )
        }

        "response.output_item.added" -> {
            val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
            val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
            val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
            if (type == "function_call") {
                return MessageChunk(
                    id = id,
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            message = null,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Tool(
                                        toolCallId = id,
                                        toolName = item["name"]?.jsonPrimitive?.content ?: "",
                                        input = item["arguments"]?.jsonPrimitive?.content
                                            ?: "",
                                        output = emptyList()
                                    )
                                )
                            ),
                            finishReason = null
                        )
                    )
                )
            } else if (type == "image_generation_call") {
                return MessageChunk(
                    id = id,
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(UIMessagePart.Image(url = ""))
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            } else if (type == "reasoning") {
                val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.content
                return MessageChunk(
                    id = id,
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            message = null,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Reasoning(
                                        reasoning = "",
                                        createdAt = Clock.System.now(),
                                        finishedAt = null,
                                        metadata = OpenAIReasoningMetadata(
                                            reasoningId = id,
                                            encryptedContent = encryptedContent,
                                        ).toMetadata()
                                    )
                                )
                            ),
                            finishReason = null,
                        )
                    )
                )
            }
        }

        "response.output_item.done" -> {
            val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
            val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
            val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
            if (type == "reasoning") {
                val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.content
                return MessageChunk(
                    id = id,
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            message = null,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Reasoning(
                                        reasoning = "",
                                        createdAt = Clock.System.now(),
                                        finishedAt = null,
                                        metadata = OpenAIReasoningMetadata(
                                            reasoningId = id,
                                            encryptedContent = encryptedContent,
                                        ).toMetadata()
                                    )
                                )
                            ),
                            finishReason = null,
                        )
                    )
                )
            } else if (type == "image_generation_call") {
                val result = item["result"]?.jsonPrimitive?.content ?: error("result not found")
                return MessageChunk(
                    id = item["id"]?.jsonPrimitive?.content ?: error("item_id not found"),
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Image(url = result)
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }
        }

        "response.function_call_arguments.done" -> {
            val toolCallId =
                jsonObject["item_id"]?.jsonPrimitive?.content ?: error("item_id not found")
            val arguments =
                jsonObject["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
            return MessageChunk(
                id = toolCallId,
                model = "",
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(
                                UIMessagePart.Tool(
                                    toolCallId = toolCallId,
                                    toolName = "",
                                    input = arguments,
                                    output = emptyList()
                                )
                            )
                        ),
                        message = null,
                        finishReason = null
                    )
                ),
            )
        }

        "response.completed" -> {
            return MessageChunk(
                id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                model = "",
                choices = emptyList(),
                usage = parseTokenUsage(jsonObject["response"]?.jsonObject?.get("usage")?.jsonObject)
            )
        }

        "response.incomplete" -> {
            // DeepSeek / OpenAI 官方：响应被截断（如达到 max_output_tokens）时
            // 流的最后一个事件（文档：没有 data: [DONE]，以 completed/incomplete/failed
            // 结束）。携带 finishReason 让 #51 的截断提示生效。
            return MessageChunk(
                id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                model = "",
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = null,
                        message = null,
                        finishReason = "length"
                    )
                ),
                usage = parseTokenUsage(jsonObject["response"]?.jsonObject?.get("usage")?.jsonObject)
            )
        }

        "response.failed" -> {
            // DeepSeek / OpenAI 官方：响应失败时最后一个事件，携带含 error 详情的
            // response 对象。抛异常由 onEvent 的 try-catch 以 close(e) 结束 flow。
            val error = jsonObject["response"]?.jsonObject?.get("error")
                ?.parseErrorDetail() ?: RuntimeException("Response failed")
            throw error
        }
    }

    return null
}

internal fun parseResponseOutput(jsonObject: JsonObject): MessageChunk {
    val outputs = jsonObject["output"]?.jsonArray ?: error("output not found")
    val parts = arrayListOf<UIMessagePart>()

    // 非流式截断/失败：DeepSeek / OpenAI 官方在响应对象顶层返回 status
    // （completed / incomplete / failed）。incomplete 时携带 finishReason 让
    // #51 的截断提示生效；failed 时抛出携带的 error 详情。
    val status = jsonObject["status"]?.jsonPrimitive?.contentOrNull
    if (status == "failed") {
        val error = jsonObject["error"]?.parseErrorDetail()
            ?: RuntimeException("Response failed")
        throw error
    }
    val finishReason = if (status == "incomplete") "length" else null

    outputs.forEach { outputItem ->
        val output = outputItem.jsonObject
        val type = output["type"]?.jsonPrimitive?.content ?: error("output type not found")
        when (type) {
            "reasoning" -> {
                // 兼容三种网关的 reasoning item 格式（只认 summary 会导致非流式请求在
                // DeepSeek/OpenCode Zen 上抛 "summary not found" 崩溃）：
                // 1. OpenAI 官方：summary 数组（summary_text）
                // 2. DeepSeek：明文 content 字符串
                // 3. OpenCode Zen 网关：content 数组（reasoning_text）
                val summary = output["summary"]?.jsonArray
                if (summary != null) {
                    summary.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content
                            ?: error("part type not found")
                        when (partType) {
                            "summary_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content
                                    ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Reasoning(
                                        reasoning = text,
                                        createdAt = Clock.System.now(),
                                        finishedAt = Clock.System.now()
                                    )
                                )
                            }
                        }
                    }
                } else {
                    when (val content = output["content"]) {
                        is JsonPrimitive -> {
                            // DeepSeek Responses API：明文 content 字符串
                            content.contentOrNull?.takeIf { it.isNotBlank() }?.let { text ->
                                parts.add(
                                    UIMessagePart.Reasoning(
                                        reasoning = text,
                                        createdAt = Clock.System.now(),
                                        finishedAt = Clock.System.now()
                                    )
                                )
                            }
                        }

                        is JsonArray -> {
                            // OpenCode Zen 网关：content 数组（reasoning_text）
                            content.forEach { element ->
                                val partObj = element.jsonObjectOrNull ?: return@forEach
                                if (partObj["type"]?.jsonPrimitive?.contentOrNull ==
                                    "reasoning_text"
                                ) {
                                    partObj["text"]?.jsonPrimitiveOrNull?.contentOrNull
                                        ?.takeIf { it.isNotBlank() }?.let { text ->
                                            parts.add(
                                                UIMessagePart.Reasoning(
                                                    reasoning = text,
                                                    createdAt = Clock.System.now(),
                                                    finishedAt = Clock.System.now()
                                                )
                                            )
                                        }
                                }
                            }
                        }

                        else -> {}
                    }
                }
            }

            "function_call" -> {
                val callId = output["call_id"]?.jsonPrimitive?.content ?: error("call_id not found")
                val name = output["name"]?.jsonPrimitive?.content ?: error("name not found")
                val arguments =
                    output["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                parts.add(
                    UIMessagePart.Tool(
                        toolCallId = callId,
                        toolName = name,
                        input = arguments,
                        output = emptyList()
                    )
                )
            }

            "message" -> {
                val content = output["content"]?.jsonArray ?: error("content not found")
                content.map { it.jsonObject }.forEach { part ->
                    val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                    when (partType) {
                        "output_text" -> {
                            val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                            parts.add(
                                UIMessagePart.Text(
                                    text = text
                                )
                            )
                        }

                        else -> error("unknown part type $partType")
                    }
                }
            }
        }
    }

    return MessageChunk(
        id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "",
        model = jsonObject["model"]?.jsonPrimitive?.contentOrNull ?: "",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                message = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = parts,
                ),
                finishReason = finishReason,
                delta = null
            )
        ),
        usage = parseTokenUsage(jsonObject["usage"]?.jsonObject)
    )
}

private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
    if (jsonObject == null) return null
    return TokenUsage(
        promptTokens = jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        completionTokens = jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        cachedTokens = jsonObject["input_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
            ?: 0,
        // DeepSeek 等 provider 汇报思维链 token 数
        reasoningTokens = jsonObject["output_tokens_details"]?.jsonObjectOrNull?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull
            ?: 0
    )
}
