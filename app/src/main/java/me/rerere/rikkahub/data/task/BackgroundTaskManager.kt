package me.rerere.rikkahub.data.task

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.TaskKeepAliveService
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.java.KoinJavaComponent.getKoin
import java.io.IOException
import kotlin.uuid.Uuid

private const val TAG = "BackgroundTaskManager"
private const val CLEANUP_INTERVAL_MS = 3600_000L // 1h
private const val MAX_TASK_AGE_MS = 7 * 24 * 3600_000L // 终态任务保留 7 天
private const val MAX_ACTIVE_TASK_AGE_MS = 30 * 24 * 3600_000L
private const val MIN_POLL_INTERVAL_MS = 2_000L        // PENDING 任务等待下限
private const val MAX_POLL_INTERVAL_MS = 60_000L       // 单次睡眠上限（防失控）
private const val IDLE_POLL_INTERVAL_MS = 30_000L      // 无活跃任务时的空闲间隔
private const val RECENT_TASKS_LIMIT = 20
private const val POLL_CONCURRENCY = 3

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
 * - 内部维护轮询协程：按所有活跃任务的最早到期时刻动态睡眠（2s-60s），
 *   空闲睡 30s；可被唤醒信号（新任务创建/取消/webhook 完成）提前打断
 * - 每个 CI 任务有自己的 pollInterval，通过 pollCount 控制指数退避
 * - 有限并发（3 路）并行 poll，多任务不互相阻塞
 * - 任务状态持久化到 Room，进程重启后恢复
 *
 * [拆分]（Strangler Fig）：轮询执行域委托给独立类，本类保留调度/任务 CRUD/
 * webhook/完成事件等编排逻辑：
 * - CI 轮询域 → [CiTaskPoller]（pollCITask + 连续失败/not_found/rate-limit 软状态）
 * - 定时器轮询域 → [TimerTaskPoller]（pollTimerTask + 注入重试/重复调度）
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

    // [TURBO M1] 保活观察协程只启动一次
    @Volatile
    private var keepAliveObserving = false

    // 轮询并发限流器（基于 Dispatchers.IO 的共享线程池）
    private val pollDispatcher = Dispatchers.IO.limitedParallelism(POLL_CONCURRENCY)

    // 懒加载避免构造环（ChatService 不依赖本类，但防御性用 Koin 懒取）
    private val chatService: ChatService by lazy { getKoin().get() }

    // [OPT] 唤醒信号：新任务创建/取消/删除后立即唤醒 poller，
    // 避免空闲态 30s 睡眠导致新任务首轮 poll 被延迟
    private val pollerWake = Channel<Unit>(Channel.CONFLATED)

    // [拆分] CI 轮询域
    private val ciPoller = CiTaskPoller(
        json = json,
        taskDao = taskDao,
        gitHubClient = gitHubClient,
        onCompleteTask = ::completeTask,
        onIncrementPollCount = ::incrementPollCount,
        onWakePoller = { pollerWake.trySend(Unit) },
    )

    // [拆分] 定时器轮询域
    private val timerPoller = TimerTaskPoller(
        json = json,
        taskDao = taskDao,
        chatService = { chatService },
        onCompleteTask = ::completeTask,
        onWakePoller = { pollerWake.trySend(Unit) },
    )

    // 活跃任务数量（UI 可观察）
    private val _activeTaskCount = MutableStateFlow(0)
    val activeTaskCount: StateFlow<Int> = _activeTaskCount.asStateFlow()

    // 最近任务列表（UI 可观察）
    private val _recentTasks = MutableStateFlow<List<TaskEntity>>(emptyList())
    val recentTasks: StateFlow<List<TaskEntity>> = _recentTasks.asStateFlow()

    /**
     * 启动轮询循环。在 App 启动时调用。
     * poller 与 cleanup 独立幂等：
     */
    fun start() {
        // [TURBO M1] 任务保活：有活跃任务时拉起前台服务，防止进程被回收导致任务中断。
        // 服务自身观察 activeTaskCount，归零后自停（见 TaskKeepAliveService）。
        if (!keepAliveObserving) {
            keepAliveObserving = true
            scope.launch {
                _activeTaskCount.collectLatest { count ->
                    if (count > 0) {
                        TaskKeepAliveService.start(app)
                    }
                }
            }
        }
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
                TaskType.TIMER -> timerPoller.dueAt(task)
                else -> ciPoller.dueAt(task)
            }
            if (due < earliestDue) earliestDue = due
        }
        val diff = earliestDue - now
        return diff.coerceIn(MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS)
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
        val taskId = Uuid.random().toString()

        val config = TaskConfig.CIMonitor(
            repo = repo,
            branch = branch,
            runId = runId,
            workflowName = workflowName,
            pollIntervalMs = pollIntervalMs,
            maxPollCount = 120, // 10min @30s
            autoAnalyzeOnFailure = autoAnalyzeOnFailure,
            notifyOnSuccess = notifyOnSuccess,
            githubToken = githubToken,
        )
        taskDao.insert(
            TaskEntity(
                id = taskId,
                type = TaskType.CI_MONITOR,
                status = TaskStatus.PENDING,
                config = json.encodeToString(TaskConfig.serializer(), config),
                result = "",
                conversationId = conversationId,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        )
        pollerWake.trySend(Unit)
        return taskId
    }

    /**
     * 创建定时任务。
     * 返回任务 ID。
     */
    suspend fun createTimerTask(
        message: String,
        delayMs: Long,
        conversationId: String = "",
        autoAi: Boolean = false,
        steps: List<String> = emptyList(),
        repeatIntervalMs: Long = 0L,
        repeatCount: Int = 0,
    ): String {
        val taskId = Uuid.random().toString()

        val config = TaskConfig.Timer(
            message = message,
            delayMs = delayMs,
            autoAi = autoAi,
            steps = steps,
            repeatIntervalMs = repeatIntervalMs,
            repeatCount = repeatCount,
        )
        taskDao.insert(
            TaskEntity(
                id = taskId,
                type = TaskType.TIMER,
                status = TaskStatus.PENDING,
                config = json.encodeToString(TaskConfig.serializer(), config),
                result = "",
                conversationId = conversationId,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        )
        pollerWake.trySend(Unit)
        return taskId
    }

    /**
     * 取消任务（PENDING/RUNNING → CANCELLED）。
     */
    suspend fun cancelTask(taskId: String): Boolean {
        val updated = taskDao.cancelIfActive(taskId, System.currentTimeMillis())
        if (updated > 0) {
            ciPoller.clearState(taskId)
            timerPoller.clearState(taskId)
            pollerWake.trySend(Unit)
            return true
        }
        return false
    }

    suspend fun cancelAll() {
        taskDao.cancelAllActive(System.currentTimeMillis())
        pollerWake.trySend(Unit)
    }

    suspend fun deleteTask(taskId: String): Boolean {
        val task = taskDao.getById(taskId) ?: return false
        taskDao.delete(task)
        ciPoller.clearState(taskId)
        timerPoller.clearState(taskId)
        pollerWake.trySend(Unit)
        return true
    }

    suspend fun getTask(taskId: String): TaskEntity? = taskDao.getById(taskId)

    suspend fun getRecentTasks(limit: Int = 20): List<TaskEntity> = taskDao.getRecentTasks(limit)

    private suspend fun findMatchingActiveCIMonitor(
        repo: String,
        branch: String,
        runId: Long,
        workflowName: String,
    ): Pair<TaskEntity, TaskConfig.CIMonitor>? {
        val activeTasks = taskDao.getActiveTasks()
        for (task in activeTasks) {
            if (task.type != TaskType.CI_MONITOR) continue
            try {
                val config = json.decodeFromString(TaskConfig.serializer(), task.config) as? TaskConfig.CIMonitor
                    ?: continue
                val repoMatch = config.repo.equals(repo, ignoreCase = true)
                val branchMatch = config.branch.isBlank() || config.branch.equals(branch, ignoreCase = true)
                val runIdMatch = config.runId == 0L || config.runId == runId
                // [FIX] workflowName 非空时必须匹配：否则监控 "Build APK" 时 "Unit Tests" 先完成，
                // webhook 会错误完成该任务并注入错误 workflow 的结果
                val workflowMatch = config.workflowName.isBlank() ||
                    config.workflowName.equals(workflowName, ignoreCase = true)
                if (repoMatch && branchMatch && runIdMatch && workflowMatch) {
                    return task to config
                }
            } catch (_: Exception) {
                // 解析失败的任务跳过
            }
        }
        return null
    }

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
        fallbackGithubToken: String = "",
    ): Boolean {
        val (matchingTask, matchedConfig) = findMatchingActiveCIMonitor(repo, branch, runId, workflowName)
            ?: return false

        val success = conclusion == "success"

        // [B flaky 自动重试] timed_out 自动 rerun 一次（成功则任务保持活跃，不完成）。
        if (ciPoller.maybeAutoRetryCITask(matchingTask, matchedConfig, conclusion, fallbackGithubToken)) {
            return true
        }

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

        // 获取失败日志（config 无 token 时回退到设置里的全局 token）
        val failedJobs = if (!success) {
            val effectiveToken = matchedConfig?.githubToken?.takeIf { it.isNotBlank() }
                ?: fallbackGithubToken
            try {
                gitHubClient.getFailedJobLogs(repo, runId, effectiveToken)
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

    /**
     * [① CI 启动感知] webhook 收到 workflow_run 的 requested/queued/in_progress 事件时调用。
     * 匹配的活跃任务立即绑定实际 runId（此前 runId=0 只能轮询 not_found 盲等），
     * 状态置 RUNNING，清除 not_found 计数——后续轮询直接查该 run，UI/AI 也能看到 runId。
     * 返回是否找到并更新了任务。
     */
    suspend fun markCIRunningByWebhook(
        repo: String,
        branch: String,
        runId: Long,
        workflowName: String,
    ): Boolean {
        val (task, config) = findMatchingActiveCIMonitor(repo, branch, runId, workflowName)
            ?: return false
        if (runId <= 0) return false

        // 把 runId 写回 config：轮询从 not_found 盲等变成精确查 run
        val newConfig = if (config.runId == 0L) config.copy(runId = runId) else config
        taskDao.update(task.copy(
            status = TaskStatus.RUNNING,
            config = json.encodeToString(TaskConfig.serializer(), newConfig),
            updatedAt = System.currentTimeMillis(),
        ))
        ciPoller.clearState(task.id)
        refreshState()
        return true
    }

    /**
     * [⑦ 全自动监控] webhook 收到新 workflow_run 且无匹配任务时，按白名单自动创建监控。
     * [FIX 重复任务] 同一 run 会连续收到 requested/queued/in_progress 三个事件，
     * 每个事件都会走到这里——必须先四维查重（repo+branch+runId+workflow），
     * 否则同一 run 会创建多个重复监控任务（重复轮询 + 重复完成通知）。
     * 返回是否创建了任务。
     */
    suspend fun autoCreateCIMonitorByWebhook(
        repo: String,
        branch: String,
        runId: Long,
        workflowName: String,
        githubToken: String = "",
        pollIntervalMs: Long = 30_000,
    ): Boolean {
        if (runId <= 0) return false
        // [FIX] 查重：已存在匹配的活跃监控任务（含 runId=0 盲等任务）则跳过创建
        if (findMatchingActiveCIMonitor(repo, branch, runId, workflowName) != null) {
            return false
        }
        val taskId = createCIMonitorTask(
            repo = repo,
            branch = branch,
            runId = runId,
            workflowName = workflowName,
            conversationId = "", // 自动监控无对话关联：完成时只通知
            pollIntervalMs = pollIntervalMs,
            autoAnalyzeOnFailure = true,
            notifyOnSuccess = true,
            githubToken = githubToken,
        )
        Log.i(TAG, "Auto-created CI monitor for $repo@$branch run=$runId (task=$taskId)")
        return true
    }

    /**
     * [④ Rerun CI] 重新触发失败/已完成的 CI run，并把任务重置为新一轮监控。
     * - 需要任务 config 里有 runId（webhook 启动感知或轮询已绑定）
     * - token 需要 actions:write 权限（任务级 token 优先，缺省回退 fallbackToken）
     * - 成功后任务重置为 PENDING + pollCount=0 + 清空结果/错误，poller 立即唤醒
     * 返回成功消息或失败原因。
     */
    suspend fun rerunTask(taskId: String, fallbackToken: String = ""): Result<String> {
        val task = taskDao.getById(taskId)
            ?: return Result.failure(IOException("Task not found: $taskId"))
        if (task.type != TaskType.CI_MONITOR) {
            return Result.failure(IOException("Task $taskId is not a CI monitor"))
        }
        val config = try {
            json.decodeFromString(TaskConfig.serializer(), task.config) as? TaskConfig.CIMonitor
        } catch (e: Exception) {
            return Result.failure(IOException("Invalid CI config", e))
        } ?: return Result.failure(IOException("Invalid CI config"))
        if (config.runId <= 0) {
            return Result.failure(IOException("No run id yet — the CI run has not been observed (webhook/auto-watch will bind it)"))
        }
        val token = config.githubToken.takeIf { it.isNotBlank() } ?: fallbackToken

        val rerunResult = withContext(Dispatchers.IO) {
            gitHubClient.rerunWorkflow(config.repo, config.runId, token)
        }
        rerunResult.onSuccess {
            // 重置任务：新一轮轮询（PENDING → 下一轮立即 poll；run 重置后状态 queued）
            taskDao.update(task.copy(
                status = TaskStatus.PENDING,
                pollCount = 0,
                result = "",
                errorMessage = "",
                updatedAt = System.currentTimeMillis(),
            ))
            ciPoller.clearState(taskId)
            refreshState()
            pollerWake.trySend(Unit)
            Log.i(TAG, "Rerun triggered for task $taskId (${config.repo} run ${config.runId})")
        }
        return rerunResult.map { "Rerun triggered: ${config.repo} run ${config.runId}" }
    }

    /**
     * [③ CI 历史] 查询指定 repo（可选 branch）最近的 CI 完成记录（结论/结论时间）。
     * 用于 AI 判断"该分支最近是否稳定"。
     */
    suspend fun getCIHistory(repo: String, branch: String = "", limit: Int = 20): List<CITaskResult> {
        return taskDao.getFinishedCITasks(limit).mapNotNull { task ->
            runCatching {
                val config = json.decodeFromString(TaskConfig.serializer(), task.config) as? TaskConfig.CIMonitor
                    ?: return@mapNotNull null
                if (!config.repo.equals(repo, ignoreCase = true)) return@mapNotNull null
                if (branch.isNotBlank() && !config.branch.equals(branch, ignoreCase = true)) return@mapNotNull null
                JsonInstant.decodeFromString(CITaskResult.serializer(), task.result)
            }.getOrNull()
        }
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
                            TaskType.CI_MONITOR -> ciPoller.poll(task)
                            TaskType.TIMER -> timerPoller.poll(task)
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

    private suspend fun incrementPollCount(task: TaskEntity) {
        // 条件更新：任务若已被 webhook 完成/取消则不动（防终态被覆盖回 running）
        taskDao.incrementPollCountIfActive(task.id, System.currentTimeMillis())
    }

    private suspend fun completeTask(
        task: TaskEntity,
        success: Boolean,
        resultJson: String = "",
        error: String = "",
        config: TaskConfig.CIMonitor? = null,
        aiAction: Boolean = false,
        steps: List<String> = emptyList(),
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
        ciPoller.clearState(task.id)
        timerPoller.clearState(task.id)
        refreshState()

        // 发出事件（任务级配置优先，缺省时消费端回退到全局设置）
        val event = AppEvent.BackgroundTaskCompleted(
            taskId = task.id,
            taskType = task.type,
            success = success,
            conversationId = task.conversationId,
            resultSummary = buildResultSummary(task, resultJson, error, config),
            autoAnalyze = config?.autoAnalyzeOnFailure,
            notifyOnSuccess = config?.notifyOnSuccess,
            aiAction = aiAction,
            steps = steps,
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
                            // conclusion 可能是 failure/cancelled/timed_out/action_required 等，
                            // 文案显示具体结论而不是笼统的 failed
                            append("❌ CI ").append(result.conclusion.ifBlank { "failed" }).append(": ")
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
