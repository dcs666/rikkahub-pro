package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.handleMessageChunk
import android.os.SystemClock
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import java.io.File
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory

// [拆分] 工具执行与输出截断域（拆自 GenerationHandler.kt，Strangler Fig）
private const val TAG = "GenerationToolExecutor"

// [FIX] 工具级超时：任何工具（含未来新增的）都不能无限挂起生成循环。
internal const val TOOL_EXECUTION_TIMEOUT_MS = 60_000L
// workspace_shell 自带命令超时（默认 30s，最大 600s）；工具级超时 = 命令超时 + 该缓冲，
// 避免误杀合法的长命令（构建/安装等显式设置了长 timeout 的场景）。
internal const val SHELL_TOOL_TIMEOUT_BUFFER_MS = 15_000L
internal const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
internal const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

internal suspend fun executeToolSafely(
        tool: UIMessagePart.Tool,
    toolsInternal: List<Tool>,
    json: Json,
    filesManager: FilesManager,
    context: Context,
    ): UIMessagePart.Tool {
        var timeoutMs = TOOL_EXECUTION_TIMEOUT_MS
        try {
            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                ?: error("Tool ${tool.toolName} not found")
            val args = runCatching {
                json.parseToJsonElement(tool.input.ifBlank { "{}" })
            }.getOrElse {
                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
            }
            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
            timeoutMs = if (toolDef.name == "workspace_shell") {
                val explicit = runCatching {
                    (args as? JsonObject)
                        ?.get("timeout")?.jsonPrimitive?.content?.toLongOrNull()
                }.getOrNull()
                (explicit ?: 30L).coerceIn(1L, 600L) * 1000L + SHELL_TOOL_TIMEOUT_BUFFER_MS
            } else {
                TOOL_EXECUTION_TIMEOUT_MS
            }
            val result = withTimeout(timeoutMs) { toolDef.execute(args) }
            val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
            // [FIX] 工具返回的 base64 图片在注入消息前转本地文件：原转换链只覆盖模型输出
            // （ChatService.outputTransformers），MCP 截图/图像工具返回 data: URL 时会带着
            // base64 进消息 → saveConversation 的 require(!hasBase64Part) 抛异常 → 整条消息
            // 保存失败（重启后丢失）。这里对工具结果做同样的转换。
            val convertedResult = filesManager.convertBase64ImagePartsToLocalFile(result)
            return tool.copy(
                output = maybeTruncateToolOutput(tool.toolCallId, convertedResult, hasShellAccess, context)
            )
        } catch (e: TimeoutCancellationException) {
            return tool.copy(
                output = listOf(
                    UIMessagePart.Text(
                        json.encodeToString(
                            buildJsonObject {
                                put(
                                    "error",
                                    JsonPrimitive(
                                        "Tool ${tool.toolName} timed out after ${timeoutMs / 1000}s. " +
                                            "If this is a long-running shell command, increase the 'timeout' parameter."
                                    )
                                )
                            }
                        )
                    )
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            return tool.copy(
                output = listOf(
                    UIMessagePart.Text(
                        json.encodeToString(
                            buildJsonObject {
                                put(
                                    "error",
                                    JsonPrimitive(buildString {
                                        append("[${e.javaClass.name}] ${e.message}")
                                        append("\n${e.stackTraceToString()}")
                                    })
                                )
                            }
                        )
                    )
                )
            )
        }
    }

internal fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
    hasShellAccess: Boolean,
    context: Context,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        // [FIX] toolCallId 由模型提供（外部可控），直接拼文件名可含 "/" 或 "../" →
        // 逃逸 tool_outputs 目录写入 app 私有目录其他位置（父目录存在时静默覆盖）。
        val safeFileName = toolCallId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val fileName = "$safeFileName.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }
