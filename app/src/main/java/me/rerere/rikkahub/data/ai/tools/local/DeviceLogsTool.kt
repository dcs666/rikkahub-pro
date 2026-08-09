package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 读取设备日志工具：
 * - App 内存日志（Logging 环形缓冲，最近 100 条）：文本日志 + HTTP 请求日志（含
 *   url/responseCode/duration/error/model/effort/stream——排查 provider 问题的一手信息）
 * - 可选附加系统 logcat 片段（Android 对非特权应用限制为只能读自身 UID 日志，可能为空）
 *
 * 脱敏：RequestLog 不输出 headers/requestBody（可能含 Authorization/API key），
 * 只输出排查所需的结构化字段。
 */
internal fun buildDeviceLogsTool(): Tool = Tool(
    name = "read_device_logs",
    description = """
        Read the app's device logs for debugging.
        Returns recent in-memory logs: text logs (tag + message) and HTTP request logs
        (url, method, response code, duration, error, model, reasoning effort, stream flag).
        Optionally appends a snippet of system logcat (usually restricted to this app's
        own UID on modern Android, and may come back empty).
        Request headers/bodies are never included (they may contain secrets).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("all")
                        add("text")
                        add("request")
                    })
                    put("description", "Which logs to read: 'all' (default), 'text', or 'request'.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of log entries to return. Default 50, max 100.")
                })
                put("filter", buildJsonObject {
                    put("type", "string")
                    put("description", "Case-insensitive keyword filter matched against tag and message/url.")
                })
                put("include_logcat", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Also append a system logcat snippet (default false; usually restricted, may be empty).")
                })
            }
        )
    },
    execute = { args ->
        val type = args.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "all"
        val limit = (args.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 50).coerceIn(1, 100)
        val filter = args.jsonObject["filter"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: ""
        val includeLogcat = args.jsonObject["include_logcat"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true

        val entries = Logging.getRecentLogs().asSequence()
            .filter { entry ->
                when (type) {
                    "text" -> entry is LogEntry.TextLog
                    "request" -> entry is LogEntry.RequestLog
                    else -> true
                }
            }
            .filter { entry ->
                if (filter.isBlank()) {
                    true
                } else {
                    val haystack = when (entry) {
                        is LogEntry.TextLog -> "${entry.tag} ${entry.message}"
                        is LogEntry.RequestLog -> "${entry.tag} ${entry.url} ${entry.method} ${entry.error.orEmpty()}"
                    }.lowercase()
                    haystack.contains(filter)
                }
            }
            .take(limit)
            .toList()

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        val jsonArray = buildJsonArray {
            entries.forEach { entry ->
                when (entry) {
                    is LogEntry.TextLog -> {
                        add(buildJsonObject {
                            put("type", "text")
                            put("time", timeFormat.format(Date(entry.timestamp)))
                            put("tag", entry.tag)
                            put("message", entry.message)
                        })
                    }

                    is LogEntry.RequestLog -> {
                        add(buildJsonObject {
                            put("type", "request")
                            put("time", timeFormat.format(Date(entry.timestamp)))
                            put("url", entry.url)
                            put("method", entry.method)
                            entry.responseCode?.let { put("response_code", it) }
                            entry.durationMs?.let { put("duration_ms", it) }
                            entry.error?.let { put("error", it) }
                            entry.model?.let { put("model", it) }
                            entry.effort?.let { put("effort", it) }
                            entry.stream?.let { put("stream", it) }
                            entry.purpose?.let { put("purpose", it) }
                        })
                    }
                }
            }
        }

        var result = buildString {
            append("{\"logs\":")
            append(jsonArray.toString())
            append(",\"count\":")
            append(entries.size)
            if (entries.size >= limit) {
                append(",\"truncated\":true")
            }
            append("}")
        }

        if (includeLogcat) {
            val logcat = readSystemLogcat(limit)
            result = result.dropLast(1) + ",\"logcat\":" + logcat + "}"
        }

        listOf(UIMessagePart.Text(result))
    }
)

private suspend fun readSystemLogcat(lines: Int): String = withContext(Dispatchers.IO) {
    runCatching {
        val process = ProcessBuilder("logcat", "-d", "-t", lines.coerceAtMost(200).toString())
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            val sb = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val read = reader.read(buffer)
                if (read <= 0) break
                sb.append(buffer, 0, read)
            }
            sb.toString()
        }
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroy()
        }
        if (output.isBlank()) {
            "\"\""
        } else {
            // 转义为 JSON 字符串
            output.take(8000).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        }
    }.getOrElse { e ->
        "\"logcat unavailable: ${e.message.orEmpty().replace("\"", "'")}\""
    }
}
