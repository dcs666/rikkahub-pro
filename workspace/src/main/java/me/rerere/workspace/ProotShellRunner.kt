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
    private val updater: ProotUpdater? = null,
) : WorkspaceShellRunner {
    // [TURBO] 优先使用 ProotUpdater 下载的最新版 proot（termux 仓库预编译，
    // 5.1.107.89 及以上，2026 年持续修复）；未就绪/失败时回退 APK 内置 proot。
    // 2026-08-11 起不再自动触发下载（用户要求去掉"每次 shell 自动下载解压"），
    // 改为工作区详情页手动按钮触发（WorkspaceDetailVM.updateProot）。
    private val prootBinDir: File? get() = updater?.binDir?.takeIf { updater.isReady() }

    /** 下载 proot 依赖库所在目录（LD_LIBRARY_PATH 指向这里） */
    private fun ldLibraryPath(): String? = prootBinDir?.absolutePath

    /** proot 二进制指纹（mtime+size）：用于检测 proot 更新后销毁旧会话重建 */
    private fun prootFingerprint(proot: File): String = "${proot.lastModified()}-${proot.length()}"

    // [A1 会话池] 按 workspace（linuxDir）缓存常驻 proot+bash 会话（LRU，上限 MAX_SESSIONS）。
    // 之前是全局单例：切换 workspace 时 boundLinuxDir 变化 → destroy + 冷启动（~1s）；
    // 且全局一把锁导致不同 workspace 的命令互相阻塞。
    // 现在每个 workspace 独立会话 + 独立锁：
    // - 切换 workspace 零冷启动（会话复用）
    // - 不同 workspace 的命令可并行（link2symlink DB 是每 proot 进程独立的，跨进程无共享状态）
    // - 淘汰最久未用的会话并销毁，池大小有界
    private val sessions = object : LinkedHashMap<File, SessionEntry>(16, 0.75f, true) {}

    private class SessionEntry(
        val session: PersistentShellSession,
        val lock: ReentrantLock,
        val prootFingerprint: String,
    )

    // [A1-FIX] 淘汰改为手动扫描（不再用 removeEldestEntry）：
    // 原实现里 removeEldestEntry 在 sessionFor 的 @Synchronized 锁内调用 session.destroy()，
    // 而 destroy() 也是 session 上的 @Synchronized —— 若被淘汰会话正有命令在跑（execute 持有
    // session monitor），destroy() 会阻塞等待命令结束（最长 600s），期间所有 workspace 的
    // sessionFor 全部卡住 → 全局 shell 头阻塞。且 MAX_SESSIONS(3) < TOOL_PARALLELISM(4)，
    // 4 个并行工具打 4 个不同 workspace 时必然触发淘汰，命中忙会话的概率不低。
    // 现在：只淘汰「最久未用且空闲」的会话（destroy 立即完成，无阻塞）；全部忙碌时允许池
    // 暂时超限（有界于并发命令数），忙碌会话结束后下次插入会再淘汰。
    // [TURBO] 另外：proot 运行时更新（ProotUpdater）后 proot 文件指纹变化，
    // 旧会话（进程已加载旧版 proot）必须销毁重建才能让新版生效。
    @Synchronized
    private fun sessionFor(linuxDir: File, expectedFingerprint: String): SessionEntry {
        sessions[linuxDir]?.let { entry ->
            if (entry.prootFingerprint == expectedFingerprint) return entry
            // proot 已更新：销毁旧会话（阻塞等待其当前命令结束），下次命令用新版
            entry.session.destroy()
            sessions.remove(linuxDir)
        }
        if (sessions.size >= MAX_SESSIONS) {
            val iterator = sessions.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!entry.value.lock.isLocked) {
                    entry.value.session.destroy()
                    iterator.remove()
                    break
                }
            }
        }
        return SessionEntry(
            PersistentShellSession(patcher, sessionEnv()),
            ReentrantLock(true),
            expectedFingerprint,
        ).also { sessions[linuxDir] = it }
    }

    /** 下载版 proot 的依赖库查找路径（LD_LIBRARY_PATH），无下载版返回空 */
    private fun sessionEnv(): Map<String, String> {
        val ldPath = ldLibraryPath() ?: return emptyMap()
        return mapOf("LD_LIBRARY_PATH" to ldPath)
    }

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            // [FIX] 报错携带期望路径：排查"界面就绪但工具报未安装"时一眼定位检查目标
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed: ${context.linuxDir.absolutePath}/bin/sh is missing",
            )
        }

        val useDownloaded = prootBinDir != null
        val proot = if (useDownloaded) File(prootBinDir!!, "proot")
        else File(nativeLibraryDir, PROOT_EXEC)
        val loader = if (useDownloaded) File(prootBinDir!!, "loader")
        else File(nativeLibraryDir, PROOT_LOADER)
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
        val entry = sessionFor(context.linuxDir, prootFingerprint(proot))
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
                // 下载版 proot 的依赖库（libtalloc/libandroid-shmem）查找路径
                sessionEnv().forEach { (k, v) -> environment()[k] = v }
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
            // [FIX] 命令也用 shellQuote 包裹，防止注入（与 PersistentShellSession 一致）。
            // 用 bash -c 间接执行，确保管道/重定向等元字符被正确解析。
            "cd -- ${context.prootCwd().shellQuote()} && bash -c ${context.command.shellQuote()}",
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
