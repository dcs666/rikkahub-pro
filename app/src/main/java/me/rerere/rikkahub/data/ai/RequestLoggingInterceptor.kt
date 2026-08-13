package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toMap()
        val requestBody = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        }

        // Parse model / effort / stream from the request body, and detect purpose
        val bodyInfo = parseBodyInfo(requestBody)
        val purpose = detectPurpose(requestBody, bodyInfo.stream)

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.toString(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error,
                    model = bodyInfo.model,
                    effort = bodyInfo.effort,
                    stream = bodyInfo.stream,
                    purpose = purpose
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toMap()

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = request.url.toString(),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error,
                model = bodyInfo.model,
                effort = bodyInfo.effort,
                stream = bodyInfo.stream,
                purpose = purpose
            )
        )

        return response
    }

    /**
     * Lightweight parse of the request body: extract the model name, reasoning effort
     * (OpenAI `reasoning_effort` or Anthropic `thinking.effort` / `thinking.type`) and
     * whether the request is streaming.
     */
    private fun parseBodyInfo(body: String?): BodyInfo {
        if (body.isNullOrBlank()) return BodyInfo(null, null, null)
        return runCatching {
            val root = Json.parseToJsonElement(body).jsonObject
            val model = root["model"]?.jsonPrimitive?.contentOrNull
            val stream = root["stream"]?.jsonPrimitive?.booleanOrNull
            var effort: String? = root["reasoning_effort"]?.jsonPrimitive?.contentOrNull
            // [FIX] Responses API（OpenAI /responses 端点，opencode.ai/zen/go 网关走此路径）
            // 的思考参数是嵌套结构 { "reasoning": { "effort": "low|high|max" } }，
            // 此前只解析顶层 reasoning_effort（chat/completions 格式）→ 日志永远看不到 effort。
            if (effort == null) {
                effort = root["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.contentOrNull
            }
            root["thinking"]?.jsonObject?.let { thinking ->
                if (effort == null) {
                    effort = thinking["effort"]?.jsonPrimitive?.contentOrNull
                }
                if (effort == null) {
                    thinking["type"]?.jsonPrimitive?.contentOrNull?.let { effort = "thinking:$it" }
                }
            }
            BodyInfo(model, effort, stream)
        }.getOrElse { BodyInfo(null, null, null) }
    }

    /**
     * Detect the purpose of the request from the prompt embedded in the body.
     * Known markers: reply suggestions and title generation prompts.
     * Anything streaming is treated as the main chat conversation.
     */
    private fun detectPurpose(body: String?, stream: Boolean?): String? {
        if (body.isNullOrBlank()) return null
        return when {
            body.contains("act as the **User**") -> "suggestion"
            body.contains("summarize the conversation") -> "title"
            stream == true -> "chat"
            else -> "other"
        }
    }

    private data class BodyInfo(
        val model: String?,
        val effort: String?,
        val stream: Boolean?
    )

    // [SECURITY] 敏感 header 脱敏：Authorization/x-api-key/cookie 等不得进入内存日志
    // （日志页明文展示 requestHeaders，API key 会直接泄露在日志详情里）
    private val SENSITIVE_HEADERS = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "api-key",
        "x-auth-token",
        "x-access-token",
        "x-auth",
        "token",
    )

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { name ->
            val value = get(name) ?: ""
            if (name.lowercase() in SENSITIVE_HEADERS) "***" else value
        }
    }
}
