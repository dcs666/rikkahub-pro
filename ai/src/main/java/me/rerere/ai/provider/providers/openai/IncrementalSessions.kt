package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
        /** 上次发送的完整 input（与请求体 input 一致） */
        val sentInput: List<JsonElement>,
        /** 上次响应的 output items（服务端已保存，无需重发） */
        val responseItems: List<JsonElement>,
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
        val prefix = session.sentInput + session.responseItems
        // [实测] opencode.ai 网关的 previous_response_id 只支持消息追加，
        // 不支持工具输出关联：增量里带 function_call_output 会报
        // "No tool call found for tool output with call_id ..."。
        // 已知状态含 function_call 时禁用增量（回退全量，工具循环不受影响）。
        if (session.responseItems.any { it.isFunctionCallItem() }) return null to null
        if (input.size <= prefix.size) return null to null
        if (!itemsEqual(input.take(prefix.size), prefix)) return null to null
        return session.previousResponseId to input.drop(prefix.size)
    }

    /**
     * 记录一次成功响应：sentInput = 本次完整 input，responseItems = 本次输出 items。
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
            responseItems = responseItems,
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
