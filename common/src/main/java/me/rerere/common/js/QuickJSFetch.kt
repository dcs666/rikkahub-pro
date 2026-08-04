package me.rerere.common.js

import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class HttpResponseDto(
    val status: Int,
    val ok: Boolean,
    val statusText: String,
    val body: String,
)

// fetch() returns a Response object synchronously (not a Promise)
// because this QuickJS wrapper doesn't support microtask scheduling.
private const val FETCH_POLYFILL = """
globalThis.fetch = function(url, options) {
    options = options || {};
    var method = (options.method || 'GET').toUpperCase();
    var headers = options.headers ? JSON.stringify(options.headers) : null;
    var body = options.body;
    if (typeof body === 'object' && body !== null) {
        body = JSON.stringify(body);
    } else if (typeof body !== 'string') {
        body = null;
    }

    var raw = __httpRequest(url, method, headers, body);
    var data = JSON.parse(raw);
    return {
        status: data.status,
        ok: data.ok,
        statusText: data.statusText,
        url: url,
        _body: data.body,
        text: function() { return this._body; },
        json: function() { return JSON.parse(this._body); }
    };
};
"""

fun QuickJSContext.injectFetch(httpClient: OkHttpClient) {
    globalObject.setProperty("__httpRequest", JSCallFunction { args ->
        val url = args[0] as? String ?: error("url is required")
        val method = (args[1] as? String ?: "GET").uppercase()
        val headersJson = args[2] as? String
        val body = args[3] as? String

        val requestBuilder = Request.Builder().url(url)

        val parsedHeaders = if (!headersJson.isNullOrBlank() && headersJson != "null") {
            json.parseToJsonElement(headersJson).jsonObject
        } else null

        parsedHeaders?.entries?.forEach { (key, value) ->
            requestBuilder.addHeader(key, value.jsonPrimitive.content)
        }

        val contentType = try {
            parsedHeaders?.get("Content-Type")?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }

        val mediaType = (contentType ?: "application/json").toMediaType()
        when (method) {
            "GET" -> requestBuilder.get()
            "HEAD" -> requestBuilder.head()
            else -> {
                val reqBody = body?.toRequestBody(mediaType)
                    ?: if (method in setOf("POST", "PUT", "PATCH")) {
                        "".toRequestBody(mediaType)
                    } else {
                        null
                    }
                requestBuilder.method(method, reqBody)
            }
        }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        // [FIX] 响应体限量读取：JS 脚本 fetch 任意 URL，不限制时一个超大响应会把
        // 整个响应读进内存再塞给 QuickJS（OOM 崩溃面）。5MB 上限覆盖正常抓取场景。
        val maxResponseBytes = 5L * 1024 * 1024
        val responseBody = try {
            val source = response.body.source()
            val buffer = okio.Buffer()
            val read = source.read(buffer, maxResponseBytes + 1)
            if (read > maxResponseBytes) {
                error("Response too large (>5MB) for JS fetch")
            }
            buffer.readUtf8()
        } catch (e: okio.IOException) {
            error("Failed to read response: ${e.message}")
        }
        val code = response.code
        val message = response.message
        response.close()

        json.encodeToString(
            HttpResponseDto(
                status = code,
                ok = code in 200..299,
                statusText = message,
                body = responseBody,
            )
        )
    })

    evaluate(FETCH_POLYFILL)
}
