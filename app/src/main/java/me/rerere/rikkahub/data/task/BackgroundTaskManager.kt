package me.rerere.rikkahub.data.task

import android.app.Application
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.JsonInstant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "BackgroundTaskManager"
private const val CLEANUP_INTERVAL_MS = 3600_000L // 1h
private const val MAX_TASK_AGE_MS = 7 * 24 * 3600_000L // 终态任务保留 7 天
// [FIX] 活跃任务不能按 7 天清理：长定时器/长 CI 监控会被静默删除。活跃任务保留 30 天。
private const val MAX_ACTIVE_TASK_AGE_MS = 30 * 24 * 3600_000L

// [OPT] 动态唤醒间隔的边界与空闲间隔
private const val MIN_POLL_INTERVAL_MS = 2_000L        // PENDING 任务等待下限
private const val MAX_POLL_INTERVAL_MS = 60_000L       // 单次睡眠上限（防失控）
private const val IDLE_POLL_INTERVAL_MS = 30_000L      // 无活跃任务时的空闲间隔
private const val DEFAULT_POLL_INTERVAL_MS = 30_000L   // config 解析失败时的兜底
private const val RECENT_TASKS_LIMIT = 20

// [OPT] 有限并发轮询：GitHub API 调用是 IO 密集，多任务时并行而不是串行排队；
// 3 路并发在速度与 rate limit 之间取平衡（未认证 60 req/h，认证 5000 req/h）。
private const val POLL_CONCURRENCY = 3

// 连续失败/连续 not_found 判定阈值（与总 pollCount 解耦，避免正常轮询稀释错误计数）
private const val CONSECUTIVE_FAILURE_LIMIT = 5
private const val CONSECUTIVE_NOT_FOUND_LIMIT = 10
private const val RATE_LIMIT_BACKOFF_MS = 5 * 60_000L  // 403/429 后强制退避 5 分钟

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
    private var cleanupJob: Job? = null

    @Volatile
    private var stateFlowObserving = false

    // [OPT] 软状态（进程内存，重启丢失可接受）：连续失败计数 / 连续 not_found 计数 /
    // rate limit 退避截止时间。不进 DB 是为了避免 schema 迁移。
    private val consecutiveFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val consecutiveNotFound = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val rateLimitedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // 轮询并发限流器（基于 Dispatchers.IO 的共享线程池）
    private val pollDispatcher = Dispatchers.IO.limitedParallelism(POLL_CONCURRENCY)

    // [OPT] 唤醒信号：新任务创建/取消/删除后立即唤醒 poller，
    // 避免空闲态 30s 睡眠导致新任务首轮 poll 被延迟
    private val pollerWake = Channel<Unit>(Channel.CONFLATED)

    // 活跃任务数量（UI 可观察）
    private val _activeTaskCount = MutableStateFlow(0)
    val activeTaskCount: StateFlow<Int> = _activeTaskCount.asStateFlow()

    // 最近任务列表（UI 可观察）
    private val _recentTasks = MutableStateFlow<List<TaskEntity>>(emptyList())
    val recentTasks: StateFlow<List<TaskEntity>> = _recentTasks.asStateFlow()

    /**
     * 启动轮询循环。在 App 启动时调用。
     * poller 与 cleanup 独立幂等：任一协程意外退出后再次调用 start() 只重启缺失的那个。
     */
    fun start() {
        if (pollerJob?.isActive != true) {
            pollerJob = scope.launch {
                Log.i(TAG, "Task poller started")
                observeTaskFlows()
                refreshState()
                while (isActive) {
                    try {
                        pollActiveTasks()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e // 协程取消必须传播
                    } catch (e: Exception) {
                        Log.e(TAG, "Poll cycle error", e)
                    }
                    // [OPT] 动态唤醒：按所有活跃任务的下一次到期时刻计算睡眠时长，
                    // 不再固定 5s/30s 空转。无任务睡 30s，有任务精确睡到最早到期点
                    // （下限 2s 保证 PENDING 任务立即被 poll，上限 60s 防失控）。
                    // 睡眠可被 pollerWake 信号提前打断（新任务创建/取消时）。
                    val wakeDelay = computeNextWakeDelayMs()
                    select<Unit> {
                        pollerWake.onReceive { /* 被唤醒：立即进入下一轮 */ }
                        onTimeout(wakeDelay) { /* 正常到期 */ }
                    }
                }
            }
        }
        if (cleanupJob?.isActive != true) {
            cleanupJob = scope.launch {
                while (isActive) {
                    delay(CLEANUP_INTERVAL_MS)
                    val now = System.currentTimeMillis()
                    taskDao.cleanupOld(
                        terminalBefore = now - MAX_TASK_AGE_MS,
                        activeBefore = now - MAX_ACTIVE_TASK_AGE_MS,
                    )
                }
            }
        }
    }

    /**
     * 把 DAO 的 Flow 直接桥接到 UI 可观察的 StateFlow：
     * 任务状态/pollCount/新增完成等变化自动实时刷新，无需在每处写库后手动 refreshState。
     */
    private fun observeTaskFlows() {
        if (stateFlowObserving) return
        stateFlowObserving = true
        scope.launch {
            taskDao.observeActiveTasks()
                .map { it.size }
                .distinctUntilChanged()
                .collect { _activeTaskCount.value = it }
        }
        scope.launch {
            taskDao.observeRecentTasks(RECENT_TASKS_LIMIT)
                .collect { _recentTasks.value = it }
        }
    }

    /**
     * 计算下一次唤醒前需要睡眠的毫秒数。
     * 基于数据库中所有活跃任务的状态，取最早到期时刻。
     */
    private suspend fun computeNextWakeDelayMs(): Long {
        val active = taskDao.getActiveTasks()
        if (active.isEmpty()) return IDLE_POLL_INTERVAL_MS

        val now = System.currentTimeMillis()
        var earliestDue = Long.MAX_VALUE
        for (task in active) {
            val due = when (task.type) {
                TaskType.TIMER -> timerDueAt(task)
                else -> ciDueAt(task)
            }
            if (due < earliestDue) earliestDue = due
        }
        val diff = earliestDue - now
        return diff.coerceIn(MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS)
    }

    private fun ciDueAt(task: TaskEntity): Long {
        // rate limit 退避窗口内：睡到窗口结束再 poll，避免每 2s 空转唤醒
        val backoffUntil = rateLimitedUntil[task.id]
        if (backoffUntil != null) return backoffUntil
        if (task.status == TaskStatus.PENDING) return 0L // 立即 poll
        val pollIntervalMs = runCatching {
            (json.decodeFromString(TaskConfig.serializer(), task.config) as? TaskConfig.CIMonitor)
                ?.pollIntervalMs
        }.getOrDefault(DEFAULT_POLL_INTERVAL_MS)
        return task.updatedAt + nextPollDelay(task.pollCount, pollIntervalMs)
    }

    private fun timerDueAt(task: TaskEntity): Long {
        val delayMs = runCatching {
            (json.decodeFromString(TaskConfig.serializer(), task.config) as? TaskConfig.Timer)
                ?.delayMs
        }.getOrDefault(0L)
        val due = task.createdAt + delayMs
        return if (task.status == TaskStatus.PENDING) due else due // 到期后每秒检查一次即可
    }

    fun stop() {
        pollerJob?.cancel()
        pollerJob = null
        cleanupJob?.cancel()
        cleanupJob = null
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
        // 去重：如果已有相同 repo+branch+runId 的活跃任务，直接返回其 ID
        val existingTasks = taskDao.getActiveTasks()
        val duplicate = existingTasks.firstOrNull { task ->
            if (task.type != TaskType.CI_MONITOR) return@firstOrNull false
            try {
                val cfg = json.decodeFromString(TaskConfig.serializer(), task.config) as? TaskConfig.CIMonitor
                    ?: return@firstOrNull false
                cfg.repo.equals(repo, ignoreCase = true) &&
                    cfg.branch.equals(branch, ignoreCase = true) &&
                    cfg.runId == runId
            } catch (_: Exception) { false }
        }
        if (duplicate != null) {
            Log.i(TAG, "CI monitor already exists for $repo@$branch, returning ${duplicate.id}")
            return duplicate.id
        }

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
        pollerWake.trySend(Unit) // 立即唤醒 poller，不等下一个睡眠周期
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
        pollerWake.trySend(Unit)
        return task.id
    }

    /**
     * 取消任务。
     */
    suspend fun cancelTask(taskId: String) {
        taskDao.updateStatus(taskId, TaskStatus.CANCELLED)
        consecutiveFailures.remove(taskId)
        consecutiveNotFound.remove(taskId)
        rateLimitedUntil.remove(taskId)
        refreshState()
        pollerWake.trySend(Unit)
    }

    /**
     * 取消所有活跃任务。
     */
    suspend fun cancelAll() {
        taskDao.cancelAllActive()
        refreshState()
        pollerWake.trySend(Unit)
    }

    /**
     * 删除任务记录（任意状态）。用于清理已完成/失败的历史任务。
     * 返回是否删除成功。
     */
    suspend fun deleteTask(taskId: String): Boolean {
        val task = taskDao.getById(taskId) ?: return false
        taskDao.delete(task)
        consecutiveFailures.remove(taskId)
        consecutiveNotFound.remove(taskId)
        rateLimitedUntil.remove(taskId)
        refreshState()
        pollerWake.trySend(Unit)
        return true
    }

    /**
     * 获取任务详情。
     */
    suspend fun getTask(taskId: String): TaskEntity? = taskDao.getById(taskId)

    /**
     * 获取最近任务。
     */
    suspend fun getRecentTasks(limit: Int = 20): List<TaskEntity> = taskDao.getRecentTasks(limit)

    /**
     * 通过 Webhook 完成 CI 监控任务。
     * 查找匹配的活跃任务（按 repo + branch），立即完成。
     * 返回是否找到并完成了任务。
     */
    suspend fun completeCIMonitorByWebhook(
        repo: String,
        branch: String,
        runId: Long,
        runNumber: Int,
        workflowName: String,
        conclusion: String,
        htmlUrl: String,
        commitMessage: String,
    ): Boolean {
        val activeTasks = taskDao.getActiveTasks()

        // 找到匹配的活跃 CI 任务，同时解码 config 避免重复
        var matchedConfig: TaskConfig.CIMonitor? = null
        val matchingTask = activeTasks.firstOrNull { task ->
            if (task.type != TaskType.CI_MONITOR) return@firstOrNull false
            try {
                val config = json.decodeFromString(TaskConfig.serializer(), task.config) as? TaskConfig.CIMonitor
                    ?: return@firstOrNull false
                val repoMatch = config.repo.equals(repo, ignoreCase = true)
                val branchMatch = config.branch.isBlank() || config.branch.equals(branch, ignoreCase = true)
                val runIdMatch = config.runId == 0L || config.runId == runId
                // [FIX] workflowName 非空时必须匹配：否则监控 "Build APK" 时 "Unit Tests" 先完成，
                // webhook 会错误完成该任务并注入错误 workflow 的结果
                val workflowMatch = config.workflowName.isBlank() ||
                    config.workflowName.equals(workflowName, ignoreCase = true)
                if (repoMatch && branchMatch && runIdMatch && workflowMatch) {
                    matchedConfig = config
                    true
                } else false
            } catch (_: Exception) {
                false
            }
        } ?: return false

        val success = conclusion == "success"
        val result = CITaskResult(
            runId = runId,
            runNumber = runNumber,
            status = "completed",
            conclusion = conclusion,
            workflowName = workflowName,
            branch = branch,
            commitMessage = commitMessage,
            htmlUrl = htmlUrl,
        )

        // 获取失败日志
        val failedJobs = if (!success) {
            try {
                gitHubClient.getFailedJobLogs(repo, runId, matchedConfig?.githubToken ?: "")
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()

        val finalResult = result.copy(failedJobs = failedJobs)

        completeTask(
            matchingTask,
            success = success,
            resultJson = json.encodeToString(CITaskResult.serializer(), finalResult),
            config = matchedConfig,
        )
        pollerWake.trySend(Unit) // 完成活跃任务后立即让 poller 重算睡眠（少一个任务）
        return true
    }

    // ---- 内部轮询逻辑 ----

    /**
     * [OPT] 有限并发并行轮询：多个 CI 任务各自是独立网络请求，串行会让
     * 慢任务阻塞整批（10 个任务 × 1-3s = 一轮 10-30s，超过轮询间隔造成积压）。
     * 每个任务仍独立 try-catch，单个异常不影响其他任务。
     */
    private suspend fun pollActiveTasks() {
        val activeTasks = taskDao.getActiveTasks()
        _activeTaskCount.value = activeTasks.size

        if (activeTasks.isEmpty()) return

        coroutineScope {
            activeTasks.map { task ->
                async(pollDispatcher) {
                    try {
                        when (task.type) {
                            TaskType.CI_MONITOR -> pollCITask(task)
                            TaskType.TIMER -> pollTimerTask(task)
                            else -> { /* webhook/custom 暂不轮询 */ }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error polling task ${task.id}", e)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun pollCITask(task: TaskEntity) {
        // [OPT] rate limit 退避窗口内直接跳过（不递增 pollCount，不计失败）
        val backoffUntil = rateLimitedUntil[task.id]
        if (backoffUntil != null) {
            if (System.currentTimeMillis() < backoffUntil) return
            rateLimitedUntil.remove(task.id)
        }

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

        // 检查是否到了下次轮询时间（带指数退避）
        val elapsed = System.currentTimeMillis() - task.updatedAt
        val requiredDelay = nextPollDelay(task.pollCount, config.pollIntervalMs)
        if (task.status == TaskStatus.RUNNING && elapsed < requiredDelay) {
            return // 还没到时间
        }

        // 标记为 running（条件更新：仅 PENDING→RUNNING，防止覆盖 webhook 已完成的状态）
        if (task.status == TaskStatus.PENDING) {
            val updated = taskDao.markRunningIfPending(task.id)
            if (updated == 0) return // 已被 webhook 完成/取消，放弃轮询
        }

        // 执行轮询
        val result = withContext(Dispatchers.IO) {
            gitHubClient.getLatestRun(config)
        }

        result.fold(
            onSuccess = { ciResult ->
                when (ciResult.status) {
                    "completed" -> {
                        consecutiveFailures.remove(task.id)
                        consecutiveNotFound.remove(task.id)
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
                        // 还没找到 run，继续等；但连续找不到说明 repo/branch/workflow 可能写错，
                        // 达到阈值时给出明确错误而不是干等到 maxPollCount
                        val streak = (consecutiveNotFound.merge(task.id, 1, Int::plus) ?: 1)
                        if (streak >= CONSECUTIVE_NOT_FOUND_LIMIT) {
                            consecutiveNotFound.remove(task.id)
                            completeTask(
                                task,
                                success = false,
                                error = "No workflow run found for ${config.repo}@${config.branch.ifBlank { "any" }}" +
                                    " (workflow: ${config.workflowName.ifBlank { "any" }}) after $streak checks"
                            )
                        } else {
                            incrementPollCount(task)
                        }
                    }
                    else -> {
                        // queued / in_progress，继续等（正常状态，清除错误计数）
                        consecutiveFailures.remove(task.id)
                        consecutiveNotFound.remove(task.id)
                        incrementPollCount(task)
                    }
                }
            },
            onFailure = { e ->
                Log.w(TAG, "CI poll error for task ${task.id}: ${e.message}")
                // [OPT] rate limit 命中：强制退避 5 分钟，不递增轮询计数
                if (gitHubClient.isRateLimitError(e)) {
                    rateLimitedUntil[task.id] = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                    Log.w(TAG, "GitHub rate limit hit for task ${task.id}, backing off ${RATE_LIMIT_BACKOFF_MS / 1000}s")
                    return@fold
                }
                // [OPT] 连续失败独立计数：网络抖动重试，但持续失败要尽早暴露
                val streak = (consecutiveFailures.merge(task.id, 1, Int::plus) ?: 1)
                if (streak >= CONSECUTIVE_FAILURE_LIMIT) {
                    consecutiveFailures.remove(task.id)
                    completeTask(task, success = false, error = "Repeated poll errors ($streak consecutive): ${e.message}")
                } else {
                    incrementPollCount(task)
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
            completeTask(task, success = true, resultJson = kotlinx.serialization.json.buildJsonObject {
                put("message", kotlinx.serialization.json.JsonPrimitive(config.message))
            }.toString())
        } else if (task.status == TaskStatus.PENDING) {
            taskDao.markRunningIfPending(task.id)
        }
    }

    private suspend fun incrementPollCount(task: TaskEntity) {
        taskDao.update(task.copy(
            pollCount = task.pollCount + 1,
            updatedAt = System.currentTimeMillis(),
            status = TaskStatus.RUNNING,
        ))
    }

    /**
     * 计算带指数退避的下次轮询间隔。
     * 前 5 次用配置的 pollIntervalMs，之后逐步增加（最大 5 分钟）。
     */
    private fun nextPollDelay(pollCount: Int, baseIntervalMs: Long): Long =
        computeNextPollDelay(pollCount, baseIntervalMs)

    private suspend fun completeTask(
        task: TaskEntity,
        success: Boolean,
        resultJson: String = "",
        error: String = "",
        config: TaskConfig.CIMonitor? = null,
    ) {
        // 防重入：如果任务已经被完成/取消（例如 webhook 先到达），不再重复处理
        val current = taskDao.getById(task.id)
        if (current == null || current.status == TaskStatus.COMPLETED ||
            current.status == TaskStatus.FAILED || current.status == TaskStatus.CANCELLED
        ) {
            return
        }

        val now = System.currentTimeMillis()
        taskDao.update(current.copy(
            status = if (success) TaskStatus.COMPLETED else TaskStatus.FAILED,
            result = resultJson,
            errorMessage = error,
            updatedAt = now,
            completedAt = now,
        ))
        // 清理软状态，防止 Map 泄漏
        consecutiveFailures.remove(task.id)
        consecutiveNotFound.remove(task.id)
        rateLimitedUntil.remove(task.id)
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
                    val timerConfig = JsonInstant.decodeFromString(TaskConfig.serializer(), task.config) as? TaskConfig.Timer
                    "⏰ Timer: ${timerConfig?.message ?: "completed"}"
                } catch (_: Exception) {
                    "⏰ Timer completed"
                }
            }
            else -> "Task completed"
        }
    }

    private suspend fun refreshState() {
        _activeTaskCount.value = taskDao.countActive()
        _recentTasks.value = taskDao.getRecentTasks(20)
    }
}

/**
 * 计算带指数退避的下次轮询间隔（顶层函数便于单元测试）。
 * 前 5 次用配置的 pollIntervalMs，之后逐步增加（最大 5 分钟）。
 */
internal fun computeNextPollDelay(pollCount: Int, baseIntervalMs: Long): Long {
    return when {
        pollCount < 5 -> baseIntervalMs
        pollCount < 10 -> baseIntervalMs * 2
        pollCount < 20 -> baseIntervalMs * 3
        else -> minOf(baseIntervalMs * 5, 300_000L) // 最大 5 分钟
    }
}
