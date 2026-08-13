package me.rerere.common.android

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

private const val MAX_RECENT_LOGS = 100

@Serializable
sealed class LogEntry {
    abstract val id: Uuid
    abstract val timestamp: Long
    abstract val tag: String

    @Serializable
    data class TextLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val message: String
    ) : LogEntry()

    @Serializable
    data class RequestLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val url: String,
        val method: String,
        val requestHeaders: Map<String, String> = emptyMap(),
        val requestBody: String? = null,
        val responseCode: Int? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        val durationMs: Long? = null,
        val error: String? = null,
        val model: String? = null,
        val effort: String? = null,
        val stream: Boolean? = null,
        val purpose: String? = null
    ) : LogEntry()
}

object Logging {
    private val recentLogs = arrayListOf<LogEntry>()
    @Volatile
    private var requestLoggingEnabled = false
    // [实时刷新] 日志变更监听器：LogPage 等 UI 订阅，新增日志时即时通知
    // （common 模块无 coroutines 依赖，用监听器而非 Flow；CopyOnWriteArrayList 读多写少）
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    // 通知进行中标志：防止 listener 内部再次 log() 造成无限递归（同线程同步回调）
    private val notifying = java.util.concurrent.atomic.AtomicBoolean(false)

    fun log(tag: String, message: String) {
        addLog(LogEntry.TextLog(tag = tag, message = message))
    }

    fun logRequest(entry: LogEntry.RequestLog) {
        if (!requestLoggingEnabled) return
        addLog(entry)
    }

    fun isRequestLoggingEnabled(): Boolean = requestLoggingEnabled

    fun setRequestLoggingEnabled(enabled: Boolean) {
        requestLoggingEnabled = enabled
    }

    fun addLogListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeLogListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun addLog(entry: LogEntry) {
        synchronized(recentLogs) {
            recentLogs.add(0, entry)
            if (recentLogs.size > MAX_RECENT_LOGS) {
                recentLogs.removeLastOrNull()
            }
        }
        notifyListeners()
    }

    // 锁外通知监听器（避免 UI 回调阻塞日志写入）。两道防线：
    // 1. 异常隔离：listener 抛异常不得传播到 log() 调用线程（日志可能来自网络/协程热路径）
    // 2. 递归防护：listener 内再调 log() 时跳过本轮通知（外层通知结束后 UI 全量读取仍是最新快照）
    private fun notifyListeners() {
        if (!notifying.compareAndSet(false, true)) return
        try {
            listeners.forEach { listener ->
                try {
                    listener()
                } catch (_: Throwable) {
                    // 订阅者异常不阻塞日志写入
                }
            }
        } finally {
            notifying.set(false)
        }
    }

    fun getRecentLogs(): List<LogEntry> {
        synchronized(recentLogs) {
            return recentLogs.toList()
        }
    }

    fun getTextLogs(): List<LogEntry.TextLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.TextLog>()
        }
    }

    fun getRequestLogs(): List<LogEntry.RequestLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.RequestLog>()
        }
    }

    fun clear() {
        synchronized(recentLogs) {
            recentLogs.clear()
        }
        // 清空也是变更：通知监听器，订阅方无需各自手动兜底
        notifyListeners()
    }
}
