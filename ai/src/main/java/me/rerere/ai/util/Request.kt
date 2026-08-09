package me.rerere.ai.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.internal.http.RealResponseBody

fun List<CustomHeader>.toHeaders(): Headers {
    return Headers.Builder().apply {
        this@toHeaders
            .filter { it.name.isNotBlank() }
            .forEach {
                add(it.name, it.value)
            }
    }.build()
}

/**
 * 拼接 API 端点 URL。若 baseUrl 已以 path 结尾（用户把完整端点填进了 base_url，
 * 如 https://opencode.ai/zen/go/v1/responses），不再重复拼接，避免出现
 * /responses/responses 或 /chat/completions/chat/completions 这类 404。
 */
fun buildEndpoint(baseUrl: String, path: String): String {
    val base = baseUrl.trimEnd('/')
    val p = path.trim('/')
    if (p.isEmpty()) return base
    return if (base.endsWith("/$p")) base else "$base/$p"
}

fun Request.Builder.configureReferHeaders(url: String): Request.Builder {
    val httpUrl = url.toHttpUrl()
    return when (httpUrl.host) {
        "aihubmix.com" -> {
            addHeader("APP-Code", "DKHA9468")
        }

        "openrouter.ai" -> {
            this
                .addHeader("X-Title", "RikkaHub")
                .addHeader("HTTP-Referer", "https://rikka-ai.com")
        }

        else -> this
    }
}

fun ResponseBody.stringSafe(): String? {
    return when (this) {
        is RealResponseBody -> string()
        else -> null
    }
}

fun JsonObject.mergeCustomBody(bodies: List<CustomBody>): JsonObject {
    if (bodies.isEmpty()) return this

    val content = toMutableMap()
    bodies.forEach { body ->
        if (body.key.isNotBlank()) {
            // 如果已存在相同键且两者都是JsonObject，则需要递归合并
            val existingValue = content[body.key]
            val newValue = body.value

            if (existingValue is JsonObject && newValue is JsonObject) {
                // 递归合并两个JsonObject
                content[body.key] = mergeJsonObjects(existingValue, newValue, depth = 0)
            } else {
                // 直接替换或添加
                content[body.key] = newValue
            }
        }
    }
    return JsonObject(content)
}

/**
 * 递归合并两个JsonObject
 * [FIX] 递归无深度上限：深嵌套 CustomBody 在请求构造时 SOE（Error 不 catch）→ 崩溃。
 * 深度超过上限直接返回 overlay（放弃合并深度，防御性截断）。
 */
private fun mergeJsonObjects(base: JsonObject, overlay: JsonObject, depth: Int): JsonObject {
    if (depth > MAX_JSON_MERGE_DEPTH) return overlay
    val result = base.toMutableMap()

    for ((key, value) in overlay) {
        val baseValue = result[key]

        result[key] = if (baseValue is JsonObject && value is JsonObject) {
            // 如果两者都是JsonObject，递归合并
            mergeJsonObjects(baseValue, value, depth + 1)
        } else {
            // 否则使用新值替换旧值
            value
        }
    }

    return JsonObject(result)
}

/**
 * 从 JsonElement 中移除或保留指定的键
 * @param keys 要操作的键列表
 * @param keepOnly 如果为 true，则只保留指定的键；如果为 false，则移除指定的键
 * @return 处理后的 JsonElement
 * [FIX] 递归无深度上限：模型响应（GoogleProvider 流式 chunk）深嵌套 → SOE 崩溃。
 * 深度超过上限原样返回（防御性截断，与 parseErrorDetail 深度防护一致）。
 */
fun JsonElement.removeElements(keys: List<String>, keepOnly: Boolean = false, depth: Int = 0): JsonElement {
    if (depth > MAX_JSON_MERGE_DEPTH) return this
    return when (this) {
        is JsonObject -> {
            val newContent = if (keepOnly) {
                // 只保留指定的键（且键存在）
                keys.mapNotNull { key ->
                    get(key)?.let { key to it }
                }.toMap()
            } else {
                // 移除指定的键
                toMap().filterKeys { key -> key !in keys }
            }

            // 递归处理嵌套的 JsonElement
            JsonObject(newContent.mapValues { (_, value) ->
                value.removeElements(keys, keepOnly, depth + 1)
            })
        }

        is JsonArray -> {
            JsonArray(map { it.removeElements(keys, keepOnly, depth + 1) })
        }

        else -> this // 基本类型直接返回
    }
}

private const val MAX_JSON_MERGE_DEPTH = 32
