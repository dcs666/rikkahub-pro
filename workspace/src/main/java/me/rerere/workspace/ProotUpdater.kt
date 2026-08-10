package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * [TURBO] proot 运行时自动更新：从 termux 官方仓库下载最新预编译 proot（含依赖库），
 * 替代 APK 内置的旧版（内置为 5.1.0，仓库最新为 5.1.107.89，2026 年持续修复）。
 *
 * 为什么这样做：
 * - termux 仓库（packages.termux.dev / TUNA 镜像）发布 Android aarch64/x86_64 预编译
 *   proot + libtalloc + libandroid-shmem，带 SHA256 校验，比 APK 内置版本更新更快
 * - 解压复用 [TarExtractor]（纯 Java，不依赖 rootfs 工具），deb 的 data.tar.xz 直接可用
 * - termux proot 支持 PROOT_LOADER 环境变量覆盖 loader 路径（硬编码的是 termux 自己的
 *   路径，App 用环境变量指向自己目录下的 loader）
 *
 * 产物布局（binDir）：
 *   proot                — proot 主程序（可执行）
 *   loader               — proot loader（PROOT_LOADER 指向这里）
 *   libtalloc.so.2       — 依赖（LD_LIBRARY_PATH 指向 binDir）
 *   libandroid-shmem.so  — 依赖（同上）
 *   version              — 当前安装版本号
 *   last_check            — 上次检查时间戳（24h 内不重复检查）
 *
 * 失败安全：任何异常静默返回 false，调用方回退内置 proot，绝不阻塞 shell 功能。
 */
class ProotUpdater(
    val binDir: File,
    private val abi: String, // "aarch64" / "x86_64"（termux 仓库目录名）
) {
    private val prootBin = File(binDir, "proot")
    private val loaderBin = File(binDir, "loader")

    /** 下载源，按序尝试（官方 + 清华 TUNA 镜像，国内可达） */
    private val sources: List<String> = listOf(
        "https://packages.termux.dev/apt/termux-main",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
    )

    /** 需要下载的包：包名 -> deb 内的目标文件（相对 usr/，strip 前缀后） */
    private data class PackageSpec(
        val name: String,
        val files: List<String>, // 相对 "usr/" 的路径，如 "bin/proot"、"lib/libtalloc.so.2"
    )

    private val packageSpecs = listOf(
        PackageSpec("proot", listOf("bin/proot", "libexec/proot/loader")),
        PackageSpec("libtalloc", listOf("lib/libtalloc.so.2")),
        PackageSpec("libandroid-shmem", listOf("lib/libandroid-shmem.so")),
    )

    /** 已安装版本；未安装返回 null */
    fun installedVersion(): String? =
        File(binDir, VERSION_FILE).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** proot + loader 都就绪 */
    fun isReady(): Boolean = prootBin.isFile && loaderBin.isFile

    /**
     * 检查并更新 proot。同步执行，调用方应在 IO 线程。
     * @return true=更新成功或已是最新；false=失败（保持现状，回退内置）
     */
    fun updateIfNeeded(): Boolean {
        return try {
            // 不支持的 ABI（如模拟器）不尝试下载
            if (abi == "unknown") return isReady()
            if (!shouldCheck()) return isReady()
            val index = fetchPackagesIndex() ?: return isReady()
            val latest = parsePackageVersion(index, "proot") ?: return isReady()
            if (latest == installedVersion()) {
                markChecked()
                return isReady()
            }
            val ok = downloadAndInstall(index)
            markChecked()
            ok
        } catch (e: Exception) {
            false
        }
    }

    // ---- 版本检查 ----

    private fun shouldCheck(): Boolean {
        val lastCheck = File(binDir, LAST_CHECK_FILE)
        if (!lastCheck.isFile) return true
        val elapsed = System.currentTimeMillis() - (lastCheck.readText().trim().toLongOrNull() ?: 0L)
        return elapsed >= CHECK_INTERVAL_MS
    }

    private fun markChecked() {
        runCatching {
            binDir.mkdirs()
            File(binDir, LAST_CHECK_FILE).writeText(System.currentTimeMillis().toString())
        }
    }

    /** 拉取 Packages 索引（按源顺序尝试，返回第一个成功的） */
    private fun fetchPackagesIndex(): String? {
        for (source in sources) {
            val url = "$source/dists/stable/main/binary-$abi/Packages"
            val text = try {
                httpGet(url)?.toString(Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
            if (text != null && text.hasPackage("proot")) return text
        }
        return null
    }

    private fun String.hasPackage(name: String): Boolean =
        startsWith("Package: $name\n") || contains("\nPackage: $name\n")

    /** 从 Packages 索引解析指定包的 Version/Filename/SHA256 */
    internal fun parsePackageVersion(index: String, packageName: String): String? {
        val section = index.split("\n\n").firstOrNull {
            it.startsWith("Package: $packageName\n") || it.contains("\nPackage: $packageName\n")
        } ?: return null
        return section.lineSequence()
            .firstOrNull { it.startsWith("Version: ") }
            ?.removePrefix("Version: ")
            ?.trim()
    }

    internal fun parsePackageField(index: String, packageName: String, field: String): String? {
        val section = index.split("\n\n").firstOrNull {
            it.startsWith("Package: $packageName\n") || it.contains("\nPackage: $packageName\n")
        } ?: return null
        return section.lineSequence()
            .firstOrNull { it.startsWith("$field: ") }
            ?.removePrefix("$field: ")
            ?.trim()
    }

    // ---- 下载与安装 ----

    private fun downloadAndInstall(index: String): Boolean {
        val staging = File(binDir, "staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            for (spec in packageSpecs) {
                val filename = parsePackageField(index, spec.name, "Filename") ?: return false
                val sha256 = parsePackageField(index, spec.name, "SHA256") ?: return false
                val deb = File(staging, "${spec.name}.deb")
                if (!downloadTo("${baseSource()}/$filename", deb, sha256)) return false
                extractDeb(deb, staging)
                deb.delete()
            }
            // 移动目标文件到 binDir 根
            for (spec in packageSpecs) {
                for (rel in spec.files) {
                    val src = File(staging, "usr/$rel")
                    if (!src.isFile) return false
                    val dst = File(binDir, rel.substringAfterLast('/'))
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                    dst.setExecutable(true, false)
                }
            }
            File(binDir, VERSION_FILE).writeText(
                parsePackageVersion(index, "proot") ?: return false
            )
            return true
        } finally {
            staging.deleteRecursively()
        }
    }

    /** 记住下载成功的源（避免每次重新探测） */
    private var cachedSource: String? = null

    private fun baseSource(): String {
        cachedSource?.let { return it }
        for (source in sources) {
            if (httpHead("$source/dists/stable/main/binary-$abi/Packages")) {
                cachedSource = source
                return source
            }
        }
        return sources.first()
    }

    /** 下载 deb 并校验 SHA256 */
    private fun downloadTo(url: String, target: File, expectedSha256: String): Boolean {
        val bytes = try {
            httpGet(url) ?: return false
        } catch (e: Exception) {
            return false
        }
        val actual = sha256(bytes)
        if (!actual.equals(expectedSha256, ignoreCase = true)) return false
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return true
    }

    /** 解析 deb（ar 归档），提取 data.tar.xz 并用 TarExtractor 解压（strip termux 前缀） */
    private fun extractDeb(deb: File, staging: File) {
        val dataTarXz = File(staging, "data.tar.xz")
        extractArMember(deb, "data.tar.xz", dataTarXz)
        TarExtractor.extract(
            archive = dataTarXz,
            targetDir = staging,
            format = TarExtractor.ArchiveFormat.TAR_XZ,
            stripPrefix = "data/data/com.termux/files",
        )
        dataTarXz.delete()
    }

    /**
     * 从 ar 归档提取指定成员。ar 格式：
     *   魔数 "!<arch>\n"（8 字节）
     *   每个成员：60 字节头（name[16] mtime[12] uid[6] gid[6] mode[8] size[10] magic[2]）
     *   数据：size 字节，2 字节对齐
     */
    internal fun extractArMember(archive: File, memberName: String, target: File) {
        archive.inputStream().use { input ->
            val magic = ByteArray(8)
            if (input.readFully(magic) != 8 || String(magic) != "!<arch>\n") {
                throw IllegalArgumentException("Not an ar archive: ${archive.name}")
            }
            while (true) {
                val header = ByteArray(60)
                if (input.readFully(header) != 60) throw IllegalArgumentException("Truncated ar archive: ${archive.name}")
                val name = String(header, 0, 16, Charsets.US_ASCII).trimEnd(' ', '/', '\u0000')
                val sizeText = String(header, 48, 10, Charsets.US_ASCII).trim().trimEnd('\u0000')
                val size = sizeText.toLongOrNull() ?: throw IllegalArgumentException("Bad ar member size: $sizeText")
                if (name == memberName) {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        input.copyBytes(out, size)
                    }
                    return
                }
                input.skipExactly(size)
                // 奇数大小对齐到偶数
                if (size % 2 != 0L) input.skipExactly(1)
            }
        }
    }

    // ---- HTTP 工具 ----

    private fun httpGet(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpHead(url: String): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "HEAD"
        try {
            return connection.responseCode in 200..299
        } catch (e: Exception) {
            return false
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun InputStream.readFully(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun InputStream.copyBytes(output: java.io.OutputStream, bytes: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = bytes
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw java.io.EOFException("Unexpected EOF in ar member")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.skipExactly(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                throw java.io.EOFException("Unexpected EOF while skipping ar data")
            }
        }
    }

    companion object {
        private const val VERSION_FILE = "version"
        private const val LAST_CHECK_FILE = "last_check"
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
