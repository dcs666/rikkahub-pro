package me.rerere.rikkahub.data.task

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "CiTaskPoller"

// [拆分] CI 轮询域：pollCITask 的软状态与执行逻辑（拆自 BackgroundTaskManager）。
// 软状态（进程内存，重启丢失可接受）不再与 Timer 域混放，各域自持。

class CiTaskPoller(
    private val json: Json,
    private val taskDao: TaskDao,
    private val gitHubClient: GitHubActionsClient,
    private val onCompleteTask: suspend (
        task: TaskEntity,
        success: Boolean,
        resultJson: String,
        error: String,
        config: TaskConfig.CIMonitor?,
        aiAction: Boolean,
        steps: List<String>,
    ) -> Unit,
    private val onIncrementPollCount: suspend (TaskEntity) -> Unit,
    private val onWakePoller: () -> Unit,
) {
    // 软状态：连续失败 / 连续 not_found / rate limit 退避截止
    private val consecutiveFailures = ConcurrentHashMap<String, Int>()
    private val consecutiveNotFound = ConcurrentHashMap<String, Int>()
    private val rateLimitedUntil = ConcurrentHashMap<String, Long>()

    /** 任务被完成/取消/重置时清理软状态（防 Map 泄漏）。 */
    fun clearState(taskId: String) {
        consecutiveFailures.remove(taskId)
        consecutiveNotFound.remove(taskId)
        rateLimitedUntil.remove(taskId)
    }

    /** 下一次该任务可轮询的绝对时间（主类唤醒调度用）。 */
    fun dueAt(task: TaskEntity): Long {
        // rate limit 退避窗口内：睡到窗口结束再 poll，避免每 2s 空转唤醒
        rateLimitedUntil[task.id]?.let { return it }
        if (task.status == TaskStatus.PENDING) return 0L // 立即 poll
        val pollIntervalMs = runCatching {
            (json.decodeFromString(TaskConfig.serializer(), task.config) as? TaskConfig.CIMonitor)
                ?.pollIntervalMs ?: DEFAULT_POLL_INTERVAL_MS
        }.getOrDefault(DEFAULT_POLL_INTERVAL_MS)
        return task.updatedAt + nextPollDelay(task.pollCount, pollIntervalMs)
    }

    suspend fun poll(task: TaskEntity) {
        // [OPT] rate limit 退避窗口内直接跳过（不递增 pollCount，不计失败）
        val backoffUntil = rateLimitedUntil[task.id]
        if (backoffUntil != null) {
            if (System.currentTimeMillis() < backoffUntil) return
            rateLimitedUntil.remove(task.id)
        }

        val config = try {
            (json.decodeFromString(TaskConfig.serializer(), task.config) as? TaskConfig.CIMonitor)
                ?: run {
                    onCompleteTask(task, success = false, error = "Invalid config type", resultJson = "", config = null, aiAction = false, steps = emptyList())
                    return
                }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid CI config for task ${task.id}", e)
            onCompleteTask(task, success = false, error = "Invalid config", resultJson = "", config = null, aiAction = false, steps = emptyList())
            return
        }

        // 检查是否超过最大轮询次数
        if (task.pollCount >= config.maxPollCount) {
            onCompleteTask(task, success = false, error = "Max poll count reached (${config.maxPollCount})", resultJson = "", config = config, aiAction = false, steps = emptyList())
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
                // [FIX] runId=0（监控 latest）时防绑定过期 run：auto-rerun 后的旧 run、
                // 以及任务创建前就已完成的上一次 push 残留。过期 run 不能采信，
                // 按 not_found 继续等待真正的目标 run。
                if (isStaleRun(config, task.createdAt, ciResult)) {
                    val streak = (consecutiveNotFound.merge(task.id, 1, Int::plus) ?: 1)
                    if (streak >= CONSECUTIVE_NOT_FOUND_LIMIT) {
                        consecutiveNotFound.remove(task.id)
                        onCompleteTask(
                            task,
                            success = false,
                            resultJson = "",
                            error = "No new workflow run found for ${config.repo}@" +
                                "${config.branch.ifBlank { "any" }}" +
                                " (workflow: ${config.workflowName.ifBlank { "any" }}) after $streak checks",
                            config = config,
                            aiAction = false,
                            steps = emptyList(),
                        )
                    } else {
                        onIncrementPollCount(task)
                    }
                    return@fold
                }
                when (ciResult.status) {
                    "completed" -> {
                        consecutiveFailures.remove(task.id)
                        consecutiveNotFound.remove(task.id)
                        // [B flaky 自动重试] timed_out 自动 rerun 一次，
                        // 成功则跳过 completeTask（任务保持活跃继续监控）
                        val autoRetried = maybeAutoRetryCITask(task, config, ciResult.conclusion)
                        if (!autoRetried) {
                            // 获取失败日志
                            // [FIX] 抓日志失败（token 失效/网络）不能阻止任务完成：
                            // 异常冒泡会让 completeTask 永远不执行，任务死循环到 maxPollCount。
                            // 失败时降级为无日志，任务照常完成（result 里 failedJobs 为空）。
                            val failedJobs = if (ciResult.conclusion == "failure") {
                                try {
                                    withContext(Dispatchers.IO) {
                                        gitHubClient.getFailedJobLogs(config.repo, ciResult.runId, config.githubToken)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to fetch job logs for task ${task.id}: ${e.message}")
                                    emptyList()
                                }
                            } else emptyList()

                            val finalResult = ciResult.copy(failedJobs = failedJobs)
                            val success = ciResult.conclusion == "success"
                            onCompleteTask(
                                task,
                                success = success,
                                resultJson = json.encodeToString(CITaskResult.serializer(), finalResult),
                                error = "",
                                config = config,
                                aiAction = false,
                                steps = emptyList(),
                            )
                        }
                    }
                    "not_found" -> {
                        // 还没找到 run，继续等；但连续找不到说明 repo/branch/workflow 可能写错，
                        // 达到阈值时给出明确错误而不是干等到 maxPollCount
                        val streak = (consecutiveNotFound.merge(task.id, 1, Int::plus) ?: 1)
                        if (streak >= CONSECUTIVE_NOT_FOUND_LIMIT) {
                            consecutiveNotFound.remove(task.id)
                            onCompleteTask(
                                task,
                                success = false,
                                resultJson = "",
                                error = "No workflow run found for ${config.repo}@${config.branch.ifBlank { "any" }}" +
                                    " (workflow: ${config.workflowName.ifBlank { "any" }}) after $streak checks",
                                config = config,
                                aiAction = false,
                                steps = emptyList(),
                            )
                        } else {
                            onIncrementPollCount(task)
                        }
                    }
                    else -> {
                        // queued / in_progress，继续等（正常状态，清除错误计数）
                        consecutiveFailures.remove(task.id)
                        consecutiveNotFound.remove(task.id)
                        onIncrementPollCount(task)
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
                    onCompleteTask(task, success = false, resultJson = "", error = "Repeated poll errors ($streak consecutive): ${e.message}", config = config, aiAction = false, steps = emptyList())
                } else {
                    onIncrementPollCount(task)
                }
            }
        )
    }

    /**
     * [B flaky 自动重试] timed_out 时自动 rerun 一次（仅当未重试过）。
     * 成功后任务重置为 PENDING + runId=0 + skipRunId=旧run，继续监控新 run。
     * 返回 true 表示已自动重试（调用方跳过完成流程）。
     */
    suspend fun maybeAutoRetryCITask(
        task: TaskEntity,
        config: TaskConfig.CIMonitor,
        conclusion: String,
        fallbackToken: String = "",
    ): Boolean {
        if (!shouldAutoRetryCI(conclusion, config.autoRetried)) return false
        // 任务级 token 优先，webhook 路径可回退到全局 token；都没有则无法 rerun
        val token = config.githubToken.takeIf { it.isNotBlank() }
            ?: fallbackToken.takeIf { it.isNotBlank() }
            ?: return false

        val result = withContext(Dispatchers.IO) {
            gitHubClient.rerunWorkflow(config.repo, config.runId, token)
        }
        return result.fold(
            onSuccess = {
                // [FIX] GitHub rerun 会生成新 run（新 run_id，run_number 不变），旧 run 保持原
                // timed_out 结论不变。若继续监控旧 runId，下一轮会读到过期结论、误报失败。
                // 重置 runId=0 + status=PENDING 让下一轮按分支重新解析最新 run（rerun 的新 run），
                // 并把旧 runId 记入 skipRunId：新 run 注册前的窗口期内 latest 仍是旧 run，
                // isStaleRun 会精确跳过它而不是错误完成。
                taskDao.update(task.copy(
                    status = TaskStatus.PENDING,
                    config = json.encodeToString(TaskConfig.serializer(), config.copy(
                        autoRetried = true,
                        runId = 0L,
                        skipRunId = config.runId,
                    )),
                    pollCount = 0,
                    updatedAt = System.currentTimeMillis(),
                ))
                consecutiveFailures.remove(task.id)
                consecutiveNotFound.remove(task.id)
                onWakePoller()
                Log.i(TAG, "CI timed out — auto-rerun #${config.runId} for task ${task.id}")
                true
            },
            onFailure = { e ->
                Log.w(TAG, "Auto-rerun failed for task ${task.id}: ${e.message}")
                false
            },
        )
    }

    /**
     * 计算带指数退避的下次轮询间隔。
     * 前 5 次用配置的 pollIntervalMs，之后逐步增加（最大 5 分钟）。
     */
    private fun nextPollDelay(pollCount: Int, baseIntervalMs: Long): Long =
        computeNextPollDelay(pollCount, baseIntervalMs)

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 30_000L
        const val RATE_LIMIT_BACKOFF_MS = 5 * 60_000L
        const val CONSECUTIVE_FAILURE_LIMIT = 5
        const val CONSECUTIVE_NOT_FOUND_LIMIT = 10
    }
}
