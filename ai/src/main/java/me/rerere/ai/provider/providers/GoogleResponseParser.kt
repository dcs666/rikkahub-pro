package me.rerere.ai.provider.providers

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.common.http.jsonPrimitiveOrNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GoogleResponseParser"

// [拆分] Google 响应解析域（拆自 GoogleProvider，Strangler Fig）。
// 纯函数：角色映射 + 消息/搜索引用/part/用量解析（无类状态依赖）。

internal fun googleRoleToCommonRole(role: String): MessageRole {
    return when (role) {
        "user" -> MessageRole.USER
        "system" -> MessageRole.SYSTEM
        "model" -> MessageRole.ASSISTANT
        else -> error("Unknown role $role")
    }
}

internal fun parseMessage(message: JsonObject): UIMessage {
    val role = googleRoleToCommonRole(
        message["role"]?.jsonPrimitive?.contentOrNull ?: "model"
    )
    // [FIX] 流式 delta / 异常候选可能缺 content（如内容被拦截），返回空消息而非崩溃中断整轮
    val content = message["content"]?.jsonObject
    val parts = content?.get("parts")?.jsonArray?.map { part ->
        parseMessagePart(part.jsonObject)
    } ?: emptyList()

    val groundingMetadata = message["groundingMetadata"]?.jsonObject
    Log.d(TAG, "parseMessage: $groundingMetadata")
    val annotations = parseSearchGroundingMetadata(groundingMetadata)

    return UIMessage(
        role = role,
        parts = parts,
        annotations = annotations
    )
}

internal fun parseSearchGroundingMetadata(jsonObject: JsonObject?): List<UIMessageAnnotation> {
    if (jsonObject == null) return emptyList()
    val groundingChunks = jsonObject["groundingChunks"]?.jsonArray ?: emptyList()
    val chunks = groundingChunks.mapNotNull { chunk ->
        val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
        val uri = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val title = web["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        UIMessageAnnotation.UrlCitation(
            title = title,
            url = uri
        )
    }
    Log.i(TAG, "parseSearchGroundingMetadata: $chunks")
    return chunks
}

internal fun parseMessagePart(jsonObject: JsonObject): UIMessagePart {
    return when {
        jsonObject.containsKey("text") -> {
            val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
            val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
            if (thought) UIMessagePart.Reasoning(
                reasoning = text,
                createdAt = Clock.System.now(),
                finishedAt = null
            ) else UIMessagePart.Text(text)
        }

        jsonObject.containsKey("functionCall") -> {
            // [FIX] 防御：name 缺失时给空串，避免 !! 链 NPE 中断整轮
            val callObj = jsonObject["functionCall"]?.jsonObject
            UIMessagePart.Tool(
                toolCallId = Uuid.random().toString(),
                toolName = callObj?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                input = callObj?.get("args")?.let { json.encodeToString(it) } ?: "{}",
                output = emptyList(),
                metadata = GoogleThoughtMetadata(
                    thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                ).toMetadata()
            )
        }

        jsonObject.containsKey("inlineData") -> {
            val inlineData = jsonObject["inlineData"]?.jsonObject
            val mime = inlineData?.get("mimeType")?.jsonPrimitive?.content ?: "image/png"
            val data = inlineData?.get("data")?.jsonPrimitive?.content ?: ""
            val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
            val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
            // 如果是思考过程中的草稿图，直接忽略
            if (thought) {
                return UIMessagePart.Reasoning(
                    reasoning = "[Draft Image]\n",
                    createdAt = Clock.System.now(),
                    finishedAt = null
                )
            }
            // [FIX] 拼完整 data URI（勿放纯 base64：Coil 渲染与 encodeBase64 的 data: 分支
            // 均按完整 data URI 处理）；video/audio 也按对应 part 解析，未知 mime 降级
            // 为文本占位，不再 require 崩溃中断整轮
            val dataUri = "data:$mime;base64,$data"
            when {
                mime.startsWith("image/") -> UIMessagePart.Image(
                    url = dataUri,
                    metadata = GoogleThoughtMetadata(thoughtSignature = thoughtSignature).toMetadata()
                )

                mime.startsWith("video/") -> UIMessagePart.Video(
                    url = dataUri,
                    metadata = GoogleThoughtMetadata(thoughtSignature = thoughtSignature).toMetadata()
                )

                mime.startsWith("audio/") -> UIMessagePart.Audio(
                    url = dataUri,
                    metadata = GoogleThoughtMetadata(thoughtSignature = thoughtSignature).toMetadata()
                )

                else -> UIMessagePart.Text("[Unsupported media: $mime]")
            }
        }

        else -> error("unknown message part type: $jsonObject")
    }
}

internal fun parseUsageMeta(jsonObject: JsonObject?): TokenUsage? {
    if (jsonObject == null) {
        return null
    }
    val promptTokens = jsonObject["promptTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
    val thoughtTokens = jsonObject["thoughtsTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
    val cachedTokens = jsonObject["cachedContentTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
    val candidatesTokens = jsonObject["candidatesTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
    val totalTokens = jsonObject["totalTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = candidatesTokens + thoughtTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens
    )
}
