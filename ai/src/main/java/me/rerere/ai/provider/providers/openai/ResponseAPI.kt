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

/** 模型上下文窗口：App 无 model.limit 元数据，deepseek 系取 128K（如不准可做成配置） */
private const val CONTEXT_LIMIT = 128_000

/** prune 保护线：opencode PRUNE_PROTECT=40_000（累计估算超此值的更老工具输出才清空） */
private const val PRUNE_PROTECT = 40_000

/** prune 最小清理量：opencode PRUNE_MINIMUM=20_000（清理量不达此值不应用） */
private const val PRUNE_MINIMUM = 20_000

/** 最近保护轮数：opencode DEFAULT_TAIL_TURNS=2（最近 2 个 user turn 完全不 prune/截断） */
private const val TAIL_TURNS = 2

/** 受保护工具：opencode PRUNE_PROTECTED_TOOLS=["skill"] */
private val PRUNE_PROTECTED_TOOLS = setOf("skill")

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
        var requestBody = applyIncremental(host, fullRequestBody, params.model.modelId)
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
            invalidateIncremental(host, fullRequestBody)
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
            recordIncremental(host, fullRequestBody, bodyJson["id"]?.jsonPrimitive?.contentOrNull, outputItems.toList())
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
        var requestBody = applyIncremental(host, fullRequestBody, params.model.modelId)
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
                invalidateIncremental(host, fullRequestBody)
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
                        recordIncremental(host, fullRequestBody, rid, streamOutputItems.toList())
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
                        recordIncremental(host, fullRequestBody, responseId, items)
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
                    invalidateIncremental(host, fullRequestBody)
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
     */
    private fun applyIncremental(host: String, fullRequestBody: JsonObject, modelId: String?): JsonObject {
        // [F3] 只有 store=true 的 host 才可能有增量能力：
        // 其他 host（OpenAI 官方/DeepSeek 直连等）服务端不保存 response，
        // previous_response_id 必然 400（invalid_response_id），一律全量
        if (fullRequestBody["store"]?.jsonPrimitive?.contentOrNull != "true") return fullRequestBody
        // [2026-08-10 实测] opencode.ai 网关（/zen/v1 与 /zen/go/v1，deepseek 系）
        // 会静默丢弃 previous_response_id：请求返回 200 但服务端不关联上下文，
        // 只发新消息时模型失忆（强测试：设秘密词 → 增量问秘密词答不出；
        // 之前"答对 6"是常识弱测试误判）。增量白名单留空 = 全禁用，
        // 待未来有实测支持 previous_response_id 的网关再启用。
        // （claude/gemini 前缀黑名单保留作防御：这些模型上游走 anthropic/google
        // 转换，previous_response_id 同样被丢弃且 function_call 取 .id 而非 call_id）
        val incrementalHosts: Set<String> = emptySet()
        if (host !in incrementalHosts) return fullRequestBody
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
        host: String,
        fullRequestBody: JsonObject,
        responseId: String?,
        responseItems: List<JsonElement>,
    ) {
        if (responseId.isNullOrBlank()) return
        val input = fullRequestBody["input"] as? JsonArray ?: return
        incrementalSessions.update(host, fullRequestBody, responseId, responseItems)
    }

    /** 增量请求失败时使当前会话失效（防持续用无效 previous_response_id 重试） */
    private fun invalidateIncremental(host: String, fullRequestBody: JsonObject) {
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
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            // [增量] opencode.ai 网关支持 previous_response_id（实测消息追加）：
            // 全量请求也 store=true（服务端保存 response，后续请求才能增量）。
            // 其他 host 保持 store=false（无增量能力，避免服务端存储开销/兼容风险）。
            put("store", host == "opencode.ai")

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

    /** [L3] token 估算：对齐 opencode Token.estimate = chars / 4 */
    private fun estimateTokens(part: UIMessagePart): Int {
        val text = when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Reasoning -> part.reasoning
            is UIMessagePart.Tool -> part.output.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            else -> ""
        }
        return text.length / CHARS_PER_TOKEN
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
        val totalTokens = filtered.sumOf { message -> message.parts.sumOf { estimateTokens(it) } }
        val overflow = totalTokens >= CONTEXT_LIMIT - COMPACTION_BUFFER
        // [L3] prune 判定（opencode compaction.ts prune()）：倒序遍历，最近 2 轮保护，
        // 更早工具输出累计估算超 PRUNE_PROTECT(40K) 的部分 → 清空为
        // [Old tool result content cleared]；清理量 > PRUNE_MINIMUM(20K) 才应用
        val clearedTools = pruneOldToolOutputs(filtered)
        filtered.forEachIndexed { index, message ->
            if (message.role == MessageRole.ASSISTANT) {
                val hasReasoning = message.parts.any { it is UIMessagePart.Reasoning }
                if (hasReasoning) reasoningIndex++
                // [L1] 思维链压缩：仅最近 4 条含思维链的消息保留完整思维链，
                // 更早的用占位符（网关只校验非空；历史思维链对生成质量无益——
                // opencode 官方甚至对历史消息补空 reasoning）
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

    private fun JsonArrayBuilder.addAssistantItems(
        message: UIMessage,
        usePlainReasoningContent: Boolean = false,
        useReasoningTextArray: Boolean = false,
        forcePlaceholderReasoning: Boolean = false,
        keepReasoning: Boolean = true,
        keepToolOutput: Boolean = true,
        overflow: Boolean = false,
        clearedTools: Set<String> = emptySet(),
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()
        // Console Go 网关（opencode.ai）thinking mode：带工具调用的 assistant 消息必须
        // 回传非空 reasoning_text；历史消息未捕获思维链时用占位符补上
        var reasoningEmitted = false
        // [FIX] 网关按 function_call 逐个校验 reasoning：1 个 reasoning item 只"服务"其
        // 后的第 1 个 fc；同一条消息内第 2 个 fc 起（或跨 Tools group）前面没有 reasoning
        // 会 400「The reasoning_text in the thinking mode must be passed back to the API」
        //（实测：占位符可接受、assistant content 不能替代、reasoning 补在 fc 之后无效）。
        // 用计数器跟踪"距上次 reasoning 输出后已输出的 fc 数"，>0 时在下一个 fc 前补占位。
        var fcSinceReasoning = 0

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Reasoning -> {
                                // 先输出累积的文本/图片内容
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                // 输出 reasoning item
                                val reasoningMetadata = part.metadataAs<OpenAIReasoningMetadata>()
                                add(buildJsonObject {
                                    put("type", "reasoning")
                                    // [codex 经验] 回传 input 时不带服务端生成的 item id：
                                    // openai/codex 的 prepare_response_items_for_request 发送前
                                    // 清除所有非 prefixed id（服务端按位置/内容重建关联），
                                    // 避免网关对已过期 id 做状态校验。OpenAI 官方同样接受无 id。
                                    when {
                                        usePlainReasoningContent -> {
                                            // DeepSeek Responses API 不支持 summary 字段，用明文 content 回传思维链；
                                            // [L1] 旧轮次思维链用占位符（网关只校验非空）
                                            put("content", if (keepReasoning) part.reasoning else REASONING_PLACEHOLDER)
                                        }

                                        useReasoningTextArray -> {
                                            // OpenCode Zen 网关（Console provider）thinking mode 要求
                                            // content 数组的 reasoning_text 类型回传；思维链为空时用
                                            // 占位符（实测空字符串会被网关拒绝）；
                                            // [L1] 旧轮次思维链同样用占位符（保留 item 结构满足校验）
                                            val text = if (keepReasoning) {
                                                part.reasoning.ifBlank { REASONING_PLACEHOLDER }
                                            } else {
                                                REASONING_PLACEHOLDER
                                            }
                                            put("content", buildJsonArray {
                                                add(buildJsonObject {
                                                    put("type", "reasoning_text")
                                                    put("text", text)
                                                })
                                            })
                                        }

                                        else -> {
                                            put("summary", buildJsonArray {
                                                add(buildJsonObject {
                                                    put("type", "summary_text")
                                                    put("text", if (keepReasoning) part.reasoning else REASONING_PLACEHOLDER)
                                                })
                                            })
                                        }
                                    }
                                    // encrypted_content 仅 OpenAI 官方支持：DeepSeek / OpenCode Zen
                                    // 网关均不接受（能力矩阵 supportEncryptedContent=false），
                                    // 回传会触发上游校验错误 → 仅 summary 分支（官方）回传；
                                    // [L1] 旧轮次思维链已占位，不匹配的 encrypted_content 不再回传
                                    if (keepReasoning && !usePlainReasoningContent && !useReasoningTextArray) {
                                        reasoningMetadata?.encryptedContent?.let {
                                            put("encrypted_content", it)
                                        }
                                    }
                                })
                                // 无论用哪种格式回传，只要 reasoning item 已输出（且文本非空，
                                // useReasoningTextArray 分支已用占位符兜底），标记本消息已带思维链
                                reasoningEmitted = true
                                fcSinceReasoning = 0
                            }

                            is UIMessagePart.Image -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                addContentItem(MessageRole.USER, listOf(part))
                            }

                            is UIMessagePart.Text -> {
                                contentBuffer.add(part)
                            }

                            else -> {}
                        }
                    }
                }

                is PartGroup.Tools -> {
                    // 先输出累积的内容
                    if (contentBuffer.isNotEmpty()) {
                        addContentItem(MessageRole.ASSISTANT, contentBuffer)
                        contentBuffer.clear()
                    }

                    // Console Go 网关（opencode.ai）thinking mode：带工具调用的 assistant 消息
                    // 必须回传非空 reasoning_text。历史消息若未捕获到思维链（如开启思考模式前
                    // 产生的工具消息），补占位符 reasoning item，否则网关 400（错误：The
                    // reasoning_text in the thinking mode must be passed back to the API）
                    if (forcePlaceholderReasoning && !reasoningEmitted) {
                        add(buildJsonObject {
                            put("type", "reasoning")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "reasoning_text")
                                    put("text", REASONING_PLACEHOLDER)
                                })
                            })
                        })
                        reasoningEmitted = true
                        fcSinceReasoning = 0
                    }

                    // 输出 function_call + function_call_output
                    group.tools.forEach { tool ->
                        // [FIX] 网关按 fc 逐个校验：1 个 reasoning 只服务其后的第 1 个 fc，
                        // 同组第 2 个 fc 起（以及跨 Tools group 的下一个 fc）前面没有
                        // reasoning 会 400（占位符可接受、assistant content 不能替代）。
                        if (forcePlaceholderReasoning && fcSinceReasoning > 0) {
                            add(buildJsonObject {
                                put("type", "reasoning")
                                put("content", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "reasoning_text")
                                        put("text", REASONING_PLACEHOLDER)
                                    })
                                })
                            })
                            fcSinceReasoning = 0
                        }
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tool.toolCallId)
                            put("name", tool.toolName)
                            // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
                            put("arguments", tool.inputAsJson().toString())
                        })
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", tool.toolCallId)
                            val hasImage = tool.output.any { it is UIMessagePart.Image }
                            if (hasImage) {
                                putJsonArray("output") {
                                    tool.output.forEach { part ->
                                        when (part) {
                                            is UIMessagePart.Image -> add(buildJsonObject {
                                                part.encodeBase64().onSuccess { encoded ->
                                                    put("type", "input_image")
                                                    put("image_url", encoded.base64)
                                                }.onFailure {
                                                    it.printStackTrace()
                                                    put("type", "input_text")
                                                    put("text", "Error: Failed to encode image to base64")
                                                }
                                            })
                                            is UIMessagePart.Text -> add(buildJsonObject {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            })
                                            else -> {}
                                        }
                                    }
                                }
                            } else {
                                // [FIX] 空输出时给占位文本，避免提供商拒绝空 output
                                // [L3] 工具输出处理（照抄 opencode compaction 语义，优先级从高到低）：
                                // ① prune 清空：超预算的旧工具输出整体替换为
                                //    [Old tool result content cleared]（opencode prune()）
                                // ② overflow 截断：对话估算总 token 接近上下文上限时，
                                //    非最近 2 轮的超长输出截断（opencode serialize truncate）
                                // ③ 完整保留：最近 2 轮 / 未 overflow 时不截断
                                val text = tool.output.filterIsInstance<UIMessagePart.Text>()
                                    .joinToString("\n") { it.text }
                                val output = when {
                                    tool.toolCallId in clearedTools -> CLEARED_TOOL_OUTPUT
                                    !keepToolOutput && overflow && text.length > MAX_TOOL_OUTPUT_CHARS ->
                                        text.take(MAX_TOOL_OUTPUT_CHARS) + "\n[truncated]"
                                    else -> text
                                }
                                put("output", output.ifBlank { "[Tool returned no output]" })
                            }
                        })
                        fcSinceReasoning++
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            addContentItem(MessageRole.ASSISTANT, contentBuffer)
        }
    }

    private fun JsonArrayBuilder.addUserItems(message: UIMessage) {
        val contentParts = message.parts.filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
        if (contentParts.isNotEmpty()) {
            addContentItem(message.role, contentParts)
        }
    }

    private fun JsonArrayBuilder.addContentItem(role: MessageRole, parts: List<UIMessagePart>) {
        if (parts.isEmpty()) return

        add(buildJsonObject {
            put("role", JsonPrimitive(role.name.lowercase()))

            if (parts.isOnlyTextPart()) {
                put("content", (parts.first() as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "input_image")
                                        put("image_url", encodedImage.base64)
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "input_text")
                                        put("text", "Error: Failed to encode image to base64")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

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
}

private fun isModelAllowTemperature(model: Model): Boolean {
    return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
}

private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
    val texts = filter { it is UIMessagePart.Text }.size
    return gonnaSend == texts && texts == 1
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

