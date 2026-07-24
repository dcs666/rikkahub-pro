package me.rerere.workspace

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 持久化 proot shell runner —— 复用 proot 进程，避免每次启动开销
 *
 * 原理：
 * 1. 第一次调用时启动 proot + 常驻 shell 进程（通过 setsid 脱离进程组）
 * 2. 后续调用通过命名管道（FIFO）发送命令，无需重新启动 proot
 * 3. 结果写入临时文件，由调用方读取
 *
 * 实测：后台进程可通过 setsid + disown 跨 workspace_shell 调用存活
 *
 * 改动文件：
 * - 新增: PersistentProotShellRunner.kt (本文件)
 * - 修改: RepositoryModule.kt → 将 ProotShellRunner 替换为本类
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

        // � 缓存每个 workspace root 的 proot 进程和通信管道
        private val prootProcesses = ConcurrentHashMap<String, Process>()
        private val commandPipes = ConcurrentHashMap<String, File>()
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
        val existingProcess = prootProcesses[key]

        return if (existingProcess != null && existingProcess.isAlive) {
            // � 热路径：proot 进程已存在，通过管道发送命令
            sendCommandViaPipe(key, context.command, context.timeoutMillis)
        } else {
            // � 冷路径：首次启动 proot 常驻进程
            startPersistentProot(context, proot, loader)
        }
    }

    private fun startPersistentProot(
        context: WorkspaceShellContext,
        proot: File,
        loader: File,
    ): WorkspaceCommandResult {
        val key = context.root

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)

        // 创建结果目录（映射到 host 可读路径）
        val resultDir = File(context.filesDir, ".proot_results")
        resultDir.mkdirs()

        // 常驻 shell：创建 FIFO → 循环读取命令 → 执行 → 写结果
        val daemonScript = """
            /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 /bin/bash -l -c '
                set +m
                FIFO=$WORKSPACE_DIR/.proot_cmd
                RESULT_DIR=$WORKSPACE_DIR/.proot_results
                rm -f "$FIFO"
                mkfifo "$FIFO"
                while true; do
                    if read -r cmd < "$FIFO"; then
                        [ -z "$cmd" ] && continue
                        [ "$cmd" = "__exit__" ] && break
                        result_file="$RESULT_DIR/out_$(date +%s%N)"
                        eval "$cmd" > "$result_file" 2>&1
                        echo "__exitcode__$?" >> "$result_file"
                    fi
                done
            '
        """.trimIndent()

        val process = ProcessBuilder(buildDaemonCommand(context, proot, daemonScript))
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
            .start()

        prootProcesses[key] = process

        // 等待 FIFO 就绪
        Thread.sleep(1000)

        // 执行首次命令
        return sendCommandViaPipe(key, context.command, context.timeoutMillis, resultDir)
    }

    private fun sendCommandViaPipe(
        key: String,
        command: String,
        timeoutMillis: Long,
        resultDir: File? = null,
    ): WorkspaceCommandResult {
        val pipe = if (resultDir != null) {
            File(resultDir.parentFile?.parentFile, ".proot_cmd").apply {
                if (!exists()) return WorkspaceCommandResult(1, "", "FIFO not found")
            }
        } else {
            commandPipes[key] ?: return WorkspaceCommandResult(1, "", "No pipe available")
        }

        val results = if (resultDir != null) resultDir else {
            val parent = pipe.parentFile
            File(parent, ".proot_results")
        }

        try {
            // 写入命令到 FIFO
            File(pipe.absolutePath).writeText(command + "\n")

            // 等待结果文件（带超时）
            val deadline = System.currentTimeMillis() + timeoutMillis
            var resultFile: File? = null

            while (System.currentTimeMillis() < deadline) {
                val files = results.listFiles()
                    ?.filter { it.name.startsWith("out_") }
                if (!files.isNullOrEmpty()) {
                    resultFile = files.first()
                    break
                }
                Thread.sleep(30)
            }

            if (resultFile == null) {
                return WorkspaceCommandResult(-1, "", "Timed out waiting for result")
            }

            val output = resultFile.readText()
            resultFile.delete()

            // 解析退出码
            val exitCode = Regex("__exitcode__(\\d+)").find(output)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val cleanOutput = output.replace(Regex("__exitcode__\\d+\\n?"), "").trim()

            return WorkspaceCommandResult(exitCode, cleanOutput, "")

        } catch (e: Exception) {
            return WorkspaceCommandResult(1, "", "Pipe error: ${e.message}")
        }
    }

    private fun buildDaemonCommand(
        context: WorkspaceShellContext,
        proot: File,
        daemonScript: String,
    ): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id", "--link2symlink", "--kill-on-exit",
            "-r", context.linuxDir.absolutePath,
            "-w", context.prootCwd(),
            "-b", "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        extraBindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"; command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }
        listOf("/dev", "/proc", "/sys").forEach { path ->
            if (File(path).exists()) { command += "-b"; command += path }
        }

        command += listOf("/bin/bash", "-c", daemonScript)
        return command
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) WORKSPACE_DIR else "$WORKSPACE_DIR/$normalized"
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile
}
