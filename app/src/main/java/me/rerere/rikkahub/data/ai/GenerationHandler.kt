package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

/** 翻译输入长度上限（与文档注入截断一致） */
private const val MAX_TRANSLATE_INPUT_CHARS = 200_000
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

        // [PERF] 工具集在生成循环中不变（只依赖 assistant/tools，均为循环外常量），
        // 原实现每次 step 迭代都重建（含 buildMemoryTools 的对象/lambda 创建）→ 移出循环构建一次
        val toolsInternal = buildList {
            if (assistant?.enableMemory == true) {
                val memoryAssistantId = if (assistant.useGlobalMemory) {
                    MemoryRepository.GLOBAL_MEMORY_ID
                } else {
                    assistant.id.toString()
                }
                buildMemoryTools(
                    json = json,
                    onCreation = { content, category ->
                        memoryRepo.addMemory(memoryAssistantId, content, category)
                    },
                    onUpdate = { id, content, category ->
                        memoryRepo.updateContent(id, content, category)
                    },
                    onDelete = { id ->
                        memoryRepo.deleteMemory(id)
                    }
                ).let(this::addAll)
            }
            addAll(tools)
        }

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

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
                                executeToolSafely(tool, toolsInternal, json, filesManager, context)
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
            // [TURBO] 流式末尾 chunk 的 finish_reason=length/max_tokens 表示服务端已达
            // max_tokens 截断。delta 常为空（finish chunk），原链路静默丢弃，用户无从得知
            // 回答不完整。收集流结束、文本累积器 flush 后统一追加截断提示。
            var truncationReason: String? = null

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
                if (choice?.finishReason.isTruncationReason()) {
                    truncationReason = choice?.finishReason
                }
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
            truncationReason?.let { reason ->
                messages = messages.appendTruncationNotice(reason)
                Log.w(TAG, "streamText: answer truncated by server (finish_reason=$reason), notice appended")
            }
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
            chunk.choices.firstOrNull()?.finishReason?.let { reason ->
                if (reason.isTruncationReason()) {
                    messages = messages.appendTruncationNotice(reason)
                    Log.w(TAG, "generateText: answer truncated by server (finish_reason=$reason), notice appended")
                }
            }
            onUpdateMessages(messages)
        }
    }

    /**
     * [TURBO] 服务端 finish_reason 为截断类（length / max_tokens / MAX_TOKENS）时，
     * 在最后一条 assistant 消息末尾追加可见提示块。带 metadata 标记，UI 可识别为警示条；
     * 幂等（同消息已有提示则不重复追加，避免重试/继续生成时叠加）。
     */
    private fun List<UIMessage>.appendTruncationNotice(reason: String): List<UIMessage> {
        val last = lastOrNull() ?: return this
        if (last.role != MessageRole.ASSISTANT) return this
        if (last.parts.any { part ->
                part.metadata?.get("truncatedNotice")?.jsonPrimitive?.contentOrNull == "true"
            }
        ) {
            return this
        }
        val notice = "\n\n> ⚠️ 回答已达 max_tokens 上限被截断，内容可能不完整（finish_reason=$reason）"
        return dropLast(1) + last.copy(
            parts = last.parts + UIMessagePart.Text(
                text = notice,
                metadata = buildJsonObject {
                    put("truncatedNotice", JsonPrimitive(true))
                }
            )
        )
    }

    private fun String?.isTruncationReason(): Boolean = this != null && (
        equals("length", ignoreCase = true) ||
            equals("max_tokens", ignoreCase = true)
        )


    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        // [FIX] 翻译输入无长度上限：超长文本（粘贴几十万字符）会构造超大 prompt →
        // API 413/超时。与文档注入截断（200K）保持一致。
        val cappedSource = sourceText.take(MAX_TRANSLATE_INPUT_CHARS)
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to cappedSource,
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
            val messages = listOf(UIMessage.user(cappedSource))
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
