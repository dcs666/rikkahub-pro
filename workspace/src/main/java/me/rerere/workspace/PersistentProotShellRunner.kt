package me.rerere.workspace

import java.io.File

/**
 * 持久化 proot shell runner
 * 首次启动 proot 后保持进程存活，后续命令通过 FIFO 通信
 */
class PersistentProotShellRunner(
    private val nativeLibraryDir: File,
    private val extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {

    companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private const val WORKSPACE_DIR = "/workspace"
        
        // 缓存进程和FIFO路径
        private var cachedProcess: Process? = null
        private var cachedRoot: String = ""
        private var fifoPath: String = ""
        private var resultDirPath: String = ""
    }

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(127, "", "Rootfs is not installed")
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile || !loader.isFile) {
            return WorkspaceCommandResult(127, "", "proot executable not found")
        }

        val key = context.root
        val alive = try { cachedProcess?.let { it.exitValue(); false } ?: false } catch (e: IllegalThreadStateException) { true }

        return if (cachedProcess != null && alive && cachedRoot == key) {
            // 热路径：发送命令到 FIFO
            sendCommand(context.command, context.timeoutMillis)
        } else {
            // 冷路径：启动 proot 常驻进程
            cachedProcess?.let { try { it.destroy() } catch (_: Exception) {} }
            cachedProcess = null
            startPersistentProot(context, proot, loader, key)
        }
    }

    private fun startPersistentProot(
        context: WorkspaceShellContext, proot: File, loader: File, key: String
    ): WorkspaceCommandResult {
        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)

        // FIFO 和结果目录（在 files 目录下，host 可读写）
        val fifoFile = File(context.filesDir, ".proot_fifo")
        val resultDir = File(context.filesDir, ".proot_results")
        resultDir.mkdirs()
        fifoPath = fifoFile.absolutePath
        resultDirPath = resultDir.absolutePath
        fifoFile.delete()

        // 常驻 shell 脚本
        val script = buildString {
            appendLine("rm -f $WORKSPACE_DIR/.proot_fifo")
            appendLine("mkfifo $WORKSPACE_DIR/.proot_fifo")
            appendLine("mkdir -p $WORKSPACE_DIR/.proot_results")
            appendLine("while true; do")
            appendLine("  if read -r cmd < $WORKSPACE_DIR/.proot_fifo; then")
            appendLine("    [ -z \"\$cmd\" ] && continue")
            appendLine("    [ \"\$cmd\" = \"__exit__\" ] && break")
            appendLine("    echo \"\$cmd\" | /bin/bash -l > $WORKSPACE_DIR/.proot_results/out 2>&1")
            appendLine("    echo \"__done__\" >> $WORKSPACE_DIR/.proot_results/out")
            appendLine("  fi")
            appendLine("done")
        }

        val cmdArgs = buildCommand(context, proot, script)
        val process = ProcessBuilder(cmdArgs)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
            .start()

        cachedProcess = process
        cachedRoot = key
        Thread.sleep(1500) // 等待 FIFO 创建

        return sendCommand(context.command, context.timeoutMillis)
    }

    private fun sendCommand(command: String, timeoutMillis: Long): WorkspaceCommandResult {
        try {
            File(fifoPath).writeText("cd /workspace\n$command\n")
            val deadline = System.currentTimeMillis() + timeoutMillis
            var output = ""
            var found = false

            while (System.currentTimeMillis() < deadline) {
                val outFile = File(resultDirPath, "out")
                if (outFile.exists()) {
                    output = outFile.readText()
                    outFile.delete()
                    found = true
                    break
                }
                Thread.sleep(30)
            }

            if (!found) return WorkspaceCommandResult(-1, "", "Timed out")

            val exitCode = if ("__done__" in output) {
                output = output.replace("__done__", "").trim()
                0
            } else 0

            return WorkspaceCommandResult(exitCode, output, "")
        } catch (e: Exception) {
            return WorkspaceCommandResult(1, "", "Error: ${e.message}")
        }
    }

    private fun buildCommand(context: WorkspaceShellContext, proot: File, script: String): List<String> {
        val cmd = mutableListOf(
            proot.absolutePath, "--root-id", "--link2symlink", "--kill-on-exit",
            "-r", context.linuxDir.absolutePath,
            "-w", context.prootCwd(),
            "-b", "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )
        for (mount in extraBindMounts) {
            if (mount.source.exists()) { cmd += "-b"; cmd += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}" }
        }
        for (path in listOf("/dev", "/proc", "/sys")) {
            if (File(path).exists()) { cmd += "-b"; cmd += path }
        }
        cmd += "/bin/bash"; cmd += "-c"; cmd += script
        return cmd
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val n = cwd.trim().trim('/')
        return if (n.isBlank()) WORKSPACE_DIR else "$WORKSPACE_DIR/$n"
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile
}
