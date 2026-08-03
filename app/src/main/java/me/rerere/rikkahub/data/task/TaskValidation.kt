package me.rerere.rikkahub.data.task

/**
 * 任务相关的共享校验/常量。
 * REPO_PATTERN 同时被 AI 工具（BackgroundTaskTool）与 REST 路由（TaskRoutes）使用，
 * 提取到 data/task 便于复用与单元测试。
 */

/** GitHub repo 全名格式（owner/name，各 1-100 个 [A-Za-z0-9_.-]）。 */
internal val REPO_PATTERN = Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")

/**
 * [B flaky 自动重试] 是否自动 rerun 一次。
 * 仅 GitHub conclusion=timed_out（网络超时类失败，非真实代码错误）且未重试过时触发；
 * failure/cancelled/skipped 等不自动重试（真实失败交给 AI 分析，cancelled 是主动操作）。
 */
internal fun shouldAutoRetryCI(conclusion: String, alreadyRetried: Boolean): Boolean =
    conclusion == "timed_out" && !alreadyRetried

/**
 * [FIX] runId=0（监控 latest）时判断最新 run 是否为"过期 run"（不应采信）：
 * 1) auto-rerun 后的旧 run（skipRunId 精确匹配：新 run 尚未注册的窗口期内，
 *    latest 仍可能是旧 run，直接采信会误报 timed_out 失败）
 * 2) 已完成但创建于任务之前（超出容差）的 run —— 推送后 GitHub 尚未注册新 run 时，
 *    latest 可能是上一次 push 的残留，直接采信会误报成功/失败
 * 返回 true 时调用方应按 not_found 继续等待。
 */
internal fun isStaleRun(
    config: TaskConfig.CIMonitor,
    taskCreatedAt: Long,
    ciResult: CITaskResult,
): Boolean {
    if (config.runId != 0L) return false // 指定 runId 的任务不受影响
    if (config.skipRunId != 0L && ciResult.runId == config.skipRunId) return true
    if (ciResult.status != "completed") return false // in_progress/queued 无法判断新旧，照常等待
    val createdMs = runCatching {
        java.time.Instant.parse(ciResult.startedAt).toEpochMilli()
    }.getOrNull() ?: return false // 时间解析失败时保守放行，不阻塞原有逻辑
    return createdMs + STALE_RUN_TOLERANCE_MS < taskCreatedAt
}

/**
 * [FIX] runId=0（监控 latest）时"run 先于任务创建"的容差：
 * push 触发 GitHub 注册 run 与 AI 创建监控任务之间存在几秒~几十秒的正常间隔，
 * 因此目标 run 的 created_at 通常略早于 task.createdAt。
 * 超过该容差仍 completed 的 run 视为上一次 push 的残留，不能采信。
 */
internal const val STALE_RUN_TOLERANCE_MS = 30_000L
