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
}
