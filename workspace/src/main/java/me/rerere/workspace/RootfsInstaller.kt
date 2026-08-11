package me.rerere.workspace

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * [TURBO] rootfs 下载源：默认走国内可达镜像（华为云实测最快），官方源保底。
 * 2026-08-11 实测（arm64, 24.04.3, 29.8MB）：华为云 632KB/s > 官方 262KB/s > TUNA 181KB/s > 阿里云 173KB/s
 */
object RootfsUrls {
    const val DEFAULT =
        "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"

    /** 候选源（按序尝试，DEFAULT 在最前；仅当用户未自定义 URL 时使用整条链） */
    val MIRRORS: List<String> = listOf(
        DEFAULT,
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz",
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz",
        "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz",
    )
}

class RootfsInstaller(
    private val manager: WorkspaceManager,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {
    fun install(
        root: String,
        urls: List<String>,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) {
        require(urls.isNotEmpty() && urls.all { it.isNotBlank() }) { "Rootfs download url is required" }
        manager.ensureWorkspace(root)
        val format = ArchiveFormat.fromUrl(urls.first())
        val tempDir = manager.tempDir(root)
        val archive = File(tempDir, "rootfs.${format.extension}")
        val stagingDir = File(tempDir, "rootfs-staging")
        val linuxDir = manager.linuxDir(root)

        try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            // 多源回退：按序尝试，某个源失败自动换下一个，全部失败抛出最后一个错误
            var lastError: Throwable? = null
            for (url in urls) {
                try {
                    download(url, archive, onProgress)
                    lastError = null
                    break
                } catch (e: Throwable) {
                    lastError = e
                    archive.delete()
                }
            }
            if (lastError != null) throw lastError
            extractTar(archive, stagingDir, format, onProgress)
            linuxDir.deleteRecursively()
            require(stagingDir.renameTo(linuxDir)) {
                "Failed to move rootfs into workspace"
            }
            patcher.patch(linuxDir)
            onProgress(RootfsInstallProgress(stage = RootfsInstallStage.INSTALLED))
        } finally {
            archive.delete()
            stagingDir.deleteRecursively()
        }
    }

    private fun download(
        url: String,
        target: File,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            require(code in 200..299) { "Rootfs download failed: HTTP $code" }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead = 0L
                    var lastReportBytes = 0L
                    while (true) {
                        checkInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (bytesRead - lastReportBytes >= PROGRESS_STEP_BYTES || bytesRead == totalBytes) {
                            lastReportBytes = bytesRead
                            onProgress(
                                RootfsInstallProgress(
                                    stage = RootfsInstallStage.DOWNLOADING,
                                    bytesRead = bytesRead,
                                    totalBytes = totalBytes,
                                )
                            )
                        }
                    }
                    if (bytesRead == 0L) {
                        onProgress(
                            RootfsInstallProgress(
                                stage = RootfsInstallStage.DOWNLOADING,
                                bytesRead = 0,
                                totalBytes = totalBytes,
                            )
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 解压 tar 归档到 targetDir。委托 [TarExtractor]（rootfs 安装与 proot 更新共用）。
     */
    internal fun extractTar(
        archive: File,
        targetDir: File,
        format: ArchiveFormat = ArchiveFormat.fromFile(archive),
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        TarExtractor.extract(archive, targetDir, format) { entries, currentEntry ->
            onProgress(
                RootfsInstallProgress(
                    stage = RootfsInstallStage.EXTRACTING,
                    entriesExtracted = entries,
                    currentEntry = currentEntry,
                )
            )
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Rootfs install cancelled")
        }
    }

    internal typealias ArchiveFormat = TarExtractor.ArchiveFormat

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}
