package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.common.js.injectFetch
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json

object CustomJsSearchService : SearchService<SearchServiceOptions.CustomJsOptions> {
    override val name: String = "Custom JS"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.custom_js_desc))
    }

    override fun parameters(options: SearchServiceOptions.CustomJsOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.CustomJsOptions): InputSchema? {
        if (options.scrapeScript.isBlank()) return null
        return InputSchema.Obj(
            properties = buildJsonObject {
                put("urls", buildJsonObject {
                    put("type", "array")
                    put("description", "urls to scrape")
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
                })
            },
            required = listOf("urls")
        )
    }

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.CustomJsOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val script = serviceOptions.searchScript.ifBlank { error("Search script is empty") }

            val resultJson = executeScript(
                userScript = script,
                invocation = "search(${quoteJsString(query)}, ${commonOptions.resultSize})"
            )

            json.decodeFromString<SearchResult>(resultJson)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.CustomJsOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val script = serviceOptions.scrapeScript.ifBlank { error("Scrape script is empty") }
            val urlsJson = params["urls"]?.toString() ?: error("urls is required")

            val resultJson = executeScript(
                userScript = script,
                invocation = "scrape($urlsJson)"
            )

            json.decodeFromString<ScrapedResult>(resultJson)
        }
    }

    private suspend fun executeScript(userScript: String, invocation: String): String {
        val context = QuickJSContext.create()
        var timedOut = false
        try {
            // [FIX] 限制脚本资源：用户自定义脚本可能分配爆炸（内存）或死循环（永不返回）。
            // 内存上限防止脚本一次性申请 GB 级；withTimeout 保证调用方不被永久挂起
            // （注意：QuickJS 为同步执行，超时后 IO 线程仍被脚本占住直到脚本返回，
            // 但调用方会及时收到超时错误而非无限等待）。
            context.setMemoryLimit(64 * 1024 * 1024)
            context.injectFetch(httpClient)
            return try {
                withTimeout(20_000) {
                    context.evaluate(userScript)
                    val result = context.evaluate("JSON.stringify($invocation)")
                    result as? String ?: error("Function returned null or undefined")
                }
            } catch (e: TimeoutCancellationException) {
                timedOut = true
                throw e
            }
        } finally {
            // [FIX] 超时时脚本可能仍在执行 native 代码：destroy 正在使用的 context
            // 是 use-after-free，会 SIGSEGV 崩溃整个 app。超时路径跳过销毁
            // （context 泄漏到进程结束，用户重启后恢复；死循环属罕见输入）。
            if (!timedOut) {
                context.destroy()
            }
        }
    }

    private fun quoteJsString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

}
