package me.rerere.workspace

import java.io.File

/**
 * Persistent proot shell runner.
 * Keeps the proot process alive across calls to avoid startup overhead.
 */
class PersistentProotShellRunner(
    private val nativeLibraryDir: File,
    private val extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {

    companion object {
        private var cachedProc: Process? = null
        private var cachedRoot: String = ""
        private var cmdFifo: File? = null
        private var resDir: File? = null
    }

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val proot = File(nativeLibraryDir, "libproot_exec.so")
        val loader = File(nativeLibraryDir, "libproot_loader.so")

        val key = context.root
        val isAlive = try { cachedProc?.exitValue(); false } catch (e: Exception) { cachedProc != null }

        if (cachedProc != null && isAlive && cachedRoot == key) {
            return sendCommand(context.command, context.timeoutMillis)
        }

        // Start new persistent proot
        cachedProc?.let { try { it.destroy() } catch (_: Exception) {} }
        cachedProc = null

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)

        val fifo = File(context.filesDir, ".proot_fifo")
        val results = File(context.filesDir, ".proot_results")
        fifo.delete()
        results.mkdirs()
        cmdFifo = fifo
        resDir = results

        val script = buildString {
            append("rm -f /workspace/.proot_fifo\n")
            append("mkfifo /workspace/.proot_fifo\n")
            append("mkdir -p /workspace/.proot_results\n")
            append("while true; do\n")
            append("  if read -r cmd < /workspace/.proot_fifo; then\n")
            append("    [ -z \"$cmd\" ] && continue\n")
            append("    [ \"$cmd\" = \"__exit__\" ] && break\n")
            append("    echo \"$cmd\" > /workspace/.proot_results/last_cmd\n")
            append("    eval \"$cmd\" > /workspace/.proot_results/out 2>&1\n")
            append("    echo __DONE__ >> /workspace/.proot_results/out\n")
            append("  fi\n")
            append("done\n")
        }

        val cmd = mutableListOf(
            proot.absolutePath, "--root-id", "--link2symlink", "--kill-on-exit",
            "-r", context.linuxDir.absolutePath,
            "-w", context.prootCwd(),
            "-b", "${context.filesDir.absolutePath}:/workspace"
        )
        for (mount in extraBindMounts) {
            if (mount.source.exists()) {
                cmd.add("-b"); cmd.add("${mount.source.absolutePath}:${mount.target.trimEnd('/')}")
            }
        }
        for (p in listOf("/dev", "/proc", "/sys")) {
            if (File(p).exists()) { cmd.add("-b"); cmd.add(p) }
        }
        cmd.add("/bin/bash"); cmd.add("-c"); cmd.add(script)

        val proc = ProcessBuilder(cmd)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
            .start()

        cachedProc = proc
        cachedRoot = key
        Thread.sleep(1500)
        return sendCommand(context.command, context.timeoutMillis)
    }

    private fun sendCommand(cmd: String, ttl: Long): WorkspaceCommandResult {
        try {
            cmdFifo?.writeText(cmd + "\n")
            val deadline = System.currentTimeMillis() + ttl
            while (System.currentTimeMillis() < deadline) {
                val out = File(resDir, "out")
                if (out.exists()) {
                    val text = out.readText()
                    out.delete()
                    val clean = text.replace("__DONE__", "").trim()
                    return WorkspaceCommandResult(0, clean, "")
                }
                Thread.sleep(30)
            }
            return WorkspaceCommandResult(-1, "", "timed out")
        } catch (e: Exception) {
            return WorkspaceCommandResult(1, "", e.message ?: "error")
        }
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val n = cwd.trim().trim('/')
        return if (n.isEmpty()) "/workspace" else "/workspace/$n"
    }
}
