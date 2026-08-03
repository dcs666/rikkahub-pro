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
