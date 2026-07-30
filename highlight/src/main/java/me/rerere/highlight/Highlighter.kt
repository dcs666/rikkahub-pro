package me.rerere.highlight

import android.content.Context
import com.whl.quickjs.wrapper.QuickJSArray
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.highlight.HighlightToken.Token.StringContent
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Highlighter(ctx: Context) {
    // [TURBO] 并行高亮：单线程 executor → 固定线程池（池大小 PARALLELISM）。QuickJS 单 context
    // 线程不安全，故每线程一个独立 context（threadContext，ThreadLocal）。批次1的缓存已消除重复
    // tokenize，本并行进一步把"首次打开多代码块对话"的串行 tokenize 并行化。
    private val executor = Executors.newFixedThreadPool(PARALLELISM)

    // 所有已创建的 context（destroy 时统一销毁）。ThreadLocal 无法遍历各线程的值，故自维护列表。
    private val contexts = mutableListOf<QuickJSContext>()

    // [TURBO] 高亮结果 LRU 缓存。accessOrder=true 让 get 也刷新顺序（真正的 LRU）；
    // 所有访问都 synchronized 保护（get 会改链表顺序，多线程下必须同步）。最多 100 条。
    private val cache = object : LinkedHashMap<String, List<HighlightToken>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<HighlightToken>>?): Boolean =
            size > MAX_CACHE_SIZE
    }

    private val script: String by lazy {
        ctx.resources.openRawResource(R.raw.prism).use {
            it.bufferedReader().readText()
        }
    }

    // 每线程一个独立 QuickJS context（QuickJS 单 context 线程不安全）。创建时登记到 contexts。
    private val threadContext = object : ThreadLocal<QuickJSContext>() {
        override fun initialValue(): QuickJSContext {
            val context = QuickJSContext.create().also { it.evaluate(script) }
            synchronized(contexts) { contexts.add(context) }
            return context
        }
    }

    init {
        // 预热：让一个线程提前创建 context，避免首次高亮的创建延迟。
        executor.submit {
            threadContext.get()
        }
    }

    suspend fun highlight(code: String, language: String): List<HighlightToken> {
        // [TURBO] 高亮结果 LRU 缓存：代码块每次进 composition（滚回/切对话/展开折叠）都会重新跑
        // QuickJS tokenize，且 Highlighter 是全局单线程串行——含多代码块的对话高亮"陆续慢出"、
        // 滚回还要重算。缓存后命中即零 tokenize，直接返回。key 用完整 language+code（无哈希碰撞风险）。
        val key = "$language\n$code"
        synchronized(cache) { cache[key]?.let { return it } }
        val tokens = highlightRaw(code, language)
        synchronized(cache) { cache[key] = tokens }
        return tokens
    }

    private suspend fun highlightRaw(code: String, language: String): List<HighlightToken> =
        suspendCancellableCoroutine { continuation ->
            executor.submit {
                runCatching {
                    val highlightFn = threadContext.get().globalObject.getJSFunction("highlight")
                    val result = highlightFn.call(code, language)
                    require(result is QuickJSArray) {
                        "highlight result must be an array"
                    }
                    val tokens = arrayListOf<HighlightToken>()
                    for (i in 0 until result.length()) {
                        when (val element = result[i]) {
                            is String -> tokens.add(
                                HighlightToken.Plain(
                                    content = element,
                                )
                            )

                            is QuickJSObject -> {
                                val json = element.stringify()
                                val token = format.decodeFromString<HighlightToken.Token>(
                                    HighlightTokenSerializer, json
                                )
                                tokens.add(token)
                            }

                            else -> error("Unknown type: ${element::class.java.name}")
                        }
                    }
                    result.release()
                    continuation.resume(tokens)
                }.onFailure {
                    it.printStackTrace()
                    if (continuation.isActive) {
                        continuation.resumeWithException(it)
                    }
                }
            }
        }

    fun destroy() {
        synchronized(contexts) {
            contexts.forEach { runCatching { it.destroy() } }
            contexts.clear()
        }
    }

    private companion object {
        private const val MAX_CACHE_SIZE = 100
        // 并行高亮线程池大小。每线程一个 QuickJS context（各占几 MB），3 是并行度与内存的平衡。
        private const val PARALLELISM = 3
    }
}

private val format by lazy {
    Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
}

sealed class HighlightToken {
    data class Plain(
        val content: String,
    ) : HighlightToken()

    @Serializable
    sealed class Token() : HighlightToken() {
        @Serializable
        data class StringContent(
            val content: String,
            val type: String,
            val length: Int,
        ) : Token()

        @Serializable
        data class StringListContent(
            val content: List<String>,
            val type: String,
            val length: Int,
        ) : Token()

        @Serializable
        data class Nested(
            val content: List<Token>,
            val type: String,
            val length: Int,
        ) : Token()
    }
}

object HighlightTokenSerializer : KSerializer<HighlightToken.Token> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("HighlightToken.Token")

    override fun serialize(
        encoder: Encoder,
        value: HighlightToken.Token
    ) {
        // not used
    }

    override fun deserialize(decoder: Decoder): HighlightToken.Token {
        val jsonDecoder = decoder as JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val type = jsonObject["type"]?.jsonPrimitive?.content
            ?: error("Missing type field in HighlightToken.Token")
        val length = jsonObject["length"]?.jsonPrimitive?.int
            ?: error("Missing length field in HighlightToken.Token")
        val content = jsonObject["content"]
            ?: error("Missing content field in HighlightToken.Token")

        return when (content) {
            is JsonArray -> {
                val nestedContent = arrayListOf<HighlightToken.Token>()

                content.forEach { part ->
                    if (part is JsonPrimitive) {
                        nestedContent += StringContent(
                            content = part.content,
                            type = type,
                            length = length,
                        )
                    } else if (part is JsonObject) {
                        nestedContent += format.decodeFromJsonElement(
                            HighlightTokenSerializer,
                            part
                        )
                    } else {
                        error("unknown content part type: $content / $part")
                    }
                }

                HighlightToken.Token.Nested(
                    content = nestedContent,
                    type = type,
                    length = length,
                )
            }

            is JsonPrimitive -> {
                val stringContent = content.content
                HighlightToken.Token.StringContent(
                    content = stringContent,
                    type = type,
                    length = length,
                )
            }

            else -> error("Unknown content type: ${content::class.java.name}")
        }
    }
}
