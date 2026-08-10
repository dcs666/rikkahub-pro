package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * [codex 借鉴] Responses API 增量发送（previous_response_id + store）。
 *
 * 与 openai/codex 的 get_incremental_items / prepare_websocket_request 设计一致：
 * - 服务端已知状态 = 上次发送的 input + 上次响应的 output items（服务端保存）
 * - 本次请求的 input 前缀必须与已知状态逐项相等，才可增量发送
 * - 非 input 请求属性（model/instructions/tools/reasoning/store/stream 等）必须
 *   与上次一致（对齐 codex responses_request_properties_match）——切换模型/
 *   修改思考档位/启用搜索等场景自动回退全量
 * - 增量 = input 的剩余部分 + previous_response_id
 *
 * 收益：历史消息（含超长 thinking 思维链）不再每轮全量重传——input 体积从
 * 几十~几百 KB 降到"仅新增消息"，请求延迟（网关解析/校验/转发）与 token 成本同步下降。
 *
 * 会话识别：App 请求无 conversationId 参数（避免大改调用链签名），
 * 用「input 首条消息 hash」分桶 + 桶内按已知状态前缀精确匹配（JsonElement == 结构化比较）。
 * 同一对话首条消息通常唯一；极端情况（多个对话首条相同）靠精确匹配保证正确性。
 *
 * 会话数量上限：超过上限整体清空（增量是性能优化，非正确性依赖；简单淘汰即可）。
 */
internal class IncrementalSessions {

    private data class Session(
        val previousResponseId: String,
        /** 上次发送的完整 input（App 构建顺序，与请求体 input 一致） */
        val sentInput: List<JsonElement>,
        /** 非 input 请求属性签名（对齐 codex responses_request_properties_match） */
        val requestSignature: String,
        val lastUsedAt: Long,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val maxSessions = 32

    /**
     * 尝试解析增量：返回 (previousResponseId, 增量 items)；无法增量时返回 (null, null)。
     */
    fun resolve(input: List<JsonElement>, requestBody: JsonObject): Pair<String?, List<JsonElement>?> {
        if (input.isEmpty()) return null to null
        val bucket = bucketKey(input)
        val session = sessions[bucket] ?: return null to null
        // [codex 对齐] 非 input 请求属性必须与上次一致（model/instructions/tools/
        // reasoning/store/stream 等），否则 previous_response_id 会沿用旧参数
        if (session.requestSignature != requestSignature(requestBody)) return null to null
        val prefix = session.sentInput
        if (input.size <= prefix.size) return null to null
        if (!itemsEqual(input.take(prefix.size), prefix)) return null to null
        val delta = input.drop(prefix.size)
        // [实测] opencode.ai 网关 previous_response_id 不支持 fco 引用之前的 fc
        //（增量带 fco 报 "No tool call found"），但支持"重发 fc+fco"（当新输入处理）：
        // 需要重建的 fc = delta 中的新 fc + sentInput 中"delta 有对应 fco"的历史 fc
        //（历史 fc 仅在其 fco 出现在增量里时才重发，避免无谓重传）；
        // 每个 fc 前必须补占位 reasoning（规则与全量一致——双 fc 无占位实测 400）。
        val deltaFcs = delta.filter { it.isFunctionCallItem() }
        val sentInputFcs = prefix.filter { it.isFunctionCallItem() }
        val needRebuildFcs = (deltaFcs + sentInputFcs.filter { fc ->
            delta.any { it.isFunctionCallOutputWithCallId(fc.callIdOrNull()) }
        }).distinctBy { it.callIdOrNull() }
        if (needRebuildFcs.isNotEmpty()) {
            val rebuilt = buildList {
                needRebuildFcs.forEach { fc ->
                    add(placeholderReasoningItem())
                    add(fc)
                    // fco 从完整历史（prefix + delta）按 call_id 匹配
                    val fco = (prefix + delta).firstOrNull {
                        it.isFunctionCallOutputWithCallId(fc.callIdOrNull())
                    }
                    if (fco != null) {
                        add(fco)
                    }
                }
                // 剩余新增：跳过 delta 里的 fc 与已重建的 fco，其余（新 user 消息等）保留
                delta.forEach { item ->
                    val isFc = item.isFunctionCallItem()
                    val isRebuiltFco = item.isFunctionCallOutput() &&
                        needRebuildFcs.any { it.callIdOrNull() == item.callIdOrNull() }
                    if (!isFc && !isRebuiltFco) {
                        add(item)
                    }
                }
            }
            return session.previousResponseId to rebuilt
        }
        return session.previousResponseId to delta
    }

    /**
     * 记录一次成功响应：sentInput = 本次完整 input（App 构建顺序）。
     */
    fun update(requestBody: JsonObject, responseId: String, responseItems: List<JsonElement>) {
        val input = requestBody["input"] as? JsonArray ?: return
        val items = input.toList()
        if (items.isEmpty() || responseId.isBlank()) return
        if (sessions.size >= maxSessions) {
            sessions.clear()
        }
        sessions[bucketKey(items)] = Session(
            previousResponseId = responseId,
            sentInput = items,
            requestSignature = requestSignature(requestBody),
            lastUsedAt = System.currentTimeMillis(),
        )
    }

    /**
     * 使指定会话失效（增量请求失败时调用，防止持续用无效 previous_response_id 重试）。
     */
    fun invalidate(input: List<JsonElement>) {
        if (input.isEmpty()) return
        sessions.remove(bucketKey(input))
    }

    /** 会话数量（测试用） */
    fun size(): Int = sessions.size

    private fun bucketKey(input: List<JsonElement>): String {
        val first = input.firstOrNull()?.toString().orEmpty()
        var hash = 1125899906842597L
        for (ch in first) {
            hash = 31 * hash + ch.code
        }
        return hash.toString()
    }

    private fun itemsEqual(a: List<JsonElement>, b: List<JsonElement>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i] != b[i]) return false
        }
        return true
    }

    private fun JsonElement.isFunctionCallItem(): Boolean {
        val obj = this as? JsonObject ?: return false
        return obj["type"]?.let { type ->
            (type as? JsonPrimitive)?.contentOrNull == "function_call"
        } ?: false
    }

    private fun JsonElement.isFunctionCallOutput(): Boolean {
        val obj = this as? JsonObject ?: return false
        return obj["type"]?.let { type ->
            (type as? JsonPrimitive)?.contentOrNull == "function_call_output"
        } ?: false
    }

    private fun JsonElement.isFunctionCallOutputWithCallId(callId: String?): Boolean {
        if (callId == null) return false
        val obj = this as? JsonObject ?: return false
        if (obj["type"]?.let { (it as? JsonPrimitive)?.contentOrNull } != "function_call_output") return false
        return obj["call_id"]?.let { (it as? JsonPrimitive)?.contentOrNull } == callId
    }

    private fun JsonElement.callIdOrNull(): String? {
        val obj = this as? JsonObject ?: return null
        return obj["call_id"]?.let { (it as? JsonPrimitive)?.contentOrNull }
    }

    private fun placeholderReasoningItem(): JsonObject = buildJsonObject {
        put("type", "reasoning")
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "reasoning_text")
                put("text", "…")
            })
        })
    }

    /**
     * [codex 对齐] 非 input 请求属性签名：与 responses_request_properties_match
     * 比较的字段一致（model/instructions/tools/tool_choice/parallel_tool_calls/
     * reasoning/store/stream/include，另加 App 实际使用的 temperature/max_output_tokens；
     * stream_options/client_metadata 是传输细节不影响上下文，忽略）。
     */
    private fun requestSignature(requestBody: JsonObject): String {
        val keys = listOf(
            "model", "instructions", "tools", "tool_choice", "parallel_tool_calls",
            "reasoning", "store", "stream", "include", "temperature", "max_output_tokens",
        )
        return keys.joinToString("|") { key -> requestBody[key]?.toString() ?: "" }
    }
}

/**
 * [codex 对齐] 发送前清理 input item 的服务端 id 字段（保留 call_id）：
 * codex 的 prepare_response_items_for_request 在发送前清除非 prefixed id——
 * App 构建的 input 本应无 id（reasoning 服务端 id 已在回传时移除），
 * 此处兜底防御，避免未来路径误带服务端 id 干扰网关解析。
 */
internal fun JsonArray.stripItemIds(): JsonArray = JsonArray(map { element ->
    val obj = element as? JsonObject ?: return@map element
    if (obj.containsKey("id")) {
        val cleaned = obj.toMutableMap().apply { remove("id") }
        JsonObject(cleaned)
    } else {
        element
    }
})
