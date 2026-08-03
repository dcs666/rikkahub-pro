package me.rerere.rikkahub.data.task

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskUtilsTest {

    // ---- computeNextPollDelay 指数退避曲线 ----

    @Test
    fun `backoff uses base interval for first 5 polls`() {
        val base = 30_000L
        assertEquals(base, computeNextPollDelay(0, base))
        assertEquals(base, computeNextPollDelay(4, base))
    }

    @Test
    fun `backoff doubles between 5 and 10 polls`() {
        val base = 30_000L
        assertEquals(base * 2, computeNextPollDelay(5, base))
        assertEquals(base * 2, computeNextPollDelay(9, base))
    }

    @Test
    fun `backoff triples between 10 and 20 polls`() {
        val base = 30_000L
        assertEquals(base * 3, computeNextPollDelay(10, base))
        assertEquals(base * 3, computeNextPollDelay(19, base))
    }

    @Test
    fun `backoff caps at 5 minutes from 20 polls`() {
        val base = 30_000L
        assertEquals(300_000L, computeNextPollDelay(20, base))
        assertEquals(300_000L, computeNextPollDelay(1000, base))
        // 大基数时 5x 超过上限也要被钳制
        assertEquals(300_000L, computeNextPollDelay(20, 120_000L))
    }

    @Test
    fun `backoff respects configured base for small bases`() {
        // 10s 下限（工具端钳制后）前 5 次仍是 10s
        assertEquals(10_000L, computeNextPollDelay(0, 10_000L))
        // 120s 基础间隔前 5 次用 120s
        assertEquals(120_000L, computeNextPollDelay(3, 120_000L))
    }

    // ---- REPO_PATTERN 格式校验 ----

    @Test
    fun `repo pattern accepts valid owner slash name`() {
        assertTrue(REPO_PATTERN.matches("dcs666/rikkahub-turbo"))
        assertTrue(REPO_PATTERN.matches("github/octocat"))
        assertTrue(REPO_PATTERN.matches("A-B.c_1/name-2.x"))
    }

    @Test
    fun `repo pattern rejects invalid formats`() {
        assertFalse(REPO_PATTERN.matches(""))
        assertFalse(REPO_PATTERN.matches("no-slash-here"))
        assertFalse(REPO_PATTERN.matches("owner/"))
        assertFalse(REPO_PATTERN.matches("/repo"))
        assertFalse(REPO_PATTERN.matches("owner/repo/extra"))
        assertFalse(REPO_PATTERN.matches("own er/repo"))
        assertFalse(REPO_PATTERN.matches("中文/仓库"))
        assertFalse(REPO_PATTERN.matches("https://github.com/owner/repo"))
    }

    // ---- GitHubActionsClient.isRateLimitError ----

    @Test
    fun `rate limit exception is detected`() {
        val client = GitHubActionsClient()
        assertTrue(client.isRateLimitError(GitHubRateLimitException("GitHub API rate limit (403)")))
    }

    @Test
    fun `legacy ioexception messages with 403 or 429 are detected`() {
        val client = GitHubActionsClient()
        assertTrue(client.isRateLimitError(IOException("GitHub API error: 403 Forbidden")))
        assertTrue(client.isRateLimitError(IOException("GitHub API error: 429 Too Many Requests")))
    }

    @Test
    fun `other errors are not rate limit`() {
        val client = GitHubActionsClient()
        assertFalse(client.isRateLimitError(IOException("GitHub API error: 404 Not Found")))
        assertFalse(client.isRateLimitError(IOException("GitHub API error: 500 Internal Server Error")))
        assertFalse(client.isRateLimitError(IOException("connect timeout")))
        assertFalse(client.isRateLimitError(RuntimeException("boom")))
    }
}
