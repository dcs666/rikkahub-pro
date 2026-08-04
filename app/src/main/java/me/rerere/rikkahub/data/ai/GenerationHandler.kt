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
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.utils.applyPlaceholders
import org.koin.java.KoinJavaComponent.getKoin
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024
// [FIX] 工具级超时：任何工具（含未来新增的）都不能无限挂起生成循环。
private const val TOOL_EXECUTION_TIMEOUT_MS = 60_000L
// workspace_shell 自带命令超时（默认 30s，最大 600s）；工具级超时 = 命令超时 + 该缓冲，
// 避免误杀合法的长命令（构建/安装等显式设置了长 timeout 的场景）。
private const val SHELL_TOOL_TIMEOUT_BUFFER_MS = 15_000L
// [TURBO 并行] 同一轮多工具并行执行的并发上限：防模型一次返回过多工具时资源暴涨。
private const val TOOL_PARALLELISM = 4

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
) {
    private val filesManager: FilesManager by lazy { getKoin().get() }
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (assistant?.enableMemory == true) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    buildMemoryTools(
                        json = json,
                        onCreation = { content ->
                            memoryRepo.addMemory(memoryAssistantId, content)
                        },
                        onUpdate = { id, content ->
                            memoryRepo.updateContent(id, content)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        }
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            // [TURBO 并行] Auto/Approved 的工具收集到并行队列，forEach 结束后统一并行执行；
            // Denied/Answered/Pending 仍同步处理（快，不涉及执行）。
            val toExecuteParallel = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - 入队并行执行（见下方 coroutineScope 块）
                        toExecuteParallel += tool
                    }
                }
            }

            // [TURBO 并行] 同一轮多个 Auto/Approved 工具并行执行（OpenAI parallel function
            // calling 语义：模型同轮返回的多个 tool_calls 即视为可并行）。
            // - 结果按调用顺序挂回（awaitAll 保持 deferreds 顺序），消息顺序与串行一致
            // - 并发上限 TOOL_PARALLELISM（Semaphore），防模型一次返回过多工具时资源暴涨
            // - 单工具超时/异常已被 executeToolSafely 消化为工具错误输出，不影响兄弟任务；
            //   而 CancellationException 向上传播 → coroutineScope 取消全部并行任务，
            //   与串行时"用户停止生成"的取消语义一致
            if (toExecuteParallel.isNotEmpty()) {
                coroutineScope {
                    val semaphore = Semaphore(TOOL_PARALLELISM)
                    val deferreds = toExecuteParallel.map { tool ->
                        async {
                            semaphore.withPermit {
                                executeToolSafely(tool, toolsInternal)
                            }
                        }
                    }
                    deferreds.awaitAll().forEach { executedTools += it }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }

    }.flowOn(Dispatchers.IO)

    /**
     * [TURBO 并行] 单工具安全执行（供并行队列调用）：
     * - 工具级超时：workspace_shell 按命令自身 timeout + 缓冲，其余工具统一 60s
     * - TimeoutCancellationException 消化为工具错误输出（不是取消生成）
     * - 其它异常同样消化为工具错误输出，不中断并行中的兄弟任务
     * - CancellationException 原样传播（用户停止生成 → coroutineScope 取消全部）
     */
    private suspend fun executeToolSafely(
        tool: UIMessagePart.Tool,
        toolsInternal: List<Tool>,
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
                output = maybeTruncateToolOutput(tool.toolCallId, convertedResult, hasShellAccess)
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

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
    ) {
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            addAll(messages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            // [TURBO] 流式 text O(n²) 缓解。长回答"reasoning 完成后 text 暴涨"阶段，原逻辑每个网络
            // token 都跑 appendChunk 的 `lastText + delta`（O(n) 复制整段 text），在 IO 线程累计 O(n²)，
            // 表现为长回答末段出字变慢 + GC 压力。优化：该阶段用 StringBuilder 累积 text（每 token
            // O(1) append），降频 ~30fps 才 snapshot 回末尾消息的 text 槽。仅当"末尾 part 是 Text 且本帧
            // delta 是单个纯 Text 且 role 匹配且无 usage/annotations"时启用 text-only 累积器；其余一律
            // fallback 原 handleMessageChunk（tool/role 切换/reasoning/image/usage/annotations/首帧正确性
            // 不变）。text-only 累积器只镜像 appendChunk 的"acc 末尾是 Text 则追加"这一条分支，故纯 text
            // 流式场景结果与原逻辑逐条等价，仅降低复制频率。不动 UIMessage 不可变模型。
            var textBuf: StringBuilder? = null
            var textBaseParts: List<UIMessagePart>? = null
            var textMeta: JsonObject? = null
            var lastFlush = 0L

            fun flushText() {
                val buf = textBuf ?: return
                val base = textBaseParts ?: return
                val last = messages.last()
                val newParts = base + UIMessagePart.Text(text = buf.toString(), metadata = textMeta)
                messages = ArrayList<UIMessage>(messages.size).apply {
                    for (i in 0 until messages.size - 1) add(messages[i])
                    add(last.copy(parts = newParts))
                }
                textBuf = null
                textBaseParts = null
            }

            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = params
            ).collect { chunk ->
                val choice = chunk.choices.getOrNull(0)
                val delta = choice?.delta ?: choice?.message
                val lastParts = messages.lastOrNull()?.parts
                val lastIsText = lastParts?.lastOrNull() is UIMessagePart.Text
                val deltaTextPart = (delta?.parts?.singleOrNull() as? UIMessagePart.Text)
                val canAbsorbText = deltaTextPart != null &&
                    deltaTextPart.metadata == null &&
                    chunk.usage == null &&
                    (delta?.annotations?.isEmpty() == true) &&
                    delta?.role == messages.lastOrNull()?.role &&
                    lastIsText

                if (canAbsorbText && deltaTextPart != null) {
                    val dt = deltaTextPart.text
                    if (dt.isNotEmpty()) {
                        val buf = textBuf
                        if (buf == null) {
                            val lp = messages.last().parts
                            val lastText = lp.last() as UIMessagePart.Text
                            textBaseParts = lp.subList(0, lp.size - 1)
                            textMeta = lastText.metadata
                            textBuf = StringBuilder(lastText.text).append(dt)
                        } else {
                            buf.append(dt)
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFlush >= 32L) {
                            flushText()
                            onUpdateMessages(messages)
                            lastFlush = now
                        }
                    }
                } else {
                    flushText()
                    messages = messages.handleMessageChunk(chunk = chunk, model = model)
                    chunk.usage?.let { usage ->
                        messages = messages.mapIndexed { index, message ->
                            if (index == messages.lastIndex) {
                                message.copy(usage = message.usage.merge(usage))
                            } else {
                                message
                            }
                        }
                    }
                    onUpdateMessages(messages)
                    lastFlush = SystemClock.elapsedRealtime()
                }
            }
            flushText()
            onUpdateMessages(messages)
        } else {
            val chunk = providerImpl.generateText(
                providerSetting = provider,
                messages = internalMessages,
                params = params,
            )
            messages = messages.handleMessageChunk(chunk = chunk, model = model)
            chunk.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(
                            usage = message.usage.merge(usage)
                        )
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(messages)
        }
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
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

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
