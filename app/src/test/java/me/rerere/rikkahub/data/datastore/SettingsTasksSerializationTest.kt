package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.ui.pages.setting.POLL_INTERVAL_OPTIONS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归测试：Settings 任务相关字段的默认值与 JSON 序列化往返。
 *
 * 背景：TaskSettingsSection（拆自 SettingTasksPage）通过
 * settingsStore.update(settings.copy(taskXxx = ...)) 持久化 Token /
 * Auto-watch 白名单 / Webhook URL / 轮询档位。本测试锁定这些字段的
 * 默认值与 copy → encode → decode 往返，防止拆分过程中字段丢失或默认值漂移。
 */
class SettingsTasksSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `task fields have expected defaults`() {
        val settings = Settings()
        assertEquals("", settings.taskGithubToken)
        assertEquals("", settings.taskAutoWatchRepos)
        assertEquals("", settings.taskWebhookUrl)
        assertEquals(true, settings.taskAutoAnalyze)
        assertEquals(true, settings.taskNotifyOnSuccess)
        assertEquals(30, settings.taskPollIntervalSec)
    }

    @Test
    fun `task fields survive json round trip`() {
        val modified = Settings().copy(
            taskGithubToken = "ghp_test_token",
            taskAutoWatchRepos = "dcs666/rikkahub-turbo, octocat/hello-world",
            taskWebhookUrl = "https://sctapi.ftqq.com/abc.send",
            taskAutoAnalyze = false,
            taskNotifyOnSuccess = false,
            taskPollIntervalSec = 120,
        )

        val decoded = json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), modified))

        assertEquals("ghp_test_token", decoded.taskGithubToken)
        assertEquals("dcs666/rikkahub-turbo, octocat/hello-world", decoded.taskAutoWatchRepos)
        assertEquals("https://sctapi.ftqq.com/abc.send", decoded.taskWebhookUrl)
        assertEquals(false, decoded.taskAutoAnalyze)
        assertEquals(false, decoded.taskNotifyOnSuccess)
        assertEquals(120, decoded.taskPollIntervalSec)
    }

    @Test
    fun `default settings round trip keeps task defaults`() {
        val decoded = json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), Settings()))
        assertEquals("", decoded.taskGithubToken)
        assertEquals("", decoded.taskAutoWatchRepos)
        assertEquals("", decoded.taskWebhookUrl)
        assertEquals(true, decoded.taskAutoAnalyze)
        assertEquals(true, decoded.taskNotifyOnSuccess)
        assertEquals(30, decoded.taskPollIntervalSec)
    }

    @Test
    fun `poll interval options cover supported range`() {
        // 与 SettingTasksPage 的档位选择一致（10s 下限对应工具端约束）
        assertEquals(listOf(10, 30, 60, 120, 300), POLL_INTERVAL_OPTIONS)
    }
}
