package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import java.io.File
import java.util.LinkedHashMap

object DocumentAsPromptTransformer : InputMessageTransformer {
    /** 单文档注入 prompt 的最大字符数：防超大 PDF/文本撑爆请求体（API 413/超时）。 */
    private const val MAX_DOCUMENT_CHARS = 200_000

    // [A] 文档解析结果缓存：同一文档（url + mtime + size 一致）不重复解析。
    // 历史消息里的文档每轮生成都会过 transform，PDF/EPUB/PPTX 解析是秒级 CPU 操作，
    // 多轮工具循环 + 长对话 = 反复解析同一文件（OcrTransformer 已有同款缓存先例）。
    // - 键 = url（FilesManager 以 UUID 文件名落盘，覆盖旧文件必生成新 url → 天然防串）
    // - 值含 mtime/size 校验：文件被替换/删除后自动 miss 重解析
    // - 内存 LRU（accessOrder=true），容量上限：32 份 × 200K 字符 ≈ 6.4MB
    // - 解析结果文本大（最大 200K 字符），不落盘（重启后重新解析一次可接受）
    private val documentCache =
        object : LinkedHashMap<String, CachedDocument>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CachedDocument>,
            ): Boolean = size > MAX_CACHED_DOCUMENTS
        }

    private data class CachedDocument(
        val mtime: Long,
        val size: Long,
        val content: String,
    )

    private companion object {
        const val MAX_CACHED_DOCUMENTS = 32
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val rawContent = readDocumentContent(document)
                                // [FIX] 截断超长文档：全量注入大文件会让请求体爆炸
                                // （数十 MB 文本 → 数十万 token → 413/超时/内存压力）。
                                // workspace 场景 AI 可凭 path 属性用工具直接读原文件。
                                val content = if (rawContent.length > MAX_DOCUMENT_CHARS) {
                                    rawContent.take(MAX_DOCUMENT_CHARS) +
                                        "\n...[truncated: file too large, ${rawContent.length} chars total; " +
                                        "read the original file for full content]"
                                } else {
                                    rawContent
                                }
                                val path = resolveWorkspacePath(document)
                                val pathAttr = path?.let { " path=\"$it\"" } ?: ""
                                val prompt = """
                                  <UploadFile name="${document.fileName}"$pathAttr>
                                  ```
                                  $content
                                  ```
                                  </UploadFile>
                                  """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                    }
                )
            }
        }
    }

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }

    private fun parsePptxAsText(file: File): String {
        return PptxParser.parse(file)
    }

    private fun parseEpubAsText(file: File): String {
        return EpubParser.parse(file)
    }

    // 上传文件保存在 filesDir/upload 下, 该目录通过 proot 挂载到 workspace 的 /upload
    // 返回文件在 workspace 内的绝对路径, 便于 AI 用 workspace 工具直接读取原始文件
    private fun resolveWorkspacePath(document: UIMessagePart.Document): String? {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull() ?: return null
        if (file.parentFile?.name != "upload") return null
        return "/upload/${file.name}"
    }

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }
        val mtime = file.lastModified()
        val size = file.length()
        // [A] 缓存命中（mtime/size 一致）→ 复用解析结果，跳过秒级解析
        synchronized(documentCache) {
            documentCache[document.url]?.let { cached ->
                if (cached.mtime == mtime && cached.size == size) {
                    return cached.content
                }
            }
        }
        val content = runCatching {
            when (document.mime) {
                "application/pdf" -> parsePdfAsText(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                "application/epub+zip" -> parseEpubAsText(file)
                else -> file.readText()
            }
        }.getOrElse {
            "[ERROR, failed to read file: ${document.fileName}]"
        }
        synchronized(documentCache) {
            documentCache[document.url] = CachedDocument(mtime, size, content)
        }
        return content
    }
}
