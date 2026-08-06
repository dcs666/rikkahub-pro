package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

/**
 * 设备公共存储只读访问 (合法扩展边界: 用户可选的 Download/Documents 白名单目录)
 *
 * 安全设计:
 *  - 只读: 不提供写能力
 *  - 白名单: 仅 Android 公共目录 Download/Documents (用户自己放文件的区域)
 *  - 路径校验: canonicalPath 规范化 + 前缀检查 (拒绝 ../ 逃逸)
 *  - 大小限制: 8MB (同 workspace 读取)
 */
private const val MAX_DEVICE_READ_BYTES = 8L * 1024 * 1024

private val DEVICE_ALLOWED_DIRS: List<String> = listOf(
    "/sdcard/Download",
    "/sdcard/Documents",
    "/storage/emulated/0/Download",
    "/storage/emulated/0/Documents",
)

private val DEVICE_IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isDeviceImage(): Boolean =
    substringAfterLast('.', "").lowercase() in DEVICE_IMAGE_EXTENSIONS

/** 规范化 + 白名单前缀校验, 返回安全 File; 不在白名单返回 null */
private fun resolveDeviceFile(requested: String): File? {
    val norm = File(requested).canonicalPath
    val ok = DEVICE_ALLOWED_DIRS.any { dir ->
        val dirNorm = File(dir).canonicalPath
        norm == dirNorm || norm.startsWith("$dirNorm${File.separator}")
    }
    return if (ok) File(norm) else null
}

private fun devicePathParameter(description: String): kotlinx.serialization.json.JsonObject =
    buildJsonObject {
        put("path", buildJsonObject {
            put("type", "string")
            put("description", description)
        })
    }

fun createDeviceReadFileTool(): Tool = Tool(
    name = "device_read_file",
    description = """
        Read a file from the device's public storage (Download/Documents folders only).
        Read-only access to files the user placed on the phone (PDFs, notes, screenshots, images).
        Paths must be absolute like /sdcard/Download/notes.txt. Supports UTF-8 text and images.
        Use device_list_files first to discover available files.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = devicePathParameter(
                "Absolute path under /sdcard/Download or /sdcard/Documents"
            ),
            required = listOf("path"),
        )
    },
    needsApproval = { true },
    execute = {
        val path = it.jsonObject["path"]?.jsonPrimitive?.contentOrNull
            ?: error("path is required")
        val file = resolveDeviceFile(path)
            ?: error("path outside allowed dirs (Download/Documents only)")
        if (!file.isFile) error("not a file: $path")
        if (file.length() > MAX_DEVICE_READ_BYTES) {
            error("file too large (${file.length() / 1024 / 1024}MB, max ${MAX_DEVICE_READ_BYTES / 1024 / 1024}MB)")
        }
        if (path.isDeviceImage()) {
            val bytes = file.readBytes()
            val filesManager = getKoin().get<FilesManager>()
            val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
            listOf(
                UIMessagePart.Image(url = uris.first().toString()),
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("description", "Device image read successfully")
                    }.toString()
                ),
            )
        } else {
            val text = file.readText(Charsets.UTF_8)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

fun createDeviceListFilesTool(): Tool = Tool(
    name = "device_list_files",
    description = """
        List files in the device's public storage (Download/Documents).
        Returns entries as 'type\tsize\tname' lines. Read-only, safe.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = devicePathParameter(
                "Directory to list (default /sdcard/Download)"
            ),
            required = emptyList(),
        )
    },
    needsApproval = { true },
    execute = {
        val dir = it.jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: "/sdcard/Download"
        val file = resolveDeviceFile(dir) ?: error("dir outside allowed dirs (Download/Documents only)")
        if (!file.isDirectory) error("not a directory: $dir")
        val entries = (file.listFiles() ?: emptyArray())
            .sortedBy { f -> f.name.lowercase() }
            .joinToString("\n") { f ->
                val type = if (f.isDirectory) "dir" else "file"
                val size = if (f.isFile) "${f.length() / 1024}KB" else "-"
                "$type\t$size\t${f.name}"
            }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("dir", dir)
                    put("files", entries)
                }.toString()
            )
        )
    },
)
