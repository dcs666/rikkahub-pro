package me.rerere.common.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoggingTest {
    private val listeners = mutableListOf<() -> Unit>()

    @Before
    fun setUp() {
        Logging.clear()
    }

    @After
    fun tearDown() {
        listeners.forEach { Logging.removeLogListener(it) }
        listeners.clear()
        Logging.clear()
    }

    private fun trackListener(): () -> Unit {
        val listener = { }
        listeners.add(listener)
        Logging.addLogListener(listener)
        return listener
    }

    @Test
    fun `addLog 头插最新在前`() {
        Logging.log("tag1", "msg1")
        Logging.log("tag2", "msg2")
        Logging.log("tag3", "msg3")

        val logs = Logging.getTextLogs()
        assertEquals(listOf("msg3", "msg2", "msg1"), logs.map { it.message })
        assertEquals(listOf("tag3", "tag2", "tag1"), logs.map { it.tag })
    }

    @Test
    fun `超过上限裁剪到 100 条`() {
        repeat(150) { Logging.log("tag", "msg$it") }

        val logs = Logging.getTextLogs()
        assertEquals(100, logs.size)
        // 保留最新的 100 条（msg149 .. msg50）
        assertEquals("msg149", logs.first().message)
        assertEquals("msg50", logs.last().message)
    }

    @Test
    fun `getRecentLogs 返回快照不受后续写入影响`() {
        Logging.log("tag", "msg1")
        val snapshot = Logging.getRecentLogs()
        Logging.log("tag", "msg2")
        Logging.clear()

        assertEquals(1, snapshot.size)
        assertEquals("msg1", snapshot.first().message)
        // 原始快照元素不受 clear 影响（独立实例）
        assertTrue(snapshot.first() is LogEntry.TextLog)
    }

    @Test
    fun `listener 在日志写入后被调用`() {
        var notified = 0
        val listener = { notified++ }
        listeners.add(listener)
        Logging.addLogListener(listener)

        Logging.log("tag", "msg")
        Logging.logRequest(LogEntry.RequestLog(tag = "net", url = "https://example.com", method = "GET"))

        assertEquals(2, notified)
    }

    @Test
    fun `listener 异常不传播到调用方`() {
        val boom = { throw IllegalStateException("listener boom") }
        listeners.add(boom)
        Logging.addLogListener(boom)

        // 不得抛异常
        Logging.log("tag", "msg")
        // 且其他 listener 仍被调用
        var otherNotified = 0
        val other = { otherNotified++ }
        listeners.add(other)
        Logging.addLogListener(other)
        Logging.log("tag", "msg2")
        assertEquals(1, otherNotified)
    }

    @Test
    fun `listener 内再次 log 不死循环`() {
        var innerCalls = 0
        val listener = {
            innerCalls++
            // 递归写日志：外层通知进行中应跳过本轮通知
            if (innerCalls < 3) Logging.log("tag", "inner")
        }
        listeners.add(listener)
        Logging.addLogListener(listener)

        Logging.log("tag", "outer")
        // 递归防护应终止：listener 只被调 1 次（外层通知期间内层通知被跳过）
        assertEquals(1, innerCalls)
        // 日志本身都写入了（写入不走通知）：outer + 1 个 inner
        assertEquals(2, Logging.getTextLogs().size)
    }

    @Test
    fun `clear 通知监听器`() {
        var notified = 0
        val listener = { notified++ }
        listeners.add(listener)
        Logging.addLogListener(listener)

        Logging.clear()

        assertEquals(1, notified)
    }

    @Test
    fun `logRequest 受开关控制`() {
        Logging.setRequestLoggingEnabled(false)
        Logging.logRequest(LogEntry.RequestLog(tag = "net", url = "https://example.com", method = "GET"))
        assertEquals(0, Logging.getRequestLogs().size)

        Logging.setRequestLoggingEnabled(true)
        Logging.logRequest(LogEntry.RequestLog(tag = "net", url = "https://example.com", method = "GET"))
        assertEquals(1, Logging.getRequestLogs().size)
        assertEquals("GET", Logging.getRequestLogs().first().method)
    }

    @Test
    fun `removeLogListener 后不再通知`() {
        var notified = 0
        val listener = { notified++ }
        listeners.add(listener)
        Logging.addLogListener(listener)

        Logging.log("tag", "msg1")
        Logging.removeLogListener(listener)
        Logging.log("tag", "msg2")

        assertEquals(1, notified)
    }

    @Test
    fun `getRequestLogs 只返回请求日志`() {
        Logging.setRequestLoggingEnabled(true)
        Logging.log("tag", "text-only")
        Logging.logRequest(LogEntry.RequestLog(tag = "net", url = "https://example.com", method = "POST"))

        assertEquals(1, Logging.getRequestLogs().size)
        assertEquals(1, Logging.getTextLogs().size)
        assertEquals(2, Logging.getRecentLogs().size)
    }
}
