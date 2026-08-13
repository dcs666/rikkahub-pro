package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isUiNotice
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.removeElements

// [拆分] Google 请求体构建域（拆自 GoogleProvider，Strangler Fig）。
// 纯函数：buildCompletionRequestBody + contents 构建（无类状态依赖）。

internal fun buildCompletionRequestBody(
    messages: List<UIMessage>,
    params: TextGenerationParams
): JsonObject = buildJsonObject {
    // System message if available
    val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
    if (systemMessage != null && !params.model.outputModalities.contains(Modality.IMAGE)) {
        put("systemInstruction", buildJsonObject {
            putJsonArray("parts") {
                add(buildJsonObject {
                    put(
                        "text",
                        systemMessage.parts.filterIsInstance<UIMessagePart.Text>()
                            .joinToString { it.text })
                })
            }
        })
    }

    // Generation config
    put("generationConfig", buildJsonObject {
        if (params.temperature != null) put("temperature", params.temperature)
        if (params.topP != null) put("topP", params.topP)
        if (params.maxTokens != null) put("maxOutputTokens", params.maxTokens)
        if (params.model.outputModalities.contains(Modality.IMAGE)) {
            put("responseModalities", buildJsonArray {
                add(JsonPrimitive("TEXT"))
                add(JsonPrimitive("IMAGE"))
            })
        }
        if (params.model.abilities.contains(ModelAbility.REASONING)) {
            put("thinkingConfig", buildJsonObject {
                put("includeThoughts", true)

                val isGeminiPro =
                    params.model.modelId.contains(Regex("2\\.5.*pro", RegexOption.IGNORE_CASE))

                when (params.reasoningLevel) {
                    ReasoningLevel.AUTO -> {} // 自动模式，不设置参数

                    ReasoningLevel.OFF -> {
                        if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                            put("thinkingLevel", "minimal")
                        } else if (!isGeminiPro) {
                            put("thinkingBudget", 0)
                            put("includeThoughts", false)
                        }
                    }

                    else -> {
                        if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                            when (params.reasoningLevel) {
                                ReasoningLevel.LOW -> put("thinkingLevel", "low")
                                ReasoningLevel.MEDIUM -> put("thinkingLevel", "medium")
                                else -> put("thinkingLevel", "high") // HIGH, XHIGH
                            }
                        } else {
                            put("thinkingBudget", params.reasoningLevel.budgetTokens)
                        }
                    }
                }
            })
        }
    })

    // Contents (user messages)
    put(
        "contents",
        buildContents(messages)
    )

    // Tools
    if (params.tools.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL)) {
        put("tools", buildJsonArray {
            add(buildJsonObject {
                put("functionDeclarations", buildJsonArray {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", JsonPrimitive(tool.name))
                            put("description", JsonPrimitive(tool.description))
                            put(
                                key = "parameters",
                                element = json.encodeToJsonElement(tool.parameters())
                                    .removeElements(
                                        listOf(
                                            "const",
                                            "exclusiveMaximum",
                                            "exclusiveMinimum",
                                            "format",
                                            "additionalProperties",
                                            "enum",
                                        )
                                    )
                            )
                        })
                    }
                })
            })
        })
    }
    // Model BuiltIn Tools
    // 目前不能和工具调用兼容
    if (params.model.tools.isNotEmpty()) {
        put("tools", buildJsonArray {
            params.model.tools.forEach { builtInTool ->
                when (builtInTool) {
                    BuiltInTools.Search -> {
                        add(buildJsonObject {
                            put("googleSearch", buildJsonObject {})
                        })
                    }

                    BuiltInTools.UrlContext -> {
                        add(buildJsonObject {
                            put("urlContext", buildJsonObject {})
                        })
                    }

                    else -> {}
                }
            }
        })
    }

    // Safety Settings
    putJsonArray("safetySettings") {
        add(buildJsonObject {
            put("category", "HARM_CATEGORY_HARASSMENT")
            put("threshold", "OFF")
        })
        add(buildJsonObject {
            put("category", "HARM_CATEGORY_HATE_SPEECH")
            put("threshold", "OFF")
        })
        add(buildJsonObject {
            put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT")
            put("threshold", "OFF")
        })
        add(buildJsonObject {
            put("category", "HARM_CATEGORY_DANGEROUS_CONTENT")
            put("threshold", "OFF")
        })
        add(buildJsonObject {
            put("category", "HARM_CATEGORY_CIVIC_INTEGRITY")
            put("threshold", "OFF")
        })
    }
}.mergeCustomBody(params.customBody)

internal fun commonRoleToGoogleRole(role: MessageRole): String {
    return when (role) {
        MessageRole.USER -> "user"
        MessageRole.SYSTEM -> "system"
        MessageRole.ASSISTANT -> "model"
        MessageRole.TOOL -> "user" // google api中, tool结果是用户role发送的
    }
}

internal fun buildContents(messages: List<UIMessage>): JsonArray {
    return buildJsonArray {
        messages
            .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addModelMessage(message)
                } else {
                    addUserMessage(message)
                }
            }
    }
}

internal fun JsonArrayBuilder.addModelMessage(message: UIMessage) {
    val groups = groupPartsByToolBoundary(message.parts)
    val partsBuffer = mutableListOf<JsonObject>()

    for (group in groups) {
        when (group) {
            is PartGroup.Content -> {
                group.parts.mapNotNull { it.toGooglePart() }.forEach { partsBuffer.add(it) }
            }

            is PartGroup.Tools -> {
                // 添加 functionCall 到 parts 缓冲
                group.tools.forEach { partsBuffer.add(it.toFunctionCallPart()) }

                // 输出 model 消息
                add(buildJsonObject {
                    put("role", "model")
                    putJsonArray("parts") { partsBuffer.forEach { add(it) } }
                })
                partsBuffer.clear()

                // 紧跟 functionResponse
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        group.tools.forEach { add(it.toFunctionResponsePart()) }
                    }
                })
            }
        }
    }

    // 输出剩余内容
    if (partsBuffer.isNotEmpty()) {
        add(buildJsonObject {
            put("role", "model")
            putJsonArray("parts") { partsBuffer.forEach { add(it) } }
        })
    }
}

internal fun JsonArrayBuilder.addUserMessage(message: UIMessage) {
    add(buildJsonObject {
        put("role", commonRoleToGoogleRole(message.role))
        putJsonArray("parts") {
            message.parts.mapNotNull { it.toGooglePart() }.forEach { add(it) }
        }
    })
}

internal fun UIMessagePart.toGooglePart(): JsonObject? = when (this) {
    is UIMessagePart.Text -> if (isUiNotice) null else buildJsonObject {
        put("text", text)
    }

    is UIMessagePart.Image -> {
        encodeBase64(false).getOrNull()?.let { encoded ->
            buildJsonObject {
                put("inlineData", buildJsonObject {
                    put("mimeType", encoded.mimeType)
                    put("data", encoded.base64)
                })
                metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
                    put("thoughtSignature", it)
                }
            }
        }
    }

    is UIMessagePart.Video -> {
        encodeBase64(false).getOrNull()?.let { base64Data ->
            buildJsonObject {
                put("inlineData", buildJsonObject {
                    put("mimeType", "video/mp4")
                    put("data", base64Data)
                })
            }
        }
    }

    is UIMessagePart.Audio -> {
        encodeBase64(false).getOrNull()?.let { base64Data ->
            buildJsonObject {
                put("inlineData", buildJsonObject {
                    put("mimeType", "audio/mp3")
                    put("data", base64Data)
                })
            }
        }
    }

    else -> null
}

internal fun UIMessagePart.Tool.toFunctionCallPart() = buildJsonObject {
    put("functionCall", buildJsonObject {
        put("name", toolName)
        put("args", inputAsJson())
    })
    metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
        put("thoughtSignature", it)
    }
}

internal fun UIMessagePart.Tool.toFunctionResponsePart() = buildJsonObject {
        put("functionResponse", buildJsonObject {
            put("name", toolName)

            // 1. 拆分出纯文本部分
            val textParts = output.filterIsInstance<UIMessagePart.Text>()
            
            // 2. 提取所有的多模态(图片/视频/音频)，并直接转为 Google 要求的格式
            // 过滤出最终包含 inlineData 的数据块
            val mediaGoogleParts = output
                .filter { it !is UIMessagePart.Text }
                .mapNotNull { it.toGooglePart() }
                .filter { it.containsKey("inlineData") } 

            // 3. 构建给模型看的结构化 response 节点
            put("response", buildJsonObject {
                // 处理文本结果
                if (textParts.isNotEmpty()) {
                    put(
                        "result", 
                        textParts.joinToString("\n") { it.text }
                    )
                } else if (mediaGoogleParts.isEmpty()) {
                    // 如果工具啥都没返回，给个兜底成功状态
                    put("result", " ")
                }

                // 处理媒体数据（图片、音频、视频），打上 $ref 标签
                mediaGoogleParts.forEachIndexed { index, _ ->
                    val refName = "media_ref_$index"
                    put(refName, buildJsonObject {
                        put("\$ref", refName)
                    })
                }
            })

            // 4. 将真实的 Base64 多媒体数据挂载到 parts 中，并建立指针绑定
            if (mediaGoogleParts.isNotEmpty()) {
                putJsonArray("parts") {
                    mediaGoogleParts.forEachIndexed { index, googlePart ->
                        val refName = "media_ref_$index"
                        val inlineData = googlePart["inlineData"]!!.jsonObject

                        add(buildJsonObject {
                            // 重新组装 inlineData，并在内部注入 displayName
                            put("inlineData", buildJsonObject {
                                // 复制原有的 mimeType 和 data
                                inlineData.forEach { (k, v) -> put(k, v) }
                                // 添加能够让 $ref 认出它的唯一名称
                                put("displayName", refName)
                            })
                            
                            // 保留可能存在的其他字段
                            googlePart.forEach { (k, v) ->
                                if (k != "inlineData") put(k, v)
                            }
                        })
                    }
                }
            }
        })
    }
