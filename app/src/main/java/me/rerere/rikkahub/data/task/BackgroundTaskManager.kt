package me.rerere.rikkahub.data.task

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

private const val TAG = "BackgroundTaskManager"
private const val CLEANUP_INTERVAL_MS = 3600_000L // 1h
private const val MAX_TASK_AGE_MS = 7 * 24 * 3600_000L // 7 days

/**
 * 后台任务管理器。
 *
 * 职责：
 * 1. 管理任务生命周期（创建、轮询、完成、取消）
 * 2. 轮询 GitHub Actions 状态
 * 3. 任务完成时发出事件（通知 + EventBus + 可选自动 AI 生成）
 *
 * 设计：
 * - 单例，由 Koin 注入
 * - 内部维护一个轮询协程，每 5s 检查一次是否有活跃任务需要 poll
 * - 每个 CI 任务有自己的 pollInterval，通过 pollCount 控制
 * - 任务状态持久化到 Room，进程重启后恢复
 */
class BackgroundTaskManager(
    private val app: Application,
    private val taskDao: TaskDao,
    private val eventBus: AppEventBus,
    private val gitHubClient: GitHubActionsClient = GitHubActionsClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json: Json = JsonInstant

    private var pollerJob: Job? = null

    // 活跃任务数量（UI 可观察）
    private val _activeTaskCount = MutableStateFlow(0)
    val activeTaskCount: StateFlow<Int> = _activeTaskCount.asStateFlow()

    // 最近任务列表（UI 可观察）
    private val _recentTasks = MutableStateFlow<List<TaskEntity>>(emptyList())
    val recentTasks: StateFlow<List<TaskEntity>> = _recentTasks.asStateFlow()

    /**
     * 启动轮询循环。在 App 启动时调用。
     */
    fun start() {
        if (pollerJob?.isActive == true) return
        pollerJob = scope.launch {
            Log.i(TAG, "Task poller started")
            refreshState()
            while (isActive) {
                try {
                    pollActiveTasks()
                } catch (e: Exception) {
                    Log.e(TAG, "Poll cycle error", e)
                }
                delay(5_000) // 每 5s 检查一次
            }
        }
        // 定期清理旧任务
        scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                taskDao.cleanupOld(System.currentTimeMillis() - MAX_TASK_AGE_MS)
            }
        }
    }

    fun stop() {
        pollerJob?.cancel()
        pollerJob = null
    }

    // ---- 公开 API ----

    /**
     * 创建 CI 监控任务。
     * 返回任务 ID。
     */
    suspend fun createCIMonitorTask(
        repo: String,
        branch: String = "",
        runId: Long = 0,
        workflowName: String = "",
        conversationId: String = "",
        pollIntervalMs: Long = 30_000,
        autoAnalyzeOnFailure: Boolean = true,
        notifyOnSuccess: Boolean = true,
        githubToken: String = "",
    ): String {
        val config = TaskConfig.CIMonitor(
            repo = repo,
            branch = branch,
            runId = runId,
            workflowName = workflowName,
            pollIntervalMs = pollIntervalMs,
            autoAnalyzeOnFailure = autoAnalyzeOnFailure,
            notifyOnSuccess = notifyOnSuccess,
            githubToken = githubToken,
        )
        val task = TaskEntity(
            id = Uuid.random().toString(),
            type = TaskType.CI_MONITOR,
            status = TaskStatus.PENDING,
            config = json.encodeToString(TaskConfig.serializer(), config),
            result = "",
            conversationId = conversationId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        taskDao.insert(task)
        refreshState()
        Log.i(TAG, "Created CI monitor task: ${task.id} for $repo")
        return task.id
    }

    /**
     * 创建定时任务。
     */
    suspend fun createTimerTask(
        delayMs: Long,
        message: String,
        conversationId: String = "",
    ): String {
        val config = TaskConfig.Timer(delayMs = delayMs, message = message)
        val task = TaskEntity(
            id = Uuid.random().toString(),
            type = TaskType.TIMER,
            status = TaskStatus.PENDING,
            config = json.encodeToString(TaskConfig.serializer(), config),
            result = "",
            conversationId = conversationId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        taskDao.insert(task)
        refreshState()
        return task.id
    }

    /**
     * 取消任务。
     */
    suspend fun cancelTask(taskId: String) {
        taskDao.updateStatus(taskId, TaskStatus.CANCELLED)
        refreshState()
    }

    /**
     * 取消所有活跃任务。
     */
    suspend fun cancelAll() {
        taskDao.cancelAllActive()
        refreshState()
    }

    /**
     * 获取任务详情。
     */
    suspend fun getTask(taskId: String): TaskEntity? = taskDao.getById(taskId)

    /**
     * 获取最近任务。
     */
    suspend fun getRecentTasks(limit: Int = 20): List<TaskEntity> = taskDao.getRecentTasks(limit)

    // ---- 内部轮询逻辑 ----

    private suspend fun pollActiveTasks() {
        val activeTasks = taskDao.getActiveTasks()
        _activeTaskCount.value = activeTasks.size

        for (task in activeTasks) {
            when (task.type) {
                TaskType.CI_MONITOR -> pollCITask(task)
                TaskType.TIMER -> pollTimerTask(task)
                else -> { /* webhook/custom 暂不轮询 */ }
            }
        }
    }

    private suspend fun pollCITask(task: TaskEntity) {
        val config = try {
            (json.decodeFromString(TaskConfig.serializer(), task.config) as? TaskConfig.CIMonitor)
                ?: run {
                    completeTask(task, success = false, error = "Invalid config type")
                    return
                }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid CI config for task ${task.id}", e)
            completeTask(task, success = false, error = "Invalid config")
            return
        }

        // 检查是否超过最大轮询次数
        if (task.pollCount >= config.maxPollCount) {
            completeTask(task, success = false, error = "Max poll count reached (${config.maxPollCount})")
            return
        }

        // 检查是否到了下次轮询时间
        val elapsed = System.currentTimeMillis() - task.updatedAt
        if (task.status == TaskStatus.RUNNING && elapsed < config.pollIntervalMs) {
            return // 还没到时间
        }

        // 标记为 running
        if (task.status == TaskStatus.PENDING) {
            taskDao.updateStatus(task.id, TaskStatus.RUNNING)
        }

        // 执行轮询
        val result = withContext(Dispatchers.IO) {
            gitHubClient.getLatestRun(config)
        }

        result.fold(
            onSuccess = { ciResult ->
                when (ciResult.status) {
                    "completed" -> {
                        // 获取失败日志
                        val failedJobs = if (ciResult.conclusion == "failure") {
                            withContext(Dispatchers.IO) {
                                gitHubClient.getFailedJobLogs(config.repo, ciResult.runId, config.githubToken)
                            }
                        } else emptyList()

                        val finalResult = ciResult.copy(failedJobs = failedJobs)
                        val success = ciResult.conclusion == "success"
                        completeTask(
                            task,
                            success = success,
                            resultJson = json.encodeToString(CITaskResult.serializer(), finalResult),
                            config = config,
                        )
                    }
                    "not_found" -> {
                        // 还没找到 run，继续等
                        incrementPollCount(task)
                    }
                    else -> {
                        // queued / in_progress，继续等
                        incrementPollCount(task)
                    }
                }
            },
            onFailure = { e ->
                Log.w(TAG, "CI poll error for task ${task.id}: ${e.message}")
                // 网络错误不立即失败，重试
                incrementPollCount(task)
                // 连续错误太多则失败
                if (task.pollCount > 10) {
                    completeTask(task, success = false, error = "Repeated poll errors: ${e.message}")
                }
            }
        )
    }

    private suspend fun pollTimerTask(task: TaskEntity) {
        val config = try {
            (json.decodeFromString(TaskConfig.serializer(), task.config) as? TaskConfig.Timer)
                ?: run {
                    completeTask(task, success = false, error = "Invalid timer config type")
                    return
                }
        } catch (e: Exception) {
            completeTask(task, success = false, error = "Invalid timer config")
            return
        }

        val elapsed = System.currentTimeMillis() - task.createdAt
        if (elapsed >= config.delayMs) {
            completeTask(task, success = true, resultJson = """{"message":"${config.message}"}""")
        } else if (task.status == TaskStatus.PENDING) {
            taskDao.updateStatus(task.id, TaskStatus.RUNNING)
        }
    }

    private suspend fun incrementPollCount(task: TaskEntity) {
        taskDao.update(task.copy(
            pollCount = task.pollCount + 1,
            updatedAt = System.currentTimeMillis(),
            status = TaskStatus.RUNNING,
        ))
    }

    private suspend fun completeTask(
        task: TaskEntity,
        success: Boolean,
        resultJson: String = "",
        error: String = "",
        config: TaskConfig.CIMonitor? = null,
    ) {
        val now = System.currentTimeMillis()
        taskDao.update(task.copy(
            status = if (success) TaskStatus.COMPLETED else TaskStatus.FAILED,
            result = resultJson,
            errorMessage = error,
            updatedAt = now,
            completedAt = now,
        ))
        refreshState()

        // 发出事件
        val event = AppEvent.BackgroundTaskCompleted(
            taskId = task.id,
            taskType = task.type,
            success = success,
            conversationId = task.conversationId,
            resultSummary = buildResultSummary(task, resultJson, error, config),
        )
        eventBus.emit(event)

        Log.i(TAG, "Task ${task.id} completed: success=$success")
    }

    private fun buildResultSummary(
        task: TaskEntity,
        resultJson: String,
        error: String,
        config: TaskConfig.CIMonitor?,
    ): String {
        if (error.isNotBlank()) return "❌ $error"

        return when (task.type) {
            TaskType.CI_MONITOR -> {
                try {
                    val result = JsonInstant.decodeFromString<CITaskResult>(resultJson)
                    buildString {
                        if (result.conclusion == "success") {
                            append("✅ CI passed: ")
                            append(result.workflowName)
                            append(" #").append(result.runNumber)
                            append(" (").append(result.branch).append(")")
                        } else {
                            append("❌ CI failed: ")
                            append(result.workflowName)
                            append(" #").append(result.runNumber)
                            append(" (").append(result.branch).append(")")
                            if (result.commitMessage.isNotBlank()) {
                                append("\nCommit: ").append(result.commitMessage)
                            }
                            result.failedJobs.forEach { job ->
                                append("\n\nFailed job: ").append(job.name)
                                if (job.errorSummary.isNotBlank()) {
                                    append("\n```\n").append(job.errorSummary.take(1000)).append("\n```")
                                }
                            }
                            if (result.htmlUrl.isNotBlank()) {
                                append("\n\nDetails: ").append(result.htmlUrl)
                            }
                        }
                    }
                } catch (_: Exception) {
                    "CI task completed"
                }
            }
            TaskType.TIMER -> {
                try {
                    val timerConfig = JsonInstant.decodeFromString<TaskConfig.Timer>(task.config)
                    "⏰ Timer: ${timerConfig.message}"
                } catch (_: Exception) {
                    "⏰ Timer completed"
                }
            }
            else -> "Task completed"
        }
    }

    private suspend fun refreshState() {
        _activeTaskCount.value = taskDao.countActive()
        _recentTasks.value = taskDao.getRecentTasks(10)
    }
}
