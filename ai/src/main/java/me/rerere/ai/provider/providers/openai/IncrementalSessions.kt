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
 * 用「host + input 首条消息 hash」分桶 + 桶内按已知状态前缀精确匹配（JsonElement == 结构化比较）。
 * host 前缀防止不同网关间串用 previous_response_id（P1-4）。
 * 同一对话首条消息通常唯一；极端情况（多个对话首条相同）靠精确匹配保证正确性。
 *
 * 会话数量上限：LRU 淘汰最久未用（增量是性能优化，非正确性依赖）。
 */
internal class IncrementalSessions {

    private data class Session(
        val previousResponseId: String,
        /** 上次发送的完整 input（App 构建顺序，与请求体 input 一致） */
        val sentInput: List<JsonElement>,
        /** 上次响应的 output items（服务端已保存；用于过滤本次增量里的 assistant 回显） */
        val responseItems: List<JsonElement>,
        /** 非 input 请求属性签名（对齐 codex responses_request_properties_match） */
        val requestSignature: String,
        var lastUsedAt: Long,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val maxSessions = 32
    // [F10] 严格递增序号（而非时间戳）保证 LRU 顺序唯一（同毫秒内多次 update 也正确）
    private val clock = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 尝试解析增量：返回 (previousResponseId, 增量 items)；无法增量时返回 (null, null)。
     *
     * 规则（v1.8.10 收紧）：
     * - 工具轮次（delta 含 function_call / function_call_output）一律回退全量：
     *   opencode.ai 网关是"消息追加"语义，重发 fc+fco 会让服务端上下文里 fc 重复
     *   （上次 output 一次 + 本次 input 一次），多轮工具循环后上下文膨胀/模型困惑；
     *   增量收益主要在思维链重传，工具轮次全量发送更简单可靠（P0-1）。
     * - 纯文本轮次增量 = delta 中"排除上次 output 的 assistant 回显"后的剩余：
     *   App 会把上次回复回显进 input（UIMessage 历史），而服务端已保存该 output，
     *   不回显重发则服务端上下文不重复（P0-1 纯文本变体）。
     */
    fun resolve(host: String, input: List<JsonElement>, requestBody: JsonObject): Pair<String?, List<JsonElement>?> {
        if (input.isEmpty()) return null to null
        val bucket = bucketKey(host, input)
        val session = sessions[bucket] ?: return null to null
        // [codex 对齐] 非 input 请求属性必须与上次一致（model/instructions/tools/
        // reasoning/store/stream 等），否则 previous_response_id 会沿用旧参数
        if (session.requestSignature != requestSignature(requestBody)) return null to null
        val prefix = session.sentInput
        if (input.size <= prefix.size) return null to null
        if (!itemsEqual(input.take(prefix.size), prefix)) return null to null
        val delta = input.drop(prefix.size)
        // [F1] 工具轮次回退全量：增量带 fc/fco 会导致服务端上下文重复累积
        //（消息追加语义 + 重发 = fc 出现两次 + 占位 reasoning "…" 污染）
        if (delta.any { it.isToolItem() }) return null to null
        // [F11] 过滤服务端已有的 assistant 回显（上次 output 已保存，无需重发）：
        // message 回显 + reasoning 回显都过滤（reasoning 是思维链，体积最大，
        // 不过滤则增量退化为"思维链照样重传"，核心收益丢失）
        val filtered = delta.filterNot { item ->
            session.responseItems.any { known -> known.isEchoOf(item) }
        }
        if (filtered.isEmpty()) return null to null
        return session.previousResponseId to filtered
    }

    /**
     * 记录一次成功响应：sentInput = 本次完整 input（App 构建顺序），
     * responseItems = 本次输出 items（用于下次增量过滤 assistant 回显）。
     */
    fun update(host: String, requestBody: JsonObject, responseId: String, responseItems: List<JsonElement>) {
        val input = requestBody["input"] as? JsonArray ?: return
        val items = input.toList()
        if (items.isEmpty() || responseId.isBlank()) return
        val key = bucketKey(host, items)
        if (!sessions.containsKey(key) && sessions.size >= maxSessions) {
            // [F10] LRU：仅新会话触发淘汰（更新已有会话不误伤），淘汰最久未用
            val oldestKey = sessions.entries.minByOrNull { it.value.lastUsedAt }?.key
            if (oldestKey != null) sessions.remove(oldestKey)
        }
        sessions[key] = Session(
            previousResponseId = responseId,
            sentInput = items,
            responseItems = responseItems,
            requestSignature = requestSignature(requestBody),
            lastUsedAt = clock.incrementAndGet(),
        )
    }

    /** 使指定会话失效（增量请求失败时调用，防止持续用无效 previous_response_id 重试）。 */
    fun invalidate(host: String, input: List<JsonElement>) {
        if (input.isEmpty()) return
        sessions.remove(bucketKey(host, input))
    }

    /** 会话数量（测试用） */
    fun size(): Int = sessions.size

    private fun bucketKey(host: String, input: List<JsonElement>): String {
        val first = input.firstOrNull()?.toString().orEmpty()
        var hash = 1125899906842597L
        for (ch in first) {
            hash = 31 * hash + ch.code
        }
        return "$host|$hash"
    }

    private fun itemsEqual(a: List<JsonElement>, b: List<JsonElement>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i] != b[i]) return false
        }
        return true
    }

    private fun JsonElement.isToolItem(): Boolean {
        val obj = this as? JsonObject ?: return false
        val type = obj["type"] as? JsonPrimitive ?: return false
        val content = type.contentOrNull ?: return false
        return content == "function_call" || content == "function_call_output"
    }

    /**
     * 判断 delta 中的 item 是否是对"服务端已知 output item"的回显（应过滤）。
     * 按 type 分发：
     * - message：App 回显（无 type、content 纯字符串或数组）vs 服务端 output
     *   （type=message、content 数组）——按 role + 文本序列比较
     * - reasoning：App 回显（content 数组 reasoning_text 或明文）vs 服务端 output
     *   （type=reasoning、content 数组）——按 type + 文本序列比较
     * 其他类型（function_call 等）不参与（工具轮次已回退全量）。
     */
    private fun JsonElement.isEchoOf(known: JsonElement): Boolean {
        val knownObj = known as? JsonObject ?: return false
        val knownType = knownObj["type"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        return when (knownType) {
            "message" -> this.isSameAssistantMessage(known)
            "reasoning" -> this.isSameReasoningItem(known)
            else -> false
        }
    }

    /** 判断两个 item 是否是"同一条 assistant 消息"（App 回显 vs 服务端 output 结构不同：
     * App 无 type/id、content 可能是纯字符串，服务端有 type/id、content 是数组——
     * 按 role + 文本序列比较，忽略结构差异）。 */
    private fun JsonElement.isSameAssistantMessage(other: JsonElement): Boolean {
        val texts = this.assistantTexts() ?: return false
        val otherTexts = other.assistantTexts() ?: return false
        return texts == otherTexts
    }

    /** 判断两个 item 是否是"同一条 reasoning"（App 回显 vs 服务端 output） */
    private fun JsonElement.isSameReasoningItem(other: JsonElement): Boolean {
        val obj = this as? JsonObject ?: return false
        if (obj["type"]?.let { (it as? JsonPrimitive)?.contentOrNull } != "reasoning") return false
        val otherObj = other as? JsonObject ?: return false
        if (otherObj["type"]?.let { (it as? JsonPrimitive)?.contentOrNull } != "reasoning") return false
        val texts = this.contentTexts() ?: return false
        val otherTexts = other.contentTexts() ?: return false
        return texts == otherTexts
    }

    /** 提取 assistant 消息的文本序列；非 assistant 消息返回 null。 */
    private fun JsonElement.assistantTexts(): List<String>? {
        val obj = this as? JsonObject ?: return null
        if (obj["role"]?.let { (it as? JsonPrimitive)?.contentOrNull } != "assistant") return null
        return contentTexts()
    }

    /** 提取 content 字段的文本序列（纯字符串 → 单元素；数组 → 逐项 text） */
    private fun JsonElement.contentTexts(): List<String>? {
        val obj = this as? JsonObject ?: return null
        val content = obj["content"] ?: return emptyList()
        return when (content) {
            is JsonPrimitive -> listOf(content.content)
            is JsonArray -> content.mapNotNull { item ->
                (item as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.contentOrNull }
            }
            else -> null
        }
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
