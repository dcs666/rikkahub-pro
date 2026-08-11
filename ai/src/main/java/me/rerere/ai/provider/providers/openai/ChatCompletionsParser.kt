package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import kotlin.time.Clock

/**
 * [拆分] ChatCompletionsAPI 的响应解析域：将 chat/completions 的 message / usage
 * JSON 解析为 UIMessage / TokenUsage。纯函数，从 ChatCompletionsAPI.kt 提取。
 */

internal fun parseMessage(jsonObject: JsonObject): UIMessage {
    val role = MessageRole.valueOf(
        jsonObject["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
    )

    // content 可能是字符串（OpenAI 标准）或 part 数组（OpenAI 新格式/Mistral/MiniMax/
    // 部分网关归一化输出），数组时正文与思维链都从 part 中提取，否则正文会整段丢失
    val contentElement = jsonObject["content"]
    val contentArray = contentElement as? JsonArray

    // 数组格式中的正文：text / output_text / input_text 元素按序拼接
    val content = contentElement?.jsonPrimitiveOrNull?.contentOrNull
        ?: contentArray?.mapNotNull { part ->
            val partObj = part.jsonObjectOrNull ?: return@mapNotNull null
            when (partObj["type"]?.jsonPrimitive?.contentOrNull) {
                "text", "output_text", "input_text" ->
                    partObj["text"]?.jsonPrimitiveOrNull?.contentOrNull
                else -> null
            }
        }?.filterNotNull()?.joinToString("")
        ?: ""

    // 数组格式中的思维链：thinking / reasoning part（元素内部仍可能是 text 数组）
    val reasoning = jsonObject["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: jsonObject["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: contentArray?.mapNotNull { part ->
            val partObj = part.jsonObjectOrNull ?: return@mapNotNull null
            when (partObj["type"]?.jsonPrimitive?.contentOrNull) {
                // Mistral接口
                // {"id":"","object":"chat.completion.chunk","created":1772351733,"model":"magistral-medium-2509","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"好的"}]}]},"finish_reason":null}]}
                "thinking" -> partObj["thinking"]?.jsonArrayOrNull?.mapNotNull { t ->
                    t.jsonObjectOrNull?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
                }?.filterNotNull()?.joinToString("")

                "reasoning" -> partObj["reasoning"]?.jsonArrayOrNull?.mapNotNull { t ->
                    t.jsonObjectOrNull?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
                }?.filterNotNull()?.joinToString("")

                else -> null
            }
        }?.filterNotNull()?.joinToString("")
    val toolCalls = jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
    val images = jsonObject["images"] as? JsonArray ?: JsonArray(emptyList())

    return UIMessage(
        role = role,
        parts = buildList {
            if (!reasoning.isNullOrEmpty()) {
                add(
                    UIMessagePart.Reasoning(
                        reasoning = reasoning,
                        createdAt = Clock.System.now(),
                        finishedAt = null
                    )
                )
            }
            toolCalls.forEach { toolCalls ->
                val type = toolCalls.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                if (!type.isNullOrEmpty() && type != "function") error("tool call type not supported: $type")
                val toolCallId = toolCalls.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                val toolName =
                    toolCalls.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                val arguments =
                    toolCalls.jsonObject["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull
                add(
                    UIMessagePart.Tool(
                        toolCallId = toolCallId ?: "",
                        toolName = toolName ?: "",
                        input = arguments ?: "",
                        output = emptyList()
                    )
                )
            }
            if (content.isNotEmpty()) add(UIMessagePart.Text(content))
            images.forEach { image ->
                val imageObject = image.jsonObjectOrNull ?: return@forEach
                val type = imageObject["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                if (type != "image_url") return@forEach
                val url = imageObject["image_url"]?.jsonObjectOrNull?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                require(url.startsWith("data:image")) { "Only data uri is supported" }
                // 保留完整 data URI（勿用 substringAfter 剥前缀）：Coil 渲染与 encodeBase64
                // 的 data: 分支均按完整 data URI 处理；剥前缀后 png 显示失败、回传时
                // encodeBase64 抛 Unsupported URL format 降级为错误文本
                add(UIMessagePart.Image(url))
            }
        },
        annotations = parseAnnotations(
            jsonArray = jsonObject["annotations"]?.jsonArrayOrNull ?: JsonArray(
                emptyList()
            )
        ),
    )
}

internal fun parseAnnotations(jsonArray: JsonArray): List<UIMessageAnnotation> {
    return jsonArray.map { element ->
        val type =
            element.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: error("type is null")
        when (type) {
            "url_citation" -> {
                UIMessageAnnotation.UrlCitation(
                    title = element.jsonObject["url_citation"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
                        ?: "",
                    url = element.jsonObject["url_citation"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                        ?: "",
                )
            }

            else -> error("unknown annotation type: $type")
        }
    }
}

internal fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
    if (jsonObject == null) return null
    return TokenUsage(
        promptTokens = jsonObject["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        completionTokens = jsonObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        // 各 provider 汇报缓存命中的字段形状不统一，按方言兜底解析（#1576）：
        // OpenAI 嵌套 -> Moonshot 顶层 cached_tokens -> DeepSeek prompt_cache_hit_tokens
        cachedTokens = jsonObject["prompt_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
            ?: jsonObject["cached_tokens"]?.jsonPrimitive?.intOrNull
            ?: jsonObject["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
            ?: 0,
        // DeepSeek 在 usage.completion_tokens_details.reasoning_tokens 汇报思维链 token 数
        reasoningTokens = jsonObject["completion_tokens_details"]?.jsonObjectOrNull?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull
            ?: 0
    )
}

