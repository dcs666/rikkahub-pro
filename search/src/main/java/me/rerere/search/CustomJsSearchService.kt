package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        // [FIX2] withTimeout 对同步 QuickJS evaluate 无效：evaluate/injectFetch 均为
        // 同步阻塞（无协程挂起点），死循环脚本永不返回 → 超时永不触发 → 调用方永久挂起。
        // 且 fetch 也是同步 OkHttp execute（无挂起点），同样无法被协作式取消。
        // 改为独立线程 + join 超时（与 eval_javascript 工具方案一致）：
        // - 正常/异常结束 → 线程内自行 destroy context（脚本已不在执行，销毁安全）
        // - 超时 → 放弃线程（不 destroy——正在执行的 native 代码访问已释放的
        //   runtime 是 use-after-free，会 SIGSEGV；线程/context 泄漏到进程结束）。
        // QuickJSContext 非线程安全：创建/注入/执行/销毁全部在子线程完成（单线程访问）。
        val resultHolder = java.util.concurrent.atomic.AtomicReference<String?>()
        val errorHolder = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val thread = Thread {
            val context = QuickJSContext.create()
            try {
                // [FIX] 内存炸弹防护：脚本可能 new 超大数组/疯狂拼接（OOM 崩溃面）
                context.setMemoryLimit(64 * 1024 * 1024)
                context.injectFetch(httpClient)
                context.evaluate(userScript)
                val result = context.evaluate("JSON.stringify($invocation)")
                resultHolder.set(result as? String ?: error("Function returned null or undefined"))
                context.destroy()
            } catch (t: Throwable) {
                errorHolder.set(t)
                // 异常已从 evaluate 抛出（native 调用已返回），此时销毁安全
                context.destroy()
            }
        }.apply {
            isDaemon = true
            start()
        }
        thread.join(20_000)
        if (thread.isAlive) {
            error("JavaScript execution timed out after 20s (possible infinite loop)")
        }
        errorHolder.get()?.let { throw it }
        return resultHolder.get() ?: error("No result")
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
