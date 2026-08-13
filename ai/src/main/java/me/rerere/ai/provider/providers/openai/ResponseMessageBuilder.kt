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
import me.rerere.ai.ui.isUiNotice
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

/**
 * [拆分] ResponseAPI 的消息构建域：将 UIMessage 组装为 Responses API 的
 * input items 数组（assistant reasoning/content/function_call 分组回传、
 * user 多模态 content 数组）。纯扩展函数，从 ResponseAPI.kt 提取。
 */

    internal fun JsonArrayBuilder.addAssistantItems(
        message: UIMessage,
        usePlainReasoningContent: Boolean = false,
        useReasoningTextArray: Boolean = false,
        forcePlaceholderReasoning: Boolean = false,
        keepReasoning: Boolean = true,
        keepToolOutput: Boolean = true,
        overflow: Boolean = false,
        clearedTools: Set<String> = emptySet(),
        opencodeStrict: Boolean = false,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()
        // Console Go 网关（opencode.ai）thinking mode：带工具调用的 assistant 消息必须
        // 回传非空 reasoning_text；历史消息未捕获思维链时用占位符补上
        var reasoningEmitted = false
        // [FIX] 网关按 function_call 逐个校验 reasoning：1 个 reasoning item 只"服务"其
        // 后的第 1 个 fc；同一条消息内第 2 个 fc 起（或跨 Tools group）前面没有 reasoning
        // 会 400「The reasoning_text in the thinking mode must be passed back to the API」
        //（实测：占位符可接受、assistant content 不能替代、reasoning 补在 fc 之后无效）。
        // 用计数器跟踪"距上次 reasoning 输出后已输出的 fc 数"，>0 时在下一个 fc 前补占位。
        var fcSinceReasoning = 0
        // [C1-FIX] 同一 assistant 消息可能产生多个 content/reasoning/image item
        //（文本缓冲被图片/推理/工具组打断后多次 flush），id 若仅基于 message.id
        // 会生成重复顶层 id。Console Go 上游按 id 反序列化输入数组，重复 id 有
        // 覆盖/被拒风险（此前 msg_<id> 复用、多图 img_<id> 复用、多段真实
        // reasoning rsn_<id> 复用）。用消息内自增序号保证唯一：
        // msg_<id>_N / rsn_<id>_N / img_<id>_N。
        // fc/fco 的 id 不受此约束（toolCallId 天然唯一，且 fc.id 必须 == fco.call_id）。
        var itemSeq = 0
        fun nextItemId(prefix: String) = "${prefix}_${message.id}_${itemSeq++}"

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Reasoning -> {
                                // 先输出累积的文本/图片内容
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer, nextItemId("msg"), opencodeStrict)
                                    contentBuffer.clear()
                                }
                                // 输出 reasoning item
                                val reasoningMetadata = part.metadataAs<OpenAIReasoningMetadata>()
                                add(buildJsonObject {
                                    put("type", "reasoning")
                                    // [FIX] Console Go 上游要求 input items 带顶层 id
                                    //（2026-08-13 实测：缺 id 400 "missing field id"）
                                    if (opencodeStrict) put("id", nextItemId("rsn"))
                                    // [codex 经验] 回传 input 时不带服务端生成的 item id：
                                    // openai/codex 的 prepare_response_items_for_request 发送前
                                    // 清除所有非 prefixed id（服务端按位置/内容重建关联），
                                    // 避免网关对已过期 id 做状态校验。OpenAI 官方同样接受无 id。
                                    when {
                                        usePlainReasoningContent -> {
                                            // DeepSeek Responses API 不支持 summary 字段，用明文 content 回传思维链；
                                            // [L1] 旧轮次思维链用占位符（网关只校验非空）
                                            put("content", if (keepReasoning) part.reasoning else REASONING_PLACEHOLDER)
                                        }

                                        useReasoningTextArray -> {
                                            // OpenCode Zen 网关（Console provider）thinking mode 要求
                                            // content 数组的 reasoning_text 类型回传；思维链为空时用
                                            // 占位符（实测空字符串会被网关拒绝）；
                                            // [L1] 旧轮次思维链同样用占位符（保留 item 结构满足校验）
                                            val text = if (keepReasoning) {
                                                part.reasoning.ifBlank { REASONING_PLACEHOLDER }
                                            } else {
                                                REASONING_PLACEHOLDER
                                            }
                                            put("content", buildJsonArray {
                                                add(buildJsonObject {
                                                    put("type", "reasoning_text")
                                                    put("text", text)
                                                })
                                            })
                                        }

                                        else -> {
                                            put("summary", buildJsonArray {
                                                add(buildJsonObject {
                                                    put("type", "summary_text")
                                                    put("text", if (keepReasoning) part.reasoning else REASONING_PLACEHOLDER)
                                                })
                                            })
                                        }
                                    }
                                    // encrypted_content 仅 OpenAI 官方支持：DeepSeek / OpenCode Zen
                                    // 网关均不接受（能力矩阵 supportEncryptedContent=false），
                                    // 回传会触发上游校验错误 → 仅 summary 分支（官方）回传；
                                    // [L1] 旧轮次思维链已占位，不匹配的 encrypted_content 不再回传
                                    if (keepReasoning && !usePlainReasoningContent && !useReasoningTextArray) {
                                        reasoningMetadata?.encryptedContent?.let {
                                            put("encrypted_content", it)
                                        }
                                    }
                                })
                                // 无论用哪种格式回传，只要 reasoning item 已输出（且文本非空，
                                // useReasoningTextArray 分支已用占位符兜底），标记本消息已带思维链
                                reasoningEmitted = true
                                fcSinceReasoning = 0
                            }

                            is UIMessagePart.Image -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer, nextItemId("msg"), opencodeStrict)
                                    contentBuffer.clear()
                                }
                                addContentItem(MessageRole.USER, listOf(part), nextItemId("img"), opencodeStrict)
                            }

                            is UIMessagePart.Text -> {
                                // [UI-ONLY] uiNotice 标记的警示条（截断/打断）不回传，
                                // 避免污染模型历史上下文
                                if (!part.isUiNotice) contentBuffer.add(part)
                            }

                            else -> {}
                        }
                    }
                }

                is PartGroup.Tools -> {
                    // 先输出累积的内容
                    if (contentBuffer.isNotEmpty()) {
                        addContentItem(MessageRole.ASSISTANT, contentBuffer, nextItemId("msg"), opencodeStrict)
                        contentBuffer.clear()
                    }

                    // Console Go 网关（opencode.ai）thinking mode：带工具调用的 assistant 消息
                    // 必须回传非空 reasoning_text。历史消息若未捕获到思维链（如开启思考模式前
                    // 产生的工具消息），补占位符 reasoning item，否则网关 400（错误：The
                    // reasoning_text in the thinking mode must be passed back to the API）
                    if (forcePlaceholderReasoning && !reasoningEmitted) {
                        add(buildJsonObject {
                            put("type", "reasoning")
                            if (opencodeStrict) put("id", nextItemId("rsn"))
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "reasoning_text")
                                    put("text", REASONING_PLACEHOLDER)
                                })
                            })
                        })
                        reasoningEmitted = true
                        fcSinceReasoning = 0
                    }

                    // 输出 function_call + function_call_output
                    group.tools.forEach { tool ->
                        // [FIX] 网关按 fc 逐个校验：1 个 reasoning 只服务其后的第 1 个 fc，
                        // 同组第 2 个 fc 起（以及跨 Tools group 的下一个 fc）前面没有
                        // reasoning 会 400（占位符可接受、assistant content 不能替代）。
                        if (forcePlaceholderReasoning && fcSinceReasoning > 0) {
                            add(buildJsonObject {
                                put("type", "reasoning")
                                if (opencodeStrict) put("id", nextItemId("rsn"))
                                put("content", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "reasoning_text")
                                        put("text", REASONING_PLACEHOLDER)
                                    })
                                })
                            })
                            fcSinceReasoning = 0
                        }
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tool.toolCallId)
                            // [FIX] Console Go 上游（opencode.ai）配对规则：function_call
                            // 项的顶层 id 会被网关用作上游 tool_call 的 id，必须与
                            // function_call_output 的 call_id 一致，否则 400
                            // "tool_call_ids did not have response messages"（2026-08-13 实测）
                            if (opencodeStrict) put("id", tool.toolCallId)
                            put("name", tool.toolName)
                            // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
                            put("arguments", tool.inputAsJson().toString())
                        })
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", tool.toolCallId)
                            // Console Go 上游同样要求 fco 项带顶层 id（实测缺失会卡反序列化）
                            if (opencodeStrict) put("id", "out_${tool.toolCallId}")
                            val hasImage = tool.output.any { it is UIMessagePart.Image }
                            if (tool.toolCallId in clearedTools) {
                                // [L3-FIX] prune 清空优先于一切（含图片输出）：
                                // opencode serialize 对 compacted 工具统一替换
                                // [Old tool result content cleared]——图片 base64 可能
                                // 巨大，必须同样清空，否则绕过体积控制
                                put("output", CLEARED_TOOL_OUTPUT)
                            } else if (hasImage) {
                                putJsonArray("output") {
                                    tool.output.forEach { part ->
                                        when (part) {
                                            is UIMessagePart.Image -> add(buildJsonObject {
                                                part.encodeBase64().onSuccess { encoded ->
                                                    put("type", "input_image")
                                                    put("image_url", encoded.base64)
                                                }.onFailure {
                                                    it.printStackTrace()
                                                    put("type", "input_text")
                                                    put("text", "Error: Failed to encode image to base64")
                                                }
                                            })
                                            is UIMessagePart.Text -> add(buildJsonObject {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            })
                                            else -> {}
                                        }
                                    }
                                }
                            } else {
                                // [FIX] 空输出时给占位文本，避免提供商拒绝空 output
                                // [L3] 工具输出处理（照抄 opencode compaction 语义，优先级从高到低）：
                                // ① prune 清空：超预算的旧工具输出整体替换为
                                //    [Old tool result content cleared]（opencode prune()）
                                // ② overflow 截断：对话估算总 token 接近上下文上限时，
                                //    非最近 2 轮的超长输出截断（opencode serialize truncate）
                                // ③ 完整保留：最近 2 轮 / 未 overflow 时不截断
                                val text = tool.output.filterIsInstance<UIMessagePart.Text>()
                                    .joinToString("\n") { it.text }
                                val output = when {
                                    tool.toolCallId in clearedTools -> CLEARED_TOOL_OUTPUT
                                    !keepToolOutput && overflow && text.length > MAX_TOOL_OUTPUT_CHARS ->
                                        text.take(MAX_TOOL_OUTPUT_CHARS) + "\n[truncated]"
                                    else -> text
                                }
                                put("output", output.ifBlank { "[Tool returned no output]" })
                            }
                        })
                        fcSinceReasoning++
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            addContentItem(MessageRole.ASSISTANT, contentBuffer, nextItemId("msg"), opencodeStrict)
        }
    }

    internal fun JsonArrayBuilder.addUserItems(message: UIMessage, opencodeStrict: Boolean = false) {
        val contentParts = message.parts.filter { (it is UIMessagePart.Text || it is UIMessagePart.Image) && !it.isUiNotice }
        if (contentParts.isNotEmpty()) {
            addContentItem(message.role, contentParts, "msg_${message.id}", opencodeStrict)
        }
    }

    internal fun JsonArrayBuilder.addContentItem(
        role: MessageRole,
        parts: List<UIMessagePart>,
        itemId: String? = null,
        opencodeStrict: Boolean = false,
    ) {
        if (parts.isEmpty()) return

        add(buildJsonObject {
            put("role", JsonPrimitive(role.name.lowercase()))
            // [FIX] Console Go 上游要求 input items 带顶层 id（2026-08-13 实测）
            if (opencodeStrict) itemId?.let { put("id", it) }

            if (parts.isOnlyTextPart()) {
                put("content", (parts.first() as UIMessagePart.Text).text)
            } else if (opencodeStrict && parts.all { it is UIMessagePart.Text }) {
                // Console Go 上游（deepseek-v4-pro）要求 assistant content 为纯字符串，
                // content 数组会被拒（"content or tool_calls must be set"，实测）；
                // 多文本 part 用换行合并（flash 后端同样接受字符串，T3 实测）
                put("content", parts.joinToString("\n") { (it as UIMessagePart.Text).text })
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "input_image")
                                        put("image_url", encodedImage.base64)
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "input_text")
                                        put("text", "Error: Failed to encode image to base64")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }


internal fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
    val texts = filter { it is UIMessagePart.Text }.size
    return gonnaSend == texts && texts == 1
}
