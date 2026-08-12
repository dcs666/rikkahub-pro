package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归测试：Auto-watch 白名单解析（parseRepoList）。
 *
 * 背景：TaskSettingsSection（拆自 SettingTasksPage 的设置卡片区）依赖该函数
 * 解析逗号/换行分隔的 repo 白名单输入。本测试锁定分隔/去空白/去重行为。
 */
class RepoListParserTest {

    @Test
    fun `comma separated repos are parsed`() {
        assertEquals(
            listOf("dcs666/rikkahub-turbo", "octocat/hello-world"),
            parseRepoList("dcs666/rikkahub-turbo, octocat/hello-world"),
        )
    }

    @Test
    fun `newline separated repos are parsed`() {
        assertEquals(
            listOf("dcs666/rikkahub-turbo", "octocat/hello-world"),
            parseRepoList("dcs666/rikkahub-turbo\noctocat/hello-world"),
        )
    }

    @Test
    fun `mixed comma and newline separators are parsed`() {
        assertEquals(
            listOf("a/b", "c/d", "e/f"),
            parseRepoList("a/b,\nc/d\ne/f"),
        )
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(
            listOf("a/b", "c/d"),
            parseRepoList("  a/b  , \n c/d \t"),
        )
    }

    @Test
    fun `blank lines and empty entries are dropped`() {
        assertEquals(
            listOf("a/b"),
            parseRepoList("\n\n  \na/b,\n"),
        )
    }

    @Test
    fun `duplicates are removed`() {
        assertEquals(
            listOf("a/b", "c/d"),
            parseRepoList("a/b, c/d, a/b"),
        )
    }

    @Test
    fun `empty input returns empty list`() {
        assertEquals(emptyList<String>(), parseRepoList(""))
        assertEquals(emptyList<String>(), parseRepoList("   "))
        assertEquals(emptyList<String>(), parseRepoList("\n\n"))
    }
}
