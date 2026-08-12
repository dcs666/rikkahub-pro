package me.rerere.rikkahub.data.ai

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale

/** 翻译输入长度上限（与文档注入截断一致） */
private const val MAX_TRANSLATE_INPUT_CHARS = 200_000

/**
 * 翻译域（Strangler Fig 拆分自 GenerationHandler）：承载 translateText（流式翻译 +
 * Qwen MT 专用翻译）。依赖仅 providerManager；调用方经 GenerationHandler 门面转发。
 */
class TranslationHandler(
    private val providerManager: ProviderManager,
) {
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
            // [PERF] 流式翻译 O(n²) 缓解：原实现每 chunk 全量 handleMessageChunk（列表拷贝）
            // + toText()（joinToString 重拼整段）+ emit 全量文本，长文本翻译累计复制 O(n²)。
            // 仿主链路 text-only 累积器：连续纯文本 delta 进 StringBuilder（O(1) append），
            // 每 ~64ms 才快照 emit 一次；其余 chunk 类型（reasoning/usage/role 切换/非纯文本）
            // 一律 fallback 原路径，语义完全不变。两个消费方（ChatMessageOps/TranslatorVM）
            // 均为"全量覆盖最新值"模式，降频不丢内容。
            var textBuf: StringBuilder? = null
            var textBaseParts: List<UIMessagePart>? = null
            var textMeta: JsonObject? = null
            var lastTranslationFlush = 0L

            suspend fun flushTranslationText() {
                val buf = textBuf ?: return
                val base = textBaseParts ?: return
                val last = messages.lastOrNull() ?: return
                messages = messages.dropLast(1) + last.copy(
                    parts = base + UIMessagePart.Text(text = buf.toString(), metadata = textMeta)
                )
                textBuf = null
                textBaseParts = null
                val translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                val delta = chunk.choices.getOrNull(0)?.delta ?: chunk.choices.getOrNull(0)?.message
                val deltaTextPart = (delta?.parts?.singleOrNull() as? UIMessagePart.Text)
                val lastParts = messages.lastOrNull()?.parts
                val canAbsorbText = deltaTextPart != null &&
                    deltaTextPart.metadata == null &&
                    chunk.usage == null &&
                    delta?.role == messages.lastOrNull()?.role &&
                    lastParts?.lastOrNull() is UIMessagePart.Text

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
                        if (now - lastTranslationFlush >= 64L) {
                            flushTranslationText()
                            lastTranslationFlush = now
                        }
                    }
                } else {
                    flushTranslationText()
                    messages = messages.handleMessageChunk(chunk)
                    val translatedText = messages.lastOrNull()?.toText() ?: ""

                    if (translatedText.isNotBlank()) {
                        onStreamUpdate?.invoke(translatedText)
                        emit(translatedText)
                    }
                    lastTranslationFlush = SystemClock.elapsedRealtime()
                }
            }
            flushTranslationText()
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
