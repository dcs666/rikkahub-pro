package me.rerere.workspace

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

data class WorkspaceBindMount(
    val source: File,
    val target: String,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    // [A1 会话池] 按 workspace（linuxDir）缓存常驻 proot+bash 会话（LRU，上限 MAX_SESSIONS）。
    // 之前是全局单例：切换 workspace 时 boundLinuxDir 变化 → destroy + 冷启动（~1s）；
    // 且全局一把锁导致不同 workspace 的命令互相阻塞。
    // 现在每个 workspace 独立会话 + 独立锁：
    // - 切换 workspace 零冷启动（会话复用）
    // - 不同 workspace 的命令可并行（link2symlink DB 是每 proot 进程独立的，跨进程无共享状态）
    // - 淘汰最久未用的会话并销毁，池大小有界
    private val sessions = object : LinkedHashMap<File, SessionEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<File, SessionEntry>?): Boolean {
            if (size <= MAX_SESSIONS) return false
            eldest?.value?.session?.destroy()
            return true
        }
    }

    private class SessionEntry(
        val session: PersistentShellSession,
        val lock: ReentrantLock,
    )

    @Synchronized
    private fun sessionFor(linuxDir: File): SessionEntry =
        sessions.getOrPut(linuxDir) {
            SessionEntry(PersistentShellSession(patcher), ReentrantLock(true))
        }

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        // [A1] 每 workspace 一把锁（原来全局一把）：
        // - 同一 workspace 的持久会话与一次性路径串行（同 rootfs 的 patch/访问不并发，
        //   link2symlink 同进程内不支持并发 fork）
        // - 不同 workspace 的命令互不阻塞
        val entry = sessionFor(context.linuxDir)
        val waitMs = context.timeoutMillis + 5_000L
        val acquired = entry.lock.tryLock(waitMs, TimeUnit.MILLISECONDS)
        if (!acquired) {
            return WorkspaceCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "proot lock timeout: another shell command is still running",
                timedOut = true,
            )
        }
        try {
            context.tempDir.mkdirs()
            // [TURBO] 需要 stdin 输入的命令走一次性 proot：常驻会话的 stdin 是命令通道，
            // 不能再喂给命令本身，故 stdin 非空时直接走一次性路径。
            if (context.stdin != null) {
                return executeOneShot(context, proot, loader)
            }
            // [TURBO] 优先走常驻 proot+bash 会话（首次秒级、后续几十 ms）。任何失败
            // （启动失败/进程死/读超时/sentinel 缺失）都销毁会话并退回一次性 proot，绝不丢功能。
            // [FIX] InterruptedException 必须单独捕获并重新抛出：用户点终止键时
            // runInterruptible 中断线程 → readThread.join() 抛 InterruptedException，
            // 若被通用 catch 吞掉会 fallback 到 executeOneShot 重新执行命令，导致终止键失效。
            val session = entry.session
            return try {
                session.execute(context, proot, loader)
            } catch (e: InterruptedException) {
                session.destroy()
                throw e // 传播中断，让 runInterruptible 转为 CancellationException
            } catch (e: Exception) {
                session.destroy()
                executeOneShot(context, proot, loader)
            }
        } finally {
            entry.lock.unlock()
        }
    }

    // [TURBO] 一次性 proot 执行路径（原逻辑），作为常驻会话的 fallback 与 stdin 命令的路径。
    private fun executeOneShot(
        context: WorkspaceShellContext,
        proot: File,
        loader: File,
    ): WorkspaceCommandResult {
        patcher.patch(context.linuxDir)
        val process = ProcessBuilder(buildCommand(context, proot))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            context.prootCwd(),
            "-b",
            "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        context.bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            // [FIX] 用 `bash` 走 PATH 解析: rootfs 的 /bin/bash 可能被 link2symlink 错映射/损坏成 dash,
            // 而 /usr/bin/bash 才是完整 bash; 硬编码 /bin/bash 会让 ; | > & if heredoc 等全部失效。
            "bash",
            // [PERF] 去掉 -l (login shell): login 模式会 source /etc/profile + ~/.bash_profile +
            // /etc/profile.d/*.sh, 每次 shell 调用白付几十~上百 ms; PATH/HOME/LANG/LC_ALL/TERM
            // 已在上方 env 显式给全, AI 工具调用不依赖 login shell 的 alias/变量, 故非 login 启动
            // 既快又干净, 无功能损失。
            "-c",
            // [FIX] 命令直接内联进 -c 脚本, 去掉 eval "$2" + 位置参数间接层。
            // proot 重写 argv 时字符串池尾指针在边界未重置, 多余位置参数会被错位/截断,
            // 导致 $2 取不到完整命令、元字符解析全乱; 内联后命令文本完整位于单个 -c argv 内,
            // 由 bash 自行解析, 规避该问题。cwd 用单引号包裹防注入。
            "cd -- ${context.prootCwd().shellQuote()} && ${context.command}",
        )
        return command
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
        // [A1] 常驻会话池上限：LRU 淘汰最久未用的会话（每个会话 = 一个 proot+bash 进程，
        // 进程数有界，防止多 workspace 堆积）
        private const val MAX_SESSIONS = 3
    }
}

private fun String.shellQuote(): String = "'" + replace("'", "'\"'\"'") + "'"
