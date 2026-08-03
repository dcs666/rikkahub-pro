package me.rerere.rikkahub.data.task

/**
 * 任务相关的共享校验/常量。
 * REPO_PATTERN 同时被 AI 工具（BackgroundTaskTool）与 REST 路由（TaskRoutes）使用，
 * 提取到 data/task 便于复用与单元测试。
 */

/** GitHub repo 全名格式（owner/name，各 1-100 个 [A-Za-z0-9_.-]）。 */
internal val REPO_PATTERN = Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")
