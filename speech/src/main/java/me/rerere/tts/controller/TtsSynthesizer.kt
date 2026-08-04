package me.rerere.tts.controller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import java.io.ByteArrayOutputStream

/**
 * Bridge TTS provider flow to a single audio buffer.
 */
class TtsSynthesizer(
    private val ttsManager: TTSManager
) {
    suspend fun synthesize(
        setting: TTSProviderSetting,
        chunk: TtsChunk
    ): TTSResponse = withContext(Dispatchers.IO) {
        collectToResponse(
            ttsManager.generateSpeech(setting, TTSRequest(text = chunk.text))
        )
    }

    private suspend fun collectToResponse(flow: Flow<AudioChunk>): TTSResponse {
        var format: AudioFormat? = null
        var sampleRate: Int? = null
        val output = ByteArrayOutputStream()
        // [FIX] 音频流上限防护：异常 provider 响应（超大/无限流）此前全量收集 → OOM。
        // 超限抛 IllegalStateException（TTS 链路捕获后按失败处理，不崩溃）。
        var totalBytes = 0L
        flow.collect { chunk ->
            if (format == null) format = chunk.format
            if (sampleRate == null) sampleRate = chunk.sampleRate
            totalBytes += chunk.data.size
            if (totalBytes > MAX_AUDIO_BYTES) {
                throw IllegalStateException("TTS audio exceeds ${MAX_AUDIO_BYTES / (1024 * 1024)}MB limit")
            }
            output.write(chunk.data)
        }
        return TTSResponse(
            audioData = output.toByteArray(),
            format = format ?: AudioFormat.MP3,
            sampleRate = sampleRate
        )
    }

    private companion object {
        // 单次 TTS 合成音频上限：150 字符 chunk 的 MP3 通常 < 1MB，8MB 已远超正常值
        const val MAX_AUDIO_BYTES = 8L * 1024 * 1024
    }
}

