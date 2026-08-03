package me.rerere.rikkahub.data.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TaskValidation 的单元测试：
 * - REPO_PATTERN 格式校验
 * - [B flaky 自动重试] shouldAutoRetryCI 判定
 */
class TaskValidationTest {

    @Test
    fun `repo pattern accepts owner slash name`() {
        assertTrue(REPO_PATTERN.matches("dcs666/rikkahub-turbo"))
        assertTrue(REPO_PATTERN.matches("octocat/hello-world"))
        assertTrue(REPO_PATTERN.matches("a/b"))
        assertTrue(REPO_PATTERN.matches("my-org/My_Repo.1"))
    }

    @Test
    fun `repo pattern rejects invalid formats`() {
        assertFalse(REPO_PATTERN.matches(""))
        assertFalse(REPO_PATTERN.matches("noslash"))
        assertFalse(REPO_PATTERN.matches("/norepo"))
        assertFalse(REPO_PATTERN.matches("nouser/"))
        assertFalse(REPO_PATTERN.matches("a/b/c"))
        assertFalse(REPO_PATTERN.matches("sp ace/repo"))
    }

    @Test
    fun `auto retry only for timed_out and not already retried`() {
        // GitHub conclusion 枚举：success/failure/neutral/cancelled/skipped/timed_out/...
        assertTrue(shouldAutoRetryCI("timed_out", alreadyRetried = false))
        // 已重试过 → 不再重试（防死循环）
        assertFalse(shouldAutoRetryCI("timed_out", alreadyRetried = true))
        // 真实失败不自动重试（交给 AI 分析）
        assertFalse(shouldAutoRetryCI("failure", alreadyRetried = false))
        assertFalse(shouldAutoRetryCI("neutral", alreadyRetried = false))
        // 主动取消/跳过不重试
        assertFalse(shouldAutoRetryCI("cancelled", alreadyRetried = false))
        assertFalse(shouldAutoRetryCI("skipped", alreadyRetried = false))
        // 成功/空结论不重试
        assertFalse(shouldAutoRetryCI("success", alreadyRetried = false))
        assertFalse(shouldAutoRetryCI("", alreadyRetried = false))
    }

    // ---- [FIX] isStaleRun：runId=0 监控 latest 时防绑定过期 run ----

    private fun ciResult(
        runId: Long = 100,
        status: String = "completed",
        startedAt: String = "2026-08-03T10:00:00Z",
    ) = CITaskResult(runId = runId, status = status, startedAt = startedAt, conclusion = "success")

    @Test
    fun `stale check skips when run id is explicitly targeted`() {
        // 指定 runId 的任务不做 stale 判断（用户明确要监控这个 run）
        val config = TaskConfig.CIMonitor(repo = "a/b", runId = 42)
        assertFalse(isStaleRun(config, taskCreatedAt = 0L, ciResult(runId = 42)))
    }

    @Test
    fun `stale check skips auto-rerun old run by skipRunId`() {
        // [FIX #1] auto-rerun 后旧 run 保持 timed_out 结论，必须被精确跳过
        val config = TaskConfig.CIMonitor(
            repo = "a/b", runId = 0L,
            skipRunId = 123L, autoRetried = true,
        )
        assertTrue(isStaleRun(config, taskCreatedAt = 0L, ciResult(runId = 123)))
        // 新 run（rerun 产生的）不被跳过
        assertFalse(isStaleRun(config, taskCreatedAt = 0L, ciResult(runId = 124)))
    }

    @Test
    fun `stale check accepts run created within tolerance before task`() {
        // 正常流程：push → GitHub 注册 run → AI 创建监控，run 的 created_at 略早于任务
        val config = TaskConfig.CIMonitor(repo = "a/b", runId = 0L)
        val createdMs = java.time.Instant.parse("2026-08-03T10:00:00Z").toEpochMilli()
        // 任务在 run 创建 20s 后才创建（在 30s 容差内 → 不视为 stale）
        val taskCreatedAt = createdMs + 20_000L
        assertFalse(isStaleRun(config, taskCreatedAt, ciResult(startedAt = "2026-08-03T10:00:00Z")))
    }

    @Test
    fun `stale check rejects completed run created long before task`() {
        // [FIX #3] 监控 latest 时，上一次 push 的残留 run 不能采信
        val config = TaskConfig.CIMonitor(repo = "a/b", runId = 0L)
        val createdMs = java.time.Instant.parse("2026-08-03T10:00:00Z").toEpochMilli()
        // 任务在 run 创建 5 分钟后才创建（远超 30s 容差 → 视为 stale）
        val taskCreatedAt = createdMs + 300_000L
        assertTrue(isStaleRun(config, taskCreatedAt, ciResult(startedAt = "2026-08-03T10:00:00Z")))
    }

    @Test
    fun `stale check does not reject in-progress runs`() {
        // in_progress 无法判断新旧，照常等待（不误杀正在跑的目标 run）
        val config = TaskConfig.CIMonitor(repo = "a/b", runId = 0L)
        assertFalse(isStaleRun(config, 1_000_000_000L, ciResult(status = "in_progress")))
    }

    @Test
    fun `stale check is lenient when timestamp cannot be parsed`() {
        val config = TaskConfig.CIMonitor(repo = "a/b", runId = 0L)
        assertFalse(isStaleRun(config, 1_000_000_000L, ciResult(startedAt = "")))
        assertFalse(isStaleRun(config, 1_000_000_000L, ciResult(startedAt = "not-a-date")))
    }
}
