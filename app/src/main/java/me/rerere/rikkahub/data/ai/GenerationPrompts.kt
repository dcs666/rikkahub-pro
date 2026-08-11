package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstantPretty

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        // [M3] 记忆分层加权注入：FACT（稳定事实）优先全量注入，PREFERENCE（偏好）次之，
        // SESSION（会话临时）最后。同层内最新在前（旧实现 takeLast = 最新 N 条）。
        // 总条数/总长封顶不变，超限时从最低优先级层开始截断。
        val tierOrder = listOf("FACT", "PREFERENCE", "SESSION")
        val byTier = memories.groupBy { it.category }
        val ordered = tierOrder.flatMap { tier ->
            byTier[tier].orEmpty().sortedByDescending { it.id }
        }
        val capped = ordered.take(MAX_MEMORY_INJECT_COUNT)
        val json = buildJsonArray {
            var total = 0
            for (memory in capped) {
                if (total >= MAX_MEMORY_INJECT_CHARS) break
                val content = memory.content.take((MAX_MEMORY_INJECT_CHARS - total).coerceAtLeast(0))
                total += content.length
                add(buildJsonObject {
                    put("id", memory.id)
                    put("category", memory.category)
                    put("content", content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

private const val MAX_MEMORY_INJECT_COUNT = 60
private const val MAX_MEMORY_INJECT_CHARS = 100_000
