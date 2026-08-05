package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import kotlin.io.path.name

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> {
        val dir = resolvePath(root, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        return dir.listFiles()
            .orEmpty()
            .filter { !it.name.startsWith(".l2s.") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .take(config.maxListEntries)
            .map { it.toEntry(root) }
    }

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= config.maxReadBytes) {
            "File is too large to read: ${file.length()} bytes"
        }
        return file.readText(charset)
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val file = resolvePath(root, path)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.toEntry(root)
    }

    fun importBytes(root: File, path: String, inputStream: InputStream): WorkspaceFileEntry {
        val file = resolvePath(root, path)
        file.parentFile?.mkdirs()
        val target = if (!file.exists()) file else resolveConflict(file)
        inputStream.use { input -> target.outputStream().use { input.copyTo(it, DEFAULT_COPY_BUFFER) } }
        return target.toEntry(root)
    }

    private fun resolveConflict(file: File): File {
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        var candidate: File
        do { candidate = File(file.parentFile, "$stem ($n)$ext"); n++ } while (candidate.exists())
        return candidate
    }

    fun delete(root: File, path: String, recursive: Boolean = false): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val file = resolvePath(root, path)
        // [FIX] "./"（或 "/"、" ." 等变体）可绕过上面的字面量检查，resolvePath 会
        // 解析到工作区根目录 → recursive=true 时 deleteRecursively 删除整个工作区。
        // 用 canonical 对比根目录，任何等价写法都拒绝。
        require(file.canonicalFile != root.canonicalFile) { "Refusing to delete workspace root" }
        if (!file.exists()) return false
        return if (file.isDirectory) {
            require(recursive) { "Directory delete requires recursive = true" }
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    fun move(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to move workspace root" }
        val sourceFile = resolvePath(root, source)
        val targetFile = resolvePath(root, target)
        // [FIX] 同 delete："./" 变体可解析到根目录 → renameTo 移动整个工作区。
        require(sourceFile.canonicalFile != root.canonicalFile) { "Refusing to move workspace root" }
        require(sourceFile.exists()) { "Source does not exist: $source" }
        if (targetFile.exists()) {
            require(overwrite) { "Target already exists: $target" }
            if (targetFile.isDirectory) {
                targetFile.deleteRecursively()
            } else {
                targetFile.delete()
            }
        }
        targetFile.parentFile?.mkdirs()
        require(sourceFile.renameTo(targetFile)) {
            "Failed to move $source to $target"
        }
        return targetFile.toEntry(root)
    }

    fun glob(root: File, pattern: String, path: String = ""): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val matcher = globMatcher(pattern)
        val results = mutableListOf<WorkspaceFileEntry>()
        // [FIX] 达到上限提前终止遍历（与 grep 的 SearchLimitReached 一致）：
        // 原实现 .take(maxListEntries) 只截断结果，Files.walk 仍扫完整棵树。
        try {
            Files.walk(start.toPath()).use { stream ->
                val paths = stream.iterator().asSequence()
                paths
                    .filter { Files.isRegularFile(it) || Files.isDirectory(it) }
                    .filter { !it.toFile().name.startsWith(".l2s.") }
                    .forEach { path ->
                        if (results.size >= config.maxListEntries) throw SearchLimitReached()
                        if (matcher.matches(root.toPath().relativize(path).normalizeForMatch())) {
                            results += path.toFile().toEntry(root)
                        }
                    }
            }
        } catch (_: SearchLimitReached) {
            // 已达上限，正常结束
        }
        return results
    }

    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank()) { "Search query is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher = includeGlob
            ?.takeIf { it.isNotBlank() }
            ?.let { globMatcher(it) }

        val results = mutableListOf<WorkspaceSearchMatch>()
        // [FIX] 达到搜索上限后抛 SearchLimitReached 提前终止 Files.walk 遍历：
        // 原实现只跳过匹配逻辑，walk 仍会扫完整棵目录树（超大目录浪费时间）。
        try {
            Files.walk(start.toPath()).use { stream ->
                val paths = stream.iterator().asSequence()
                paths
                    .filter { Files.isRegularFile(it) }
                    .filter { !it.toFile().name.startsWith(".l2s.") }
                    .forEach { path ->
                        if (results.size >= config.maxSearchResults) throw SearchLimitReached()
                        if (includeMatcher != null &&
                            !includeMatcher.matches(root.toPath().relativize(path).normalizeForMatch())
                        ) {
                            return@forEach
                        }
                        val file = path.toFile()
                        if (file.length() > config.maxReadBytes) return@forEach
                        file.useLines(StandardCharsets.UTF_8) { lines ->
                            lines.forEachIndexed { index, line ->
                                if (results.size >= config.maxSearchResults) throw SearchLimitReached()
                                if (matcher.containsMatchIn(line)) {
                                    results += WorkspaceSearchMatch(
                                        path = file.relativePath(root),
                                        line = index + 1,
                                        text = line,
                                    )
                                }
                            }
                        }
                    }
            }
        } catch (_: SearchLimitReached) {
            // 已达上限，正常结束（stream 已随 use{} 关闭）
        }
        return results
    }

    /** [FIX] glob 模式编译失败时转为带清晰信息的 IllegalArgumentException（调用方按 400 处理）。 */
    private fun globMatcher(pattern: String): PathMatcher =
        runCatching { FileSystems.getDefault().getPathMatcher("glob:$pattern") }
            .getOrElse { throw IllegalArgumentException("Invalid glob pattern: $pattern", it) }

    /** [FIX] grep 达搜索上限时用于提前终止 Files.walk 遍历的内部信号。 */
    private class SearchLimitReached : RuntimeException()

    private fun resolvePath(root: File, path: String): File {
        root.mkdirs()
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }

        val rootFile = root.canonicalFile
        val target = if (normalized == ".") rootFile else File(rootFile, normalized).canonicalFile
        val rootPath = rootFile.path
        val targetPath = target.path
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path escapes workspace root: $path"
        }
        return target
    }

    fun resolve(root: File, path: String): File = resolvePath(root, path)

    private fun File.toEntry(root: File): WorkspaceFileEntry = WorkspaceFileEntry(
        path = relativePath(root),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        updatedAt = lastModified(),
    )

    private fun File.relativePath(root: File): String {
        val rootCanonical = root.canonicalFile
        val parentCanonical = (parentFile ?: rootCanonical).canonicalFile
        return File(parentCanonical, name).relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
    }

    private fun Path.normalizeForMatch(): Path =
        FileSystems.getDefault().getPath(relativeToString())

    private fun Path.relativeToString(): String =
        joinToString("/") { it.name }
}
