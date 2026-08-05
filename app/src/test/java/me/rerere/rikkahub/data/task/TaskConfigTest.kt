package me.rerere.rikkahub.data.task

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    // 定时器 AI 执行（autoAi）链路的核心：配置必须 round-trip 无损
    @Test
    fun `timer config round trip preserves autoAi and message`() {
        val config = TaskConfig.Timer(
            delayMs = 300_000,
            message = "查看 CI 状态并执行发布链",
            repeatIntervalMs = 300_000,
            repeatCount = 0,
            autoAi = true,
        )
        val encoded = json.encodeToString(TaskConfig.serializer(), config)
        val decoded = json.decodeFromString(TaskConfig.serializer(), encoded)
        assertTrue(decoded is TaskConfig.Timer)
        val timer = decoded as TaskConfig.Timer
        assertEquals(300_000L, timer.delayMs)
        assertEquals("查看 CI 状态并执行发布链", timer.message)
        assertEquals(300_000L, timer.repeatIntervalMs)
        assertEquals(0, timer.repeatCount)
        assertTrue(timer.autoAi)
    }

    @Test
    fun `timer config defaults autoAi to false when field absent`() {
        // [FIX] sealed class 解码必须带 "type" 判别字段（@SerialName("timer")），
        // 缺失会抛 SerializationException 导致 :app:testDebugUnitTest 失败
        val encoded = buildString {
            append("{\"type\":\"timer\",\"delayMs\":60000,\"message\":\"hello\",\"repeatIntervalMs\":0,\"repeatCount\":0}")
        }
        val decoded = json.decodeFromString(TaskConfig.serializer(), encoded) as TaskConfig.Timer
        assertFalse(decoded.autoAi)
    }

    @Test
    fun `ci monitor config round trip preserves autoAnalyzeOnFailure`() {
        val config = TaskConfig.CIMonitor(
            repo = "dcs666/rikkahub-turbo",
            branch = "perf/rendering-and-streaming",
            pollIntervalMs = 30_000,
            autoAnalyzeOnFailure = true,
            notifyOnSuccess = false,
        )
        val encoded = json.encodeToString(TaskConfig.serializer(), config)
        val decoded = json.decodeFromString(TaskConfig.serializer(), encoded)
        assertTrue(decoded is TaskConfig.CIMonitor)
        val monitor = decoded as TaskConfig.CIMonitor
        assertEquals("dcs666/rikkahub-turbo", monitor.repo)
        assertEquals("perf/rendering-and-streaming", monitor.branch)
        assertTrue(monitor.autoAnalyzeOnFailure)
        assertFalse(monitor.notifyOnSuccess)
    }

    // 存储格式兼容：数据库里旧格式（无 autoAi 字段）也能解析
    // [FIX] 旧格式同样带 "type" 判别字段（sealed class 序列化必需），
    // 这里缺 autoAi 但保留 type，模拟的是 autoAi 字段引入前的存储格式
    @Test
    fun `legacy timer json without autoAi field parses`() {
        val legacy = """{"type":"timer","delayMs":300000,"message":"old format","repeatIntervalMs":0,"repeatCount":0}"""
        val decoded = json.decodeFromString(TaskConfig.serializer(), legacy) as TaskConfig.Timer
        assertEquals("old format", decoded.message)
        assertFalse(decoded.autoAi)
    }
}
