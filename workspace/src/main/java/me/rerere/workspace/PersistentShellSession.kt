package me.rerere.workspace

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * [TURBO] 常驻 proot+bash 会话：shell 性能质变的核心。
 *
 * 背景：每条 shell 命令都 fork+exec 一个新 proot 进程（加载原生 .so + ptrace 初始化 + 起 bash），
 * 是秒级固定开销。本类维护一个长驻 proot+bash，命令经 stdin 发送、输出经 stdout 读取、用带 NUL 的
 * sentinel 标记结束并携带退出码，把"每次秒级"降到"首次秒级 + 后续几十 ms"。
 *
 * 安全设计（每项都对应一个真实坑）：
 * - 子 shell 隔离：每条命令包成 `( cd -- CWD && COMMAND )`，cd/变量/export/trap 在子 shell 结束即消失，
 *   天然无跨命令状态污染，无需手动 reset。
 * - sentinel 防冲突：结束标记 `\0__RIKKA_DONE_<code>__\0` 含 NUL 字节，正常文本输出几乎不含 NUL，
 *   偶然匹配概率极低。
 * - stderr 合并 stdout：redirectErrorStream(true) 单流读取（trade-off：stderr 混入 stdout，顺序可能交错）。
 * - 完整 fallback：任何异常（启动失败/进程死/读超时/sentinel 缺失）都抛出，由 ProotShellRunner 退回
 *   一次性 proot，绝不丢功能。
 * - stdin 命令不走这里：需要 stdin 输入的命令由调用方直接走一次性 proot，避免 stdin 通道冲突。
 */
class PersistentShellSession(
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var boundLinuxDir: File? = null

    private companion object {
        // sentinel: NUL + 前缀 + 退出码 + 后缀 + NUL。NUL 让正常文本几乎不可能误匹配。
        private const val SENTINEL_PREFIX = "\u0000__RIKKA_DONE_"
        private const val SENTINEL_SUFFIX = "__\u0000"
        private const val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
        private const val WARMUP_TIMEOUT_MS = 5_000L
    }

    @Synchronized
    fun destroy() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        process?.let { p ->
            if (p.isAlive) {
                // [FIX] 先 SIGTERM（process.destroy()）：proot 收到后可执行
                // --kill-on-exit 的清理逻辑（杀掉 tracee 进程树），避免子孙进程残留；
                // 等待 1s 后仍存活再 SIGKILL 兜底。直接 destroyForcibly() 是 SIGKILL，
                // proot 无法做任何清理，残留的 bash/命令子进程会变成孤儿。
                runCatching { p.destroy() }
                try {
                    if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                    // 被中断也不能留活进程：SIGKILL 兜底后恢复中断状态
                    p.destroyForcibly()
                    Thread.currentThread().interrupt()
                }
            }
        }
        process = null
        writer = null
        reader = null
        boundLinuxDir = null
    }

    /**
     * 在常驻会话执行命令。失败时抛异常（由调用方 fallback 到一次性 proot）。
     */
    @Synchronized
    fun execute(
        context: WorkspaceShellContext,
        proot: File,
        loader: File?,
    ): WorkspaceCommandResult {
        ensureStarted(context, proot, loader)
        val w = writer ?: error("persistent shell: writer unavailable")
        val r = reader ?: error("persistent shell: reader unavailable")

        // [TURBO-FIX] 命令在主 bash 进程内直接执行，不再用 `( ... )` 子 shell 包裹。
        // 根因（用户实测：短命令 cd/ls/echo 每次都 "persistent shell command timed out"）：
        // `( cmd )` 会让 bash 纯 fork 一个子 shell（bash 克隆，不 exec）。proot --link2symlink
        // 的共享数据库不支持并发访问，fork 出的子 shell 继承父 bash 的 db 状态后与父进程
        // 并发访问 → 死锁/卡住 → 命令永不结束 → sentinel 永不到达 → 30s 超时。
        // warmUp（主 bash 直接 printf，无 fork）成功、实际命令（强制子 shell）超时，完全吻合。
        // 一次性 proot（bash -c → fork+exec 全新进程，不继承 db 锁状态）无此问题，故从未超时。
        // 修复后：cd 是 builtin 不 fork；外部命令（ls/grep/curl）fork+exec 全新进程，
        // 与一次性路径等价，不死锁。状态隔离降级：cwd 会残留，但每条命令开头都会 cd 到
        // 目标目录覆盖；export 变量残留概率低，可接受。
        // [FIX] 命令交给内层 `bash -c` 执行（fork+exec 全新进程）：
        // 1) `exit`/`exec`/`kill -9 $$` 等只影响内层 bash，会话主 bash 存活
        // 2) 命令内的变量/export/trap 随内层 bash 退出即消失，天然隔离
        // 3) fork+exec 是与一次性 proot 路径等价的"安全模式"（不死锁）；
        //    之前因 fork 子 shell（无 exec）与 proot --link2symlink 共享 DB 死锁
        //    才改为直接内联，现在用 exec 的子进程重新获得隔离，且规避死锁
        // 4) 命令文本经 shellQuote 保护外层解析，bash -c 内部再解析一次；
        //    `#` 行尾/尾随反斜杠/未闭合引号只影响内层（返回非零退出码），
        //    sentinel 在独立行，永不被打断
        // 代价：每条命令多一次 fork+exec（毫秒级），相对 proot 秒级启动可忽略
        // [PERF] sentinel 走 stderr（>&2）：glibc 的 stderr 默认 unbuffered → 立即
        // 写入管道，读线程马上能检测到；stdout 保持块缓冲（见 buildSessionCommand，
        // 已移除 stdbuf -oL）→ 编译/大输出按 4KB 批量 flush，write syscall 数从
        // "每行一次"降到"每 4KB 一次"（10 万行输出 ≈ 100 倍削减），每条 write 在
        // proot 下都要过 ptrace，这是 shell 性能的关键优化。退出码经外层 bash
        // 捕获（bash -c 内 exit 不会跳过外层 sentinel 打印）。
        val script = buildString {
            append("cd -- ")
            append(context.prootCwd().shellQuote())
            append(" && bash -c ")
            append(context.command.shellQuote())
            append("\n__rikka_s=${'$'}?\n")
            append("printf '\\\\000__RIKKA_DONE_%d__\\\\000' \\\"${'$'}__rikka_s\\\" >&2\n")
        }
        runCatching {
            w.write(script)
            w.flush()
        }.onFailure {
            destroy()
            throw it
        }
        return readUntilSentinel(r, context.timeoutMillis)
    }

    private fun ensureStarted(context: WorkspaceShellContext, proot: File, loader: File?) {
        if (process?.isAlive == true && boundLinuxDir == context.linuxDir) return
        destroy()
        patcher.patch(context.linuxDir)
        val p = ProcessBuilder(buildSessionCommand(context, proot, loader))
            .directory(context.filesDir)
            .redirectErrorStream(true) // stderr 合并 stdout，单流读取
            .apply {
                if (loader != null) {
                    environment()["PROOT_LOADER"] = loader.absolutePath
                    environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                } else {
                    // proroot：运行时配置文件目录
                    environment()["PROROOT_TMP_DIR"] = context.tempDir.absolutePath
                }
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
            .start()
        process = p
        writer = OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8)
        reader = BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8))
        boundLinuxDir = context.linuxDir
        // 预热：发一个空命令读掉 bash/proot 的启动输出，确保后续命令输出干净。
        warmUp()
    }

    private fun warmUp() {
        val w = writer ?: return
        val r = reader ?: return
        runCatching {
            w.write("printf '\\\\000__RIKKA_DONE_0__\\\\000' >&2\n")
            w.flush()
            readUntilSentinel(r, WARMUP_TIMEOUT_MS)
        }.onFailure {
            destroy()
            throw it
        }
    }

    private fun buildSessionCommand(context: WorkspaceShellContext, proot: File, loader: File?): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
        )
        if (loader != null) {
            command += listOf("--root-id", "--link2symlink", "--kill-on-exit")
        } else {
            // proroot 参数风格：-0 == --root-id；不支持 --kill-on-exit
            command += listOf("-0", "--link2symlink")
        }
        command += listOf(
            "-r", context.linuxDir.absolutePath,
            "-w", context.prootCwd(),
            "-b", "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
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
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            // [PERF] 移除 stdbuf -oL -eL（行缓冲）：命令输出走 stdout 块缓冲
            // （4KB 批量 flush），write syscall 数 = 输出字节/4KB 而非"每行一次"。
            // proot 下每条 write 都过 ptrace 拦截，这是编译/大输出慢的关键因素。
            // sentinel 改走 stderr（>&2，glibc stderr 默认 unbuffered 立即送达），
            // 配合 redirectErrorStream(true) 单流读取，无超时风险。
            // 非交互 bash：stdin 是 pipe 时自动非交互地读命令执行，无 prompt。
            // --norc --noprofile 不加载 rc（更快更干净，AI 工具调用不依赖 alias/变量）。
            "bash", "--norc", "--noprofile",
        )
        return command
    }

    private fun readUntilSentinel(r: BufferedReader, timeoutMillis: Long): WorkspaceCommandResult {
        // [PERF] 预分配主缓冲容量，避免 StringBuilder 从 16 扩容到 128KB 的 ~13 次复制
        val sb = StringBuilder(MAX_OUTPUT_CHARS.coerceAtMost(64 * 1024))
        // [FIX] 尾部缓冲：主 sb 满后仍需检测 sentinel（sentinel 在输出末尾）。
        // [FIX2] 窗口太小会让跨 read 的 sentinel 前后缀分离而漏检。
        // [PERF] sentinel 必然在输出末尾（bash 执行完最后一条命令后打印），
        // tail 是最后 tailKeep 字符的滑动窗口，始终包含 sentinel → 只检测 tail，
        // 省去每次循环对主 sb 全量 indexOf（O(n²) → O(n)）。
        // [FIX3] tailKeep 必须 >= 单次 read 缓冲(16KB) + sentinel 长度：
        // sentinel 前缀在 read N 末尾、后缀在 read N+1 开头时，
        // 若窗口小于 read 大小，追加后删除头部会把前缀一起删掉 → 漏检假超时。
        val tailKeep = 16 * 1024 + 128
        val tail = StringBuilder(tailKeep)
        // [FIX] 截断标记：用 AtomicBoolean 保证跨线程可见（readThread 写、主线程读）
        val truncatedFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val readThread = Thread {
            try {
                // [PERF] 16KB 缓冲：管道读一次 4096 太小，系统调用次数多；
                // 16KB 是 pipe 默认容量，一次最多取满
                val buf = CharArray(16 * 1024)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    // 主缓冲：限制内存
                    if (sb.length < MAX_OUTPUT_CHARS) {
                        val remaining = MAX_OUTPUT_CHARS - sb.length
                        sb.append(buf, 0, minOf(n, remaining))
                        if (n > remaining) truncatedFlag.set(true)
                    } else {
                        truncatedFlag.set(true)
                    }
                    // 尾部缓冲：始终追加（用于 sentinel 检测），保持小尺寸
                    tail.append(buf, 0, n)
                    if (tail.length > tailKeep * 2) {
                        tail.delete(0, tail.length - tailKeep)
                    }
                    // sentinel 检测：只查 tail（含 NUL 的 sentinel 几乎不可能被正常输出误匹配）
                    val tailPrefixIdx = tail.indexOf(SENTINEL_PREFIX)
                    if (tailPrefixIdx >= 0 &&
                        tail.indexOf(SENTINEL_SUFFIX, tailPrefixIdx) >= 0
                    ) break
                }
            } catch (_: Exception) {
                // 进程被杀/流关闭时 read 抛异常，保留已读内容即可
            }
        }.apply { isDaemon = true; start() }

        readThread.join(timeoutMillis)
        if (readThread.isAlive) {
            // 超时：会话卡死，销毁（daemon 读线程会随流关闭结束）
            destroy()
            return WorkspaceCommandResult(
                exitCode = -1,
                // [OPT] 返回超时前已读到的部分输出，AI 可据此判断命令卡在哪一步
                stdout = sb.toString(),
                stderr = "persistent shell command timed out",
                timedOut = true,
            )
        }

        val all = sb.toString()
        val prefixIdx = all.indexOf(SENTINEL_PREFIX)
        if (prefixIdx >= 0) {
            val codeStart = prefixIdx + SENTINEL_PREFIX.length
            val codeEnd = all.indexOf(SENTINEL_SUFFIX, codeStart)
            if (codeEnd >= 0) {
                val output = all.substring(0, prefixIdx)
                val exitCode = all.substring(codeStart, codeEnd).toIntOrNull() ?: -1
                return WorkspaceCommandResult(
                    exitCode = exitCode,
                    stdout = output,
                    stderr = "",
                    timedOut = false,
                    truncated = truncatedFlag.get(),
                )
            }
            // sentinel 前缀在 sb 但后缀在 tail（截断边界）：回退到 tail 提取
        }
        // sb 中没有完整 sentinel：可能是输出超限被截断，sentinel 只在 tail 中。
        // 此时输出 = sb 全量（已截断），退出码从 tail 提取。
        val tailAll = tail.toString()
        val tailPrefixIdx = tailAll.indexOf(SENTINEL_PREFIX)
        if (tailPrefixIdx < 0) {
            // 真没读到 sentinel：进程可能已死，销毁让调用方 fallback
            destroy()
            error("persistent shell: sentinel not found (session likely dead)")
        }
        val tailCodeStart = tailPrefixIdx + SENTINEL_PREFIX.length
        val tailCodeEnd = tailAll.indexOf(SENTINEL_SUFFIX, tailCodeStart)
        val tailExitCode = if (tailCodeEnd >= 0) {
            tailAll.substring(tailCodeStart, tailCodeEnd).toIntOrNull() ?: -1
        } else {
            -1
        }
        return WorkspaceCommandResult(
            exitCode = tailExitCode,
            stdout = sb.toString(),
            stderr = "",
            timedOut = false,
            truncated = true,
        )
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) WORKSPACE_DIR else "$WORKSPACE_DIR/$normalized"
    }
}

private fun String.shellQuote(): String = "'" + replace("'", "'\"'\"'") + "'"
