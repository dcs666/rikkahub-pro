package me.rerere.rikkahub.provider.providers.openai

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

// [拆分] Response API 请求体构建 + token 预算域（拆自 ResponseAPI.kt，Strangler Fig）

private const val CHARS_PER_TOKEN = 4
private const val COMPACTION_BUFFER = 20_000
private const val CONTEXT_LIMIT = 140_000
private const val PRUNE_PROTECT = 40_000
private const val PRUNE_MINIMUM = 20_000
private const val TAIL_TURNS = 2
private val PRUNE_PROTECTED_TOOLS = setOf("skill")
private const val ESTIMATE_CACHE_MAX_ENTRIES = 4096
private const val ESTIMATE_CACHE_MAX_KEY_LEN = 8192

internal object ResponseTokenBudget {
    private val estimateCache = HashMap<String, Int>()

    internal fun estimateTokens(part: UIMessagePart): Int {
        val text = when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Reasoning -> part.reasoning
            is UIMessagePart.Tool -> {
                val output = part.output.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                // 工具调用本身（input）+ 输出都计入（opencode tokens.total 含全部内容）
                part.input + "\n" + output
            }
            is UIMessagePart.ToolCall -> part.arguments
            // 图片以 data URL 内嵌时体积巨大，按字符估算计入（避免溢出判定偏小）
            is UIMessagePart.Image -> part.url
            else -> ""
        }
        if (text.length <= ESTIMATE_CACHE_MAX_KEY_LEN) {
            synchronized(estimateCache) {
                estimateCache[text]?.let { return it }
            }
        }
        var ascii = 0
        var nonAscii = 0
        for (ch in text) {
            if (ch.code <= 0x7F) ascii++ else nonAscii++
        }
        val result = ascii / CHARS_PER_TOKEN + nonAscii
        if (text.length <= ESTIMATE_CACHE_MAX_KEY_LEN) {
            synchronized(estimateCache) {
                if (estimateCache.size >= ESTIMATE_CACHE_MAX_ENTRIES) estimateCache.clear()
                estimateCache[text] = result
            }
        }
        return result
    }
    /**
     * [L3] prune 判定：照抄 opencode compaction.ts prune()——
     * 从最新往最旧遍历，最近 TAIL_TURNS(2) 个 user turn 完全保护；
     * 更早的工具输出按估算 token 累计，超过 PRUNE_PROTECT(40K) 的部分
     * 标记为待清空（发送时替换为 [Old tool result content cleared]）；
     * 仅当总清理量 > PRUNE_MINIMUM(20K) 才真正应用。skill 工具豁免。
     * 返回需要清空的 toolCallId 集合。
     */
    internal fun pruneOldToolOutputs(messages: List<UIMessage>): Set<String> {
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
}

internal fun buildRequestBody(
    providerSetting: ProviderSetting.OpenAI,
    messages: List<UIMessage>,
    params: TextGenerationParams,
    stream: Boolean
): JsonObject {
    val host = providerSetting.baseUrl.toHttpUrl().host
    val capabilities = resolveResponseProviderCapabilities(host)
    // [E1] 上下文窗口上限配置化：provider 可配 contextLimitTokens（null = 默认 140K）
    val contextLimit = providerSetting.contextLimitTokens ?: CONTEXT_LIMIT
    return buildJsonObject {
        put("model", params.model.modelId)
        put("stream", stream)
        // [增量] store=true 是增量（previous_response_id）的前提：服务端保存
        // response 才能关联上下文。opencode.ai 网关支持；其他 host 仅在用户
        // 显式开启 incrementalEnabled 时 store（有实测支持的网关才开）。
        put("store", host == "opencode.ai" || providerSetting.incrementalEnabled)

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
                contextLimit = contextLimit,
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

internal fun buildMessages(messages: List<UIMessage>,
    usePlainReasoningContent: Boolean = false,
    useReasoningTextArray: Boolean = false,
    forcePlaceholderReasoning: Boolean = false,
    contextLimit: Int = CONTEXT_LIMIT,
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
    // [E1] 上限来自 provider 配置 contextLimitTokens（默认 CONTEXT_LIMIT=140K）
    val totalTokens = filtered.sumOf { message -> message.parts.sumOf { ResponseTokenBudget.estimateTokens(it) } }
    val overflow = totalTokens >= contextLimit - COMPACTION_BUFFER
    // [L3] prune 判定（opencode compaction.ts prune()）：倒序遍历，最近 2 轮保护，
    // 更早工具输出累计估算超 PRUNE_PROTECT(40K) 的部分 → 清空为
    // [Old tool result content cleared]；清理量 > PRUNE_MINIMUM(20K) 才应用
    val clearedTools = ResponseTokenBudget.pruneOldToolOutputs(filtered)
    filtered.forEachIndexed { index, message ->
        if (message.role == MessageRole.ASSISTANT) {
            val hasReasoning = message.parts.any { it is UIMessagePart.Reasoning }
            if (hasReasoning) reasoningIndex++
            // [L1] 思维链增量发送（2026-08-10 实测：opencode 网关 deepseek 系
            // 把 reasoning item 仅作 schema 校验/回显，不参与推理上下文——
            // 密码实验：正文里的密码模型答得出，思维链里的密码模型答不出）。
            // 因此历史思维链无需全量回传：保留最近 4 条含思维链的消息完整
            // （用户要求 4 轮；追问"思路"时最新思考仍可用），更早的用占位符
            // （网关只校验非空，拒空串——占位符 '…' 已解决）。
            // 相比旧实现（仅 overflow 才压缩）：
            // ① 请求体大幅缩小（思维链是 200KB 请求体的大头 → 5-10 倍削减）
            // ② 占位符稳定 → 前缀缓存命中率更高（全量思维链每次长度不同）
            // ③ 对回答质量零影响（服务端本来就不读历史思维链）
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
