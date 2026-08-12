package me.rerere.rikkahub.data.sync.importer

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.serialization.json.JsonNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.io.File
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlin.time.Instant as KotlinInstant
import kotlin.uuid.Uuid

// [拆分] Chatbox 导入数据模型域（拆自 ChatboxImporter.kt，Strangler Fig）

data class ChatboxImportPayload(
    val providers: List<ProviderSetting>,
    val conversations: ChatboxConversationImport,
)

data class ChatboxConversationImport(
    val conversations: List<Conversation>,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
)

data class ChatboxStreamingImportResult(
    val providers: List<ProviderSetting>,
    val parsedConversations: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
    val hasConversationSystemPrompt: Boolean,
)

internal data class ChatboxSessionParseResult(
    val conversation: Conversation?,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
)

data class ChatboxPartParseResult(
    val parts: List<UIMessagePart>,
    val skippedImageParts: Int,
)

