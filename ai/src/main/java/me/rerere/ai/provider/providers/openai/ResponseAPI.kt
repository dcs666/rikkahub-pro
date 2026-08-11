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

private const val TAG = "ResponseAPI"

/**
 * 诊断：把 responses 请求的 input 结构摘要化输出（每个 item 的类型/id/名称/内容长度+开头），
 * 不截断 item 条数，便于定位网关 400（如 thinking mode reasoning_text 校验失败）的确切消息形状。
 */
private fun summarizeInput(input: JsonElement?): String {
    if (input == null) return "null"
    val arr = input as? JsonArray
    if (arr == null) return input.toString().take(2000)
    return arr.mapIndexed { index, item ->
        val o = item as? JsonObject
        val type = o?.get("type")?.jsonPrimitiveOrNull?.contentOrNull
            ?: o?.get("role")?.jsonPrimitiveOrNull?.contentOrNull
            ?: "?"
        val id = o?.get("id")?.jsonPrimitiveOrNull?.contentOrNull
        val name = o?.get("name")?.jsonPrimitiveOrNull?.contentOrNull
        val callId = o?.get("call_id")?.jsonPrimitiveOrNull?.contentOrNull
        val content = o?.get("content")
        val contentInfo = when {
            content == null -> ""
            content is JsonPrimitive -> " content=[str ${content.contentOrNull?.length}ch]"
            content is JsonArray -> " content=[arr ${content.size}]"
            else -> ""
        }
        val textHead = when {
            content == null -> ""
            content is JsonPrimitive -> content.contentOrNull?.take(150)?.let { " head=${it}" } ?: ""
            content is JsonArray ->
                (content.firstOrNull() as? JsonObject)?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
                    ?.take(150)?.let { " head=${it}" } ?: ""
            else -> ""
        }
        val output = o?.get("output")
        val outputInfo = when {
            output == null -> ""
            output is JsonPrimitive ->
                " out=[str ${output.contentOrNull?.length}ch ${output.contentOrNull?.take(80)}]"
            output is JsonArray -> " out=[arr ${output.size}]"
            else -> ""
        }
        "#$index {$type${id?.let { " id=$it" } ?: ""}${name?.let { " name=$it" } ?: ""}${callId?.let { " call=$it" } ?: ""}$contentInfo$outputInfo$textHead"
    }.joinToString("\n")
}

/**
 * Console Go 网关（opencode.ai）thinking mode 要求带工具调用的 assistant 消息必须回传
 * 非空 reasoning_text（实测空字符串会被拒绝，非空即可、id 可选）。历史消息若未捕获到
 * 思维链（如开启思考模式前产生的消息），用占位符补上，否则网关 400：
 * The `reasoning_text` in the thinking mode must be passed back to the API
 */
private const val REASONING_PLACEHOLDER = "…"

/** [L3] 工具输出回传上限：超长截断（对齐 opencode TOOL_OUTPUT_MAX_CHARS=2000 思路，取 2500） */
private const val MAX_TOOL_OUTPUT_CHARS = 2500

// ===== [L3] opencode compaction 机制照抄（compaction.ts / overflow.ts / token.ts）=====
/** token 估算：opencode Token.estimate = chars / 4（token.ts CHARS_PER_TOKEN=4） */
private const val CHARS_PER_TOKEN = 4

/** overflow 预留 buffer：opencode overflow.ts COMPACTION_BUFFER=20_000 */
private const val COMPACTION_BUFFER = 20_000

/**
 * 模型上下文窗口（触发压缩的"假窗口"）。
 * 注：deepseek-v4-flash 真实 context 为 1M tokens，但 opencode 网关路径下
 * 无官方 implicit caching 红利（实测 200KB 请求体 TTFB 9.8s）——按真实值
 * 设阈值会导致压缩永不触发、卡顿依旧。故按用户实测体感（TTFB < 3s 需
 * 请求体 ≤ 50KB ≈ 15-20K tokens）取值：140K − 20K buffer = 120K tokens
 * 触发，覆盖绝大多数长对话场景。如不准可做成 provider 配置。
 */
private const val CONTEXT_LIMIT = 140_000

/** prune 保护线：opencode PRUNE_PROTECT=40_000（累计估算超此值的更老工具输出才清空） */
private const val PRUNE_PROTECT = 40_000

/** prune 最小清理量：opencode PRUNE_MINIMUM=20_000（清理量不达此值不应用） */
private const val PRUNE_MINIMUM = 20_000

/** 最近保护轮数：opencode DEFAULT_TAIL_TURNS=2（最近 2 个 user turn 完全不 prune/截断） */
private const val TAIL_TURNS = 2

/** 受保护工具：opencode PRUNE_PROTECTED_TOOLS=["skill"] */
private val PRUNE_PROTECTED_TOOLS = setOf("skill")

// [P1] token 估算缓存：条目上限，超限清空重建（估算本身廉价，缓存只是消除重复扫描）
private const val ESTIMATE_CACHE_MAX_ENTRIES = 4096
// key 超长不缓存（如内嵌 data URL 的 Image part，文本几千~几万字符，缓存收益低且占内存）
private const val ESTIMATE_CACHE_MAX_KEY_LEN = 8192

/** 清空占位文本：opencode serialize() 对 compacted 工具输出用此文案 */
private const val CLEARED_TOOL_OUTPUT = "[Old tool result content cleared]"

/**
 * [F2] 增量请求失败信号：streamText 外层捕获后自动回退全量重试一次
 *（增量是性能优化，失败不应让用户看到报错；codex 同样失败后回退全量）。
 */
private class IncrementalFailedException(cause: Throwable?) : Exception(cause)

class ResponseAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default()
) : OpenAIImpl {
    // [codex 借鉴] previous_response_id 增量发送：历史（含超长思维链）不重传，
    // 只发新增消息——input 从几十~几百 KB 降到仅增量，请求延迟与 token 双降。
    private val incrementalSessions = IncrementalSessions()

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk {
        val fullRequestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = false,
        )
        val host = providerSetting.baseUrl.toHttpUrl().host
        // 尝试增量：命中（历史与上次一致）→ previous_response_id + 仅新增 items；
        // 未命中（编辑/重试/新会话）→ 全量发送
        var requestBody = applyIncremental(providerSetting, host, fullRequestBody, params.model.modelId)
        val isIncremental = requestBody.containsKey("previous_response_id")
        var response = client.newCall(
            buildRequest(providerSetting, params, requestBody)
        ).await()
        if (!response.isSuccessful && isIncremental) {
            // [F2] 增量请求失败（如服务端 previous_response_id 过期/上下文超限）→
            // 使会话失效 + 自动回退全量重试一次（否则用户直接看到报错）
            // [F12] 先记录失败原因 + 关闭失败响应（body 未读，close 释放连接，
            // 否则每次增量失败泄漏一个 OkHttp 连接）
            val failBody = response.body?.stringSafe().orEmpty()
            Logging.log(TAG, "incremental failed (${response.code}) → retry full: ${failBody.take(2000)}")
            response.close()
            invalidateIncremental(providerSetting, host, fullRequestBody)
            requestBody = fullRequestBody
            response = client.newCall(
                buildRequest(providerSetting, params, requestBody)
            ).await()
        }
        val bodyStr = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            if (response.code == 400) {
                // 诊断：网关 400 时记录完整错误 + input 形状，便于复现
                //（如 thinking mode reasoning_text 校验失败、previous_response_id 无效等）
                // 注意用 Logging.log（App 内存日志），Log.w 只进 logcat 无法远程查看
                Logging.log(TAG, "generateText 400 body=${bodyStr.take(4000)}")
                Logging.log(TAG, "generateText 400 input=${requestBody["input"]?.toString()?.take(4000)}")
            }
            throw Exception("Failed to get response: ${response.code} $bodyStr")
        }

        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        // 记录会话状态（下次请求可增量）：sentInput=完整 input，responseItems=本次输出
        val outputItems = bodyJson["output"] as? JsonArray
        if (outputItems != null) {
            recordIncremental(providerSetting, host, fullRequestBody, bodyJson["id"]?.jsonPrimitive?.contentOrNull, outputItems.toList())
        }
        val output = parseResponseOutput(bodyJson)

        return output
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = flow {
        val fullRequestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val host = providerSetting.baseUrl.toHttpUrl().host
        var requestBody = applyIncremental(providerSetting, host, fullRequestBody, params.model.modelId)
        var attempt = 0
        while (true) {
            attempt++
            val isIncremental = requestBody.containsKey("previous_response_id")
            try {
                emitAll(streamOnce(providerSetting, params, requestBody, fullRequestBody, host))
                break
            } catch (e: IncrementalFailedException) {
                // [F2] 增量请求失败（400）→ 使会话失效 + 自动回退全量重试一次
                //（否则用户直接看到报错；codex 同样失败后回退全量）
                if (!isIncremental || attempt >= 2) throw e
                invalidateIncremental(providerSetting, host, fullRequestBody)
                requestBody = fullRequestBody
            }
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED)

    private fun streamOnce(
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
        requestBody: JsonObject,
        fullRequestBody: JsonObject,
        host: String,
    ): Flow<MessageChunk> = callbackFlow {
        val isIncremental = requestBody.containsKey("previous_response_id")
        // 流式增量记录：收集 output_item.done 的完整 item + response id
        val streamOutputItems = mutableListOf<JsonElement>()
        // [F5] response.created 捕获 response id——某些网关用 [DONE] 结束而不发
        // response.completed，此时也要记录会话（否则该网关永远无法增量）
        var streamResponseId: String? = null
        val request = buildRequest(providerSetting, params, requestBody)

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    // [F5] [DONE] 结束也记录增量会话（response.created 已捕获 id）
                    streamResponseId?.let { rid ->
                        recordIncremental(providerSetting, host, fullRequestBody, rid, streamOutputItems.toList())
                    }
                    close()
                    return
                }
                Log.d(TAG, "onEvent: $id/$type $data")
                try {
                    val json = json.parseToJsonElement(data).jsonObject
                    // [codex 借鉴] 流式收集：output_item.done 有完整 item JSON；
                    // response.completed 有 response.id → 记录会话状态供下次增量
                    if (type == "response.created") {
                        streamResponseId = json["response"]?.jsonObject?.get("id")
                            ?.jsonPrimitive?.contentOrNull
                    }
                    if (type == "response.output_item.done") {
                        json["item"]?.let { streamOutputItems.add(it) }
                    }
                    if (type == "response.completed") {
                        val responseId = json["response"]?.jsonObject?.get("id")
                            ?.jsonPrimitive?.contentOrNull
                        // [opencode 网关] 透传路径上游会发 output_item.done（streamOutputItems
                        // 收集）；但转换路径（上游 anthropic/google/chat）只发 output_text.delta /
                        // output_item.added / completed——没有 done → streamOutputItems 为空 →
                        // responseItems 空 → 回显过滤失效（增量退化）。
                        // 兜底：completed 事件若带完整 response.output（OpenAI 官方透传带），
                        // 直接用作输出 items。
                        val completedOutput = json["response"]?.jsonObject?.get("output")
                            ?.let { it as? JsonArray }
                        val items = if (completedOutput != null && completedOutput.isNotEmpty()) {
                            completedOutput.toList()
                        } else {
                            streamOutputItems.toList()
                        }
                        recordIncremental(providerSetting, host, fullRequestBody, responseId, items)
                    }
                    val chunk = parseResponseDelta(json)
                    if (chunk != null) {
                        trySend(chunk).onFailure { e ->
                            Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                        }
                    }
                    if (type == "response.completed") {
                        close()
                    }
                } catch (e: Throwable) {
                    // 网关事件数据非法（非 JSON/未知 chunk 类型）时不能悬挂：
                    // 以异常结束 flow，让调用方收到错误提示
                    Log.w(TAG, "onEvent: failed to process event data: $data", e)
                    close(e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // [F2] 增量请求失败（400，如 previous_response_id 过期）→ 使会话失效
                // + 抛重试信号，外层自动回退全量重试一次
                if (response?.code == 400 && isIncremental) {
                    // [F13] 读 body 记诊断（body 未读就 close 会丢失败原因）
                    val failBody = response.body?.stringSafe().orEmpty()
                    Logging.log(TAG, "onFailure code=400 body=${failBody.take(2000)}")
                    Logging.log(TAG, "onFailure request input-structure:\n${summarizeInput(requestBody["input"])}")
                    invalidateIncremental(providerSetting, host, fullRequestBody)
                    Logging.log(TAG, "onFailure: incremental request failed (400) → retry full")
                    close(IncrementalFailedException(t))
                    return
                }
                var exception = t

                val bodyRaw = response?.body?.stringSafe()
                // 诊断：400 时把响应体 + 请求 input 结构摘要打进 App 内存日志（Logging.log），
                // Log.w 只进 logcat 无法远程查看
                if (response?.code == 400) {
                    Logging.log(TAG, "onFailure code=${response?.code} body=${bodyRaw?.take(2000)}")
                    Logging.log(TAG, "onFailure request input-structure:\n${summarizeInput(requestBody["input"])}")
                }
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        Log.w(TAG, "onFailure: body=$bodyElement")
                        exception = bodyElement.parseErrorDetail()
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    exception = e
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
    }

    /** 构建 /responses 请求（增量/全量共用，F2 重试复用） */
    private fun buildRequest(
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
        requestBody: JsonObject,
    ): Request = Request.Builder()
        .url(buildEndpoint(providerSetting.baseUrl, "/responses"))
        .headers(params.customHeaders.toHeaders())
        .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
        .addHeader(
            "Authorization",
            "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}"
        )
        .addHeader("Content-Type", "application/json")
        .configureReferHeaders(providerSetting.baseUrl)
        .build()

    /**
     * [codex 借鉴] 尝试把请求体转为增量：命中增量会话时
     * 用 previous_response_id + 仅新增 items（+ store=true），否则原样返回。
     * [E2] 增量能力改为 provider 级配置 incrementalEnabled：
     * - 默认关闭（实测 opencode.ai 网关 /zen/v1 与 /zen/go/v1 会静默丢弃
     *   previous_response_id：200 但服务端不关联上下文，增量问秘密词答不出）
     * - 开启后要求 store=true（服务端保存 response 是增量前提）
     * - claude/gemini 前缀黑名单保留作防御：这些模型上游走 anthropic/google
     *   转换，previous_response_id 同样被丢弃且 function_call 取 .id 而非 call_id
     */
    private fun applyIncremental(
        providerSetting: ProviderSetting.OpenAI,
        host: String,
        fullRequestBody: JsonObject,
        modelId: String?,
    ): JsonObject {
        // [P3] 开关关闭直接短路：不查会话、不构建 map（增量是纯性能优化，关闭时零开销）
        if (!providerSetting.incrementalEnabled) return fullRequestBody
        // [F3] 只有 store=true 的请求才可能有增量能力：
        // 服务端不保存 response 时 previous_response_id 必然 400（invalid_response_id）
        if (fullRequestBody["store"]?.jsonPrimitive?.contentOrNull != "true") return fullRequestBody
        val model = modelId.orEmpty().lowercase()
        if (model.startsWith("claude") || model.startsWith("gemini")) return fullRequestBody
        val input = fullRequestBody["input"] as? JsonArray ?: return fullRequestBody
        val items = input.toList()
        val (previousResponseId, deltaItems) = incrementalSessions.resolve(host, items, fullRequestBody)
        if (previousResponseId == null || deltaItems == null) return fullRequestBody
        val newBody = fullRequestBody.toMutableMap().apply {
            put("previous_response_id", JsonPrimitive(previousResponseId))
            put("input", JsonArray(deltaItems))
            put("store", JsonPrimitive(true))
        }
        Log.d(TAG, "incremental: ${items.size} items -> ${deltaItems.size} delta (prev=$previousResponseId)")
        return JsonObject(newBody)
    }

    /** 记录一次成功响应到增量会话（sentInput=完整 input，responseItems=本次输出 items） */
    private fun recordIncremental(
        providerSetting: ProviderSetting.OpenAI,
        host: String,
        fullRequestBody: JsonObject,
        responseId: String?,
        responseItems: List<JsonElement>,
    ) {
        // [P3] 开关关闭时不记录（无增量会话可命中，记录纯属浪费）
        if (!providerSetting.incrementalEnabled) return
        if (responseId.isNullOrBlank()) return
        val input = fullRequestBody["input"] as? JsonArray ?: return
        incrementalSessions.update(host, fullRequestBody, responseId, responseItems)
    }

    /** 增量请求失败时使当前会话失效（防持续用无效 previous_response_id 重试） */
    private fun invalidateIncremental(
        providerSetting: ProviderSetting.OpenAI,
        host: String,
        fullRequestBody: JsonObject,
    ) {
        if (!providerSetting.incrementalEnabled) return
        val input = fullRequestBody["input"] as? JsonArray ?: return
        incrementalSessions.invalidate(host, input.toList())
    }

    internal fun buildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val capabilities = resolveResponseProviderCapabilities(host)
        // [E1] 上下文窗口上限配置化：provider 可配 contextLimitTokens（null = 默认 140K）
        val contextLimit = providerSetting.contextLimitTokens ?: CONTEXT_LIMIT
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            // [增量] store=true 是增量（previous_response_id）的前提：服务端保存
            // response 才能关联上下文。opencode.ai 网关支持；其他 host 仅在用户
            // 显式开启 incrementalEnabled 时 store（有实测支持的网关才开）。
            put("store", host == "opencode.ai" || providerSetting.incrementalEnabled)

            // DeepSeek 思考模式下 temperature/top_p 不生效（官方文档：不报错但无效），不发送避免误导
            val deepSeekThinking = host == "api.deepseek.com" && params.reasoningLevel.isEnabled
            if (isModelAllowTemperature(params.model) && !deepSeekThinking) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            // system instructions
            if (messages.any { it.role == MessageRole.SYSTEM }) {
                val parts = messages.first { it.role == MessageRole.SYSTEM }.parts
                put(
                    "instructions",
                    parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
            }

            // messages
            // DeepSeek Responses API 的 reasoning 输入 item 不支持 summary 字段（官方文档：
            // 明文 content 才被归并），需用明文 content 回传思维链（工具调用场景必须回传）；
            // OpenCode Zen 网关（Console provider）thinking mode 下要求 content 数组的
            // reasoning_text 类型回传（错误：The reasoning_text in the thinking mode must
            // be passed back to the API）
            put(
                "input",
                buildMessages(
                    messages,
                    usePlainReasoningContent = host == "api.deepseek.com",
                    useReasoningTextArray = host == "opencode.ai",
                    forcePlaceholderReasoning = host == "opencode.ai" && params.reasoningLevel.isEnabled,
                    contextLimit = contextLimit,
                ).stripItemIds()
            )

            // reasoning
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                put("reasoning", buildJsonObject {
                    if (capabilities.supportsReasoningSummary) {
                        put("summary", "auto")
                    }
                    if (level != ReasoningLevel.AUTO) {
                        // DeepSeek Responses API 的 effort 只支持 low/high/max（thinking_mode 文档），
                        // OpenCode Zen 网关（opencode.ai）同样代理 DeepSeek 系模型；
                        // App 的 XHIGH("xhigh")/MEDIUM("medium") 需映射到官方枚举，否则强度静默失效
                        val effort = if (host == "api.deepseek.com" || host == "opencode.ai") {
                            when (level) {
                                ReasoningLevel.XHIGH -> "max"
                                ReasoningLevel.MEDIUM -> "high"
                                else -> level.effort // none/low/high
                            }
                        } else {
                            level.effort
                        }
                        put("effort", effort)
                    }
                })
                if (capabilities.supportEncryptedContent) {
                    put("include", buildJsonArray {
                        add("reasoning.encrypted_content")
                    })
                }
            }

            // tools
            // Response API 的 tools 是扁平数组, 函数工具和内置工具可以共存, 必须写在同一个 key 下,
            // 否则后写入的会覆盖前者
            val useFunctionTools =
                params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()
            if (useFunctionTools || params.model.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    if (useFunctionTools) {
                        params.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("type", "function")
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parameters()
                                    )
                                )
                            })
                        }
                    }
                    // built-in tools
                    params.model.tools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("type", "web_search")
                                })
                            }

                            BuiltInTools.UrlContext -> {} // not supported

                            BuiltInTools.ImageGeneration -> {
                                add(buildJsonObject {
                                    put("type", "image_generation")
                                    put("model", "gpt-image-2")
                                })
                            }
                        }
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    /**
     * [L3] token 估算：适配 opencode Token.estimate（chars/4）。
     * opencode 的 overflow 判定用服务端真实 tokens.total（含中文精确计数），
     * 其本地 estimate 仅用于预算分配；我们无服务端计数 → 用加权估算兜底：
     * ASCII 4 字符 ≈ 1 token（对齐 opencode），非 ASCII（中文等）1 字符 ≈ 1 token
     * （真实分布），否则中文对话估算严重偏小、overflow 触发太晚。
     *
     * [P1] 结果按 part 文本内容缓存：多轮工具循环中历史消息不变（UIMessage 不可变），
     * 每轮请求构建都会全量重扫所有 parts 做估算——长对话下是重复 O(n) 扫描。
     * 缓存命中后直接复用；文本变化（工具输出更新、新消息）自然 miss 重算。
     */
    private val estimateCache = HashMap<String, Int>()

    private fun estimateTokens(part: UIMessagePart): Int {
        val text = when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Reasoning -> part.reasoning
            is UIMessagePart.Tool -> {
                val output = part.output.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                // 工具调用本身（input）+ 输出都计入（opencode tokens.total 含全部内容）
                part.input + "\n" + output
            }
            is UIMessagePart.ToolCall -> part.arguments
            // 图片以 data URL 内嵌时体积巨大，按字符估算计入（避免溢出判定偏小）
            is UIMessagePart.Image -> part.url
            else -> ""
        }
        if (text.length <= ESTIMATE_CACHE_MAX_KEY_LEN) {
            synchronized(estimateCache) {
                estimateCache[text]?.let { return it }
            }
        }
        var ascii = 0
        var nonAscii = 0
        for (ch in text) {
            if (ch.code <= 0x7F) ascii++ else nonAscii++
        }
        val result = ascii / CHARS_PER_TOKEN + nonAscii
        if (text.length <= ESTIMATE_CACHE_MAX_KEY_LEN) {
            synchronized(estimateCache) {
                if (estimateCache.size >= ESTIMATE_CACHE_MAX_ENTRIES) estimateCache.clear()
                estimateCache[text] = result
            }
        }
        return result
    }

    /**
     * [L3] prune 判定：照抄 opencode compaction.ts prune()——
     * 从最新往最旧遍历，最近 TAIL_TURNS(2) 个 user turn 完全保护；
     * 更早的工具输出按估算 token 累计，超过 PRUNE_PROTECT(40K) 的部分
     * 标记为待清空（发送时替换为 [Old tool result content cleared]）；
     * 仅当总清理量 > PRUNE_MINIMUM(20K) 才真正应用。skill 工具豁免。
     * 返回需要清空的 toolCallId 集合。
     */
    private fun pruneOldToolOutputs(messages: List<UIMessage>): Set<String> {
        var total = 0
        var pruned = 0
        var turns = 0
        val toPrune = mutableListOf<String>()
        for (msgIndex in messages.indices.reversed()) {
            val msg = messages[msgIndex]
            if (msg.role == MessageRole.USER) turns++
            if (turns < TAIL_TURNS) continue
            for (part in msg.parts.reversed()) {
                val tool = part as? UIMessagePart.Tool ?: continue
                if (!tool.isExecuted) continue
                if (tool.toolName in PRUNE_PROTECTED_TOOLS) continue
                val estimate = tool.output.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }.length / CHARS_PER_TOKEN
                total += estimate
                if (total <= PRUNE_PROTECT) continue
                pruned += estimate
                toPrune.add(tool.toolCallId)
            }
        }
        return if (pruned > PRUNE_MINIMUM) toPrune.toSet() else emptySet()
    }

    internal fun buildMessages(messages: List<UIMessage>,
        usePlainReasoningContent: Boolean = false,
        useReasoningTextArray: Boolean = false,
        forcePlaceholderReasoning: Boolean = false,
        contextLimit: Int = CONTEXT_LIMIT,
    ) = buildJsonArray {
        val filtered = messages.filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
        // [L1-FIX] 按"含思维链的消息"计数而非全部 assistant 消息：工具轮次（fc+fco）
        // 没有 reasoning，若按 assistant 计数，工具密集场景（1 思考 + ≥4 工具轮次）
        // 会把唯一/最新一轮思考挤出窗口 → 最新思考被占位，追问"思路"时质量受损
        val reasoningCount = filtered.count {
            it.role == MessageRole.ASSISTANT && it.parts.any { p -> p is UIMessagePart.Reasoning }
        }
        var reasoningIndex = 0
        // [L3] 对齐 opencode compaction 语义（compaction.ts turns()）：1 轮 = 1 个 user
        // 消息到下一个 user 消息之间（含中间所有工具调用）。最近 2 轮内的工具输出
        // 完整保留（当前决策链需要完整工具结果），更早的才截断。
        // opencode 默认 tail_turns=2，同一轮内多次工具调用全部保留——按 user 分段
        // 而非按 assistant 工具消息计数，避免误伤同一轮内的工具链。
        val userTurns = filtered.mapIndexedNotNull { index, m ->
            if (m.role == MessageRole.USER) index else null
        }
        val keepToolFrom = if (userTurns.size <= TAIL_TURNS) 0 else userTurns[userTurns.size - TAIL_TURNS]
        // [L3] overflow 判定（opencode overflow.ts isOverflow）：估算总 token 接近
        // 上下文上限时才触发历史截断——短对话完全不截，长对话自动收紧
        // [E1] 上限来自 provider 配置 contextLimitTokens（默认 CONTEXT_LIMIT=140K）
        val totalTokens = filtered.sumOf { message -> message.parts.sumOf { estimateTokens(it) } }
        val overflow = totalTokens >= contextLimit - COMPACTION_BUFFER
        // [L3] prune 判定（opencode compaction.ts prune()）：倒序遍历，最近 2 轮保护，
        // 更早工具输出累计估算超 PRUNE_PROTECT(40K) 的部分 → 清空为
        // [Old tool result content cleared]；清理量 > PRUNE_MINIMUM(20K) 才应用
        val clearedTools = pruneOldToolOutputs(filtered)
        filtered.forEachIndexed { index, message ->
            if (message.role == MessageRole.ASSISTANT) {
                val hasReasoning = message.parts.any { it is UIMessagePart.Reasoning }
                if (hasReasoning) reasoningIndex++
                // [L1] 思维链增量发送（2026-08-10 实测：opencode 网关 deepseek 系
                // 把 reasoning item 仅作 schema 校验/回显，不参与推理上下文——
                // 密码实验：正文里的密码模型答得出，思维链里的密码模型答不出）。
                // 因此历史思维链无需全量回传：保留最近 4 条含思维链的消息完整
                // （用户要求 4 轮；追问"思路"时最新思考仍可用），更早的用占位符
                // （网关只校验非空，拒空串——占位符 '…' 已解决）。
                // 相比旧实现（仅 overflow 才压缩）：
                // ① 请求体大幅缩小（思维链是 200KB 请求体的大头 → 5-10 倍削减）
                // ② 占位符稳定 → 前缀缓存命中率更高（全量思维链每次长度不同）
                // ③ 对回答质量零影响（服务端本来就不读历史思维链）
                val keepReasoning = !hasReasoning || reasoningIndex > reasoningCount - 4
                // [L3] 最近 2 轮（user 分段）内工具输出不截断
                val hasTool = message.parts.any { it is UIMessagePart.Tool && it.isExecuted }
                val keepToolOutput = !hasTool || index >= keepToolFrom
                addAssistantItems(
                    message,
                    usePlainReasoningContent,
                    useReasoningTextArray,
                    forcePlaceholderReasoning,
                    keepReasoning,
                    keepToolOutput,
                    overflow,
                    clearedTools,
                )
            } else {
                addUserItems(message)
            }
        }
    }

}

private fun isModelAllowTemperature(model: Model): Boolean {
    return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
}


internal data class ResponseProviderCapabilities(
    val supportsReasoningSummary: Boolean = true,
    val supportEncryptedContent: Boolean = true
)

internal fun resolveResponseProviderCapabilities(host: String): ResponseProviderCapabilities {
    return when (host) {
        "ark.cn-beijing.volces.com" -> ResponseProviderCapabilities(
            supportsReasoningSummary = false,
            supportEncryptedContent = false
        )

        "api.deepseek.com" -> ResponseProviderCapabilities(
            // DeepSeek Responses API 文档（guides/responses_api）：reasoning 输入 item 仅支持
            // 明文 content，summary / encrypted_content 不支持；请求参数 reasoning.summary
            // 可传入但不生成摘要 → 不发送无效参数；也不请求 reasoning.encrypted_content 输出
            supportsReasoningSummary = false,
            supportEncryptedContent = false
        )

        "opencode.ai" -> ResponseProviderCapabilities(
            // OpenCode Zen 网关（Console provider）：thinking mode 下 reasoning 输入 item
            // 必须用 content 数组的 reasoning_text 类型回传（错误：The reasoning_text in
            // the thinking mode must be passed back to the API），summary/encrypted_content
            // 不被接受 → 不发送无效参数，也不请求 encrypted_content 输出
            supportsReasoningSummary = false,
            supportEncryptedContent = false
        )

        else -> ResponseProviderCapabilities()
    }
}

