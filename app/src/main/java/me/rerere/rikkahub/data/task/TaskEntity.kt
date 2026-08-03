package me.rerere.rikkahub.data.task

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 后台任务实体，持久化到 Room。
 * 支持 CI 监控、Webhook、定时器等任务类型。
 */
@Entity(
    tableName = "background_tasks",
    indices = [
        androidx.room.Index(value = ["status"]),
        androidx.room.Index(value = ["created_at"]),
        androidx.room.Index(value = ["conversation_id"]),
    ]
)
data class TaskEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo("type")
    val type: String, // "ci_monitor", "webhook", "timer", "custom"

    @ColumnInfo("status")
    val status: String, // "pending", "running", "completed", "failed", "cancelled"

    @ColumnInfo("config")
    val config: String, // JSON config

    @ColumnInfo("result", defaultValue = "")
    val result: String, // JSON result

    @ColumnInfo("conversation_id", defaultValue = "")
    val conversationId: String, // linked conversation UUID string

    @ColumnInfo("created_at")
    val createdAt: Long,

    @ColumnInfo("updated_at")
    val updatedAt: Long,

    @ColumnInfo("completed_at", defaultValue = "0")
    val completedAt: Long = 0,

    @ColumnInfo("error_message", defaultValue = "")
    val errorMessage: String = "",

    @ColumnInfo("poll_count", defaultValue = "0")
    val pollCount: Int = 0,
)

// ---- 任务配置模型 ----

@Serializable
sealed class TaskConfig {
    @Serializable
    @SerialName("ci_monitor")
    data class CIMonitor(
        val repo: String, // "dcs666/rikkahub-turbo"
        val branch: String = "",
        val runId: Long = 0, // 0 = latest
        val workflowName: String = "",
        val pollIntervalMs: Long = 30_000,
        val maxPollCount: Int = 120, // 最多轮询次数 (30s * 120 = 1h)
        val autoAnalyzeOnFailure: Boolean = true,
        val notifyOnSuccess: Boolean = true,
        val githubToken: String = "", // optional, for private repos
        // [B flaky 自动重试] time_out 结论已自动 rerun 过一次（防死循环）
        val autoRetried: Boolean = false,
        // [FIX] auto-rerun 后旧 run 的 id：GitHub rerun 会生成新 run（新 id，run_number 不变），
        // 旧 run 保持原 timed_out 结论。runId 重置为 0 重新解析最新 run 后，
        // 在新 run 注册的窗口期内 latest 仍可能是旧 run，用 skipRunId 精确跳过它。
        val skipRunId: Long = 0,
    ) : TaskConfig()

    @Serializable
    @SerialName("webhook")
    data class Webhook(
        val url: String,
        val method: String = "POST",
        val headers: Map<String, String> = emptyMap(),
        val body: String = "",
    ) : TaskConfig()

    @Serializable
    @SerialName("timer")
    data class Timer(
        val delayMs: Long,
        val message: String = "",
        // [⑥ 重复定时器] 0 = 一次性；>0 = 首次 delayMs 后每 repeatIntervalMs 重复
        val repeatIntervalMs: Long = 0,
        // 总触发次数上限（0 = 无限，仅 repeatIntervalMs > 0 时有意义）
        val repeatCount: Int = 0,
        // [⑨ 定时 AI 动作] 到期时注入对话并触发 AI 把 message 当作指令执行
        val autoAi: Boolean = false,
    ) : TaskConfig()

    @Serializable
    @SerialName("custom")
    data class Custom(
        val command: String,
        val args: Map<String, String> = emptyMap(),
    ) : TaskConfig()
}

// ---- 任务结果模型 ----

@Serializable
data class CITaskResult(
    val runId: Long = 0,
    val runNumber: Int = 0,
    val status: String = "", // "completed", "in_progress", "queued"
    val conclusion: String = "", // "success", "failure", "cancelled", "timed_out"
    val workflowName: String = "",
    val branch: String = "",
    val commitMessage: String = "",
    val commitSha: String = "",
    val htmlUrl: String = "",
    val startedAt: String = "",
    val completedAt: String = "",
    val failedJobs: List<FailedJob> = emptyList(),
)

@Serializable
data class FailedJob(
    val name: String,
    val conclusion: String,
    val htmlUrl: String = "",
    val errorSummary: String = "",
)

// ---- 状态常量 ----

object TaskStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

object TaskType {
    const val CI_MONITOR = "ci_monitor"
    const val WEBHOOK = "webhook"
    const val TIMER = "timer"
    const val CUSTOM = "custom"
}
