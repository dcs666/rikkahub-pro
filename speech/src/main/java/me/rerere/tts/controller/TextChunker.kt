package me.rerere.tts.controller

/**
 * Split long text into speakable chunks with basic punctuation-aware grouping.
 */
class TextChunker(
    private val maxChunkLength: Int = 150
) {
    fun split(text: String): List<TtsChunk> {
        if (text.isBlank()) return emptyList()

        val paragraphs = text.split("\n\n")
        val punctuationRegex = "(?<=[。！？，、：;.!?:,\n])".toRegex()

        val chunks = paragraphs.flatMap { paragraph ->
            if (paragraph.isBlank()) emptyList() else {
                paragraph
                    .split(punctuationRegex)
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .fold(mutableListOf<StringBuilder>()) { acc, seg ->
                        // [FIX] 超长无标点段（长 URL/代码块）此前不硬切 → 单 chunk 远超
                        // maxChunkLength → TTS provider 请求超限失败/413。
                        // 按硬上限再切分（语义上 chunk 本就是可拼接的语音片段）。
                        if (seg.length > maxChunkLength) {
                            var remaining = seg
                            while (remaining.length > maxChunkLength) {
                                acc.add(StringBuilder(remaining.take(maxChunkLength)))
                                remaining = remaining.drop(maxChunkLength)
                            }
                            if (remaining.isNotEmpty()) acc.add(StringBuilder(remaining))
                        } else if (acc.isEmpty() || acc.last().length + seg.length > maxChunkLength) {
                            acc.add(StringBuilder(seg))
                        } else {
                            acc.last().append(seg)
                        }
                        acc
                    }
                    .map { it.toString() }
            }
        }

        return chunks.mapIndexed { index, value ->
            TtsChunk(text = value, index = index)
        }
    }
}

data class TtsChunk(
    val id: java.util.UUID = java.util.UUID.randomUUID(),
    val index: Int,
    val text: String
)

