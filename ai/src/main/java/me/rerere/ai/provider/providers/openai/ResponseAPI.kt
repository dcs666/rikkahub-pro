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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.common.android.Logging
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.buildEndpoint
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
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
internal const val REASONING_PLACEHOLDER = "…"

/** [L3] 工具输出回传上限：超长截断（对齐 opencode TOOL_OUTPUT_MAX_CHARS=2000 思路，取 2500） */
internal const val MAX_TOOL_OUTPUT_CHARS = 2500

/** 清空占位文本：opencode serialize() 对 compacted 工具输出用此文案 */
internal const val CLEARED_TOOL_OUTPUT = "[Old tool result content cleared]"

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


}

internal fun isModelAllowTemperature(model: Model): Boolean {
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

