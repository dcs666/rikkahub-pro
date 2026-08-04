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
        // [FIX] 记忆条数/总量上限：单条 20K 已有，但条数无上限时模型可塞入数百条 →
        // prompt 注入数十 MB → API 413/超时。注入侧兜底截断（保留最新 N 条、总长封顶）。
        val capped = memories.takeLast(MAX_MEMORY_INJECT_COUNT)
        val json = buildJsonArray {
            var total = 0
            for (memory in capped) {
                if (total >= MAX_MEMORY_INJECT_CHARS) break
                val content = memory.content.take((MAX_MEMORY_INJECT_CHARS - total).coerceAtLeast(0))
                total += content.length
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

private const val MAX_MEMORY_INJECT_COUNT = 60
private const val MAX_MEMORY_INJECT_CHARS = 100_000
