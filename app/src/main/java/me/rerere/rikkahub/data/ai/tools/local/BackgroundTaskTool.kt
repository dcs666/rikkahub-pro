package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.task.BackgroundTaskManager

/**
 * AI 工具：创建后台任务（CI 监控、定时器等）。
 *
 * 使用场景：
 * - 用户 push 代码后，AI 自动创建 CI 监控任务
 * - 用户说"CI 跑完了告诉我"，AI 创建监控
 * - 用户说"5分钟后提醒我"，AI 创建定时器
 */

// owner/name 格式（GitHub 用户名/组织名 + 仓库名，各 1-100 个 [A-Za-z0-9_.-]）
private val REPO_PATTERN = Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")

/** 任务描述（与 REST TaskDto 一致的轻量解析，解析失败返回 null）。 */
private fun taskDescription(task: me.rerere.rikkahub.data.task.TaskEntity): String? {
    return runCatching {
        val json = me.rerere.rikkahub.utils.JsonInstant
        when (task.type) {
            "ci_monitor" -> {
                val config = json.decodeFromString(
                    me.rerere.rikkahub.data.task.TaskConfig.serializer(), task.config
                ) as? me.rerere.rikkahub.data.task.TaskConfig.CIMonitor ?: return null
                buildString {
                    append(config.repo)
                    if (config.branch.isNotBlank()) append(" @ ").append(config.branch)
                    if (config.workflowName.isNotBlank()) append(" (").append(config.workflowName).append(")")
                }
            }
            "timer" -> {
                val config = json.decodeFromString(
                    me.rerere.rikkahub.data.task.TaskConfig.serializer(), task.config
                ) as? me.rerere.rikkahub.data.task.TaskConfig.Timer ?: return null
                config.message.ifBlank { "Timer (${config.delayMs / 1000}s)" }
            }
            else -> null
        }
    }.getOrNull()
}
internal fun buildBackgroundTaskTool(
    taskManager: BackgroundTaskManager,
    settingsStore: SettingsStore,
    defaultConversationId: String = "",
): Tool = Tool(
    name = "background_task",
    description = """
        Create and manage background tasks that run asynchronously.
        Use this when the user wants to be notified about events that happen in the future,
        such as CI/CD pipeline results, timers, or other async operations.

        Supported actions:
        - "create_ci_monitor": Monitor a GitHub Actions workflow run. You'll be notified when it completes.
        - "create_timer": Set a timer that fires after a delay.
        - "list_tasks": List active and recent background tasks.
        - "get_task": Get the status and result of a specific task by ID.
        - "cancel_task": Cancel an active task by ID.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "The action to perform: create_ci_monitor, create_timer, list_tasks, get_task, or cancel_task")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("create_ci_monitor"))
                        add(JsonPrimitive("create_timer"))
                        add(JsonPrimitive("list_tasks"))
                        add(JsonPrimitive("get_task"))
                        add(JsonPrimitive("cancel_task"))
                    })
                })
                put("repo", buildJsonObject {
                    put("type", "string")
                    put("description", "GitHub repo full name, e.g. 'owner/repo'. Required for create_ci_monitor.")
                })
                put("branch", buildJsonObject {
                    put("type", "string")
                    put("description", "Git branch to monitor. Optional, defaults to all branches.")
                })
                put("workflow", buildJsonObject {
                    put("type", "string")
                    put("description", "Workflow name filter. Optional.")
                })
                put("conversation_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Conversation UUID to inject results into. Optional.")
                })
                put("delay_seconds", buildJsonObject {
                    put("type", "number")
                    put("description", "Delay in seconds for create_timer.")
                })
                put("delay_minutes", buildJsonObject {
                    put("type", "number")
                    put("description", "Delay in minutes for create_timer.")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Message for the timer.")
                })
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task ID for get_task or cancel_task.")
                })
                put("poll_interval_sec", buildJsonObject {
                    put("type", "number")
                    put("description", "Polling interval in seconds for CI monitor. Optional, uses app setting default.")
                })
                put("run_id", buildJsonObject {
                    put("type", "number")
                    put("description", "Specific GitHub Actions run ID to monitor. Optional, defaults to latest run.")
                })
            },
            required = listOf("action"),
        )
    },
    systemPrompt = { _, _ ->
        """
        You have access to a background_task tool that can monitor CI/CD pipelines and set timers.
        When the user pushes code or mentions waiting for CI, proactively offer to monitor it.
        When a CI task completes, you will receive the result as a new message in the conversation.
        If CI fails, analyze the error and suggest fixes.
        """.trimIndent()
    },
    execute = { input ->
        val obj = input.jsonObject
        val action = obj["action"]?.jsonPrimitive?.content ?: "list_tasks"
        val settings = settingsStore.settingsFlow.value

        val result = when (action) {
            "create_ci_monitor" -> {
                val repo = obj["repo"]?.jsonPrimitive?.content ?: ""
                val branch = obj["branch"]?.jsonPrimitive?.content ?: ""
                val runId = obj["run_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                val workflow = obj["workflow"]?.jsonPrimitive?.content ?: ""
                val conversationId = obj["conversation_id"]?.jsonPrimitive?.content ?: defaultConversationId
                val autoAnalyze = obj["auto_analyze"]?.jsonPrimitive?.content?.toBoolean()
                    ?: settings.taskAutoAnalyze
                val token = obj["github_token"]?.jsonPrimitive?.content
                    ?: settings.taskGithubToken
                // [FIX] clamp 最小轮询间隔 10s：AI 若传 1s，未认证 GitHub 60 req/hour 会被秒耗 rate limit
                val pollInterval = ((obj["poll_interval_sec"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: settings.taskPollIntervalSec.toLong()).coerceAtLeast(10L)) * 1000

                if (repo.isBlank()) {
                    """{"error": "repo is required, e.g. 'dcs666/rikkahub-turbo'"}"""
                } else if (!REPO_PATTERN.matches(repo)) {
                    """{"error": "repo must be in 'owner/name' format, e.g. 'dcs666/rikkahub-turbo' (got: $repo)"}"""
                } else {
                    val taskId = taskManager.createCIMonitorTask(
                        repo = repo,
                        branch = branch,
                        runId = runId,
                        workflowName = workflow,
                        conversationId = conversationId,
                        pollIntervalMs = pollInterval,
                        autoAnalyzeOnFailure = autoAnalyze,
                        notifyOnSuccess = settings.taskNotifyOnSuccess,
                        githubToken = token,
                    )
                    buildJsonObject {
                        put("status", "created")
                        put("task_id", taskId)
                        put("message", "CI monitor active for $repo. I'll notify you when it completes.")
                    }.toString()
                }
            }

            "create_timer" -> {
                val delayMs = obj["delay_ms"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: obj["delay_seconds"]?.jsonPrimitive?.content?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                    ?: obj["delay_minutes"]?.jsonPrimitive?.content?.toDoubleOrNull()?.let { (it * 60_000).toLong() }
                    ?: 0L
                val message = obj["message"]?.jsonPrimitive?.content ?: "Timer"
                val conversationId = obj["conversation_id"]?.jsonPrimitive?.content ?: defaultConversationId

                if (delayMs <= 0) {
                    """{"error": "Specify delay_ms, delay_seconds, or delay_minutes"}"""
                } else {
                    val taskId = taskManager.createTimerTask(
                        delayMs = delayMs,
                        message = message,
                        conversationId = conversationId,
                    )
                    buildJsonObject {
                        put("status", "created")
                        put("task_id", taskId)
                        put("message", "Timer set for ${delayMs / 1000}s: $message")
                    }.toString()
                }
            }

            "list_tasks" -> {
                val tasks = taskManager.getRecentTasks(10)
                buildJsonObject {
                    putJsonArray("tasks") {
                        tasks.forEach { task ->
                            add(buildJsonObject {
                                put("id", task.id)
                                put("type", task.type)
                                put("status", task.status)
                                put("created_at", task.createdAt)
                                put("updated_at", task.updatedAt)
                                put("poll_count", task.pollCount)
                                // 与 REST TaskDto.description 一致：CI 任务给 repo@branch(+workflow)，
                                // 定时器给消息文本，让 AI 能区分任务
                                taskDescription(task)?.let { put("description", it) }
                                if (task.errorMessage.isNotBlank()) put("error", task.errorMessage)
                            })
                        }
                    }
                    if (tasks.isEmpty()) put("message", "No tasks found")
                }.toString()
            }

            "get_task" -> {
                val taskId = obj["task_id"]?.jsonPrimitive?.content ?: ""
                if (taskId.isBlank()) {
                    """{"error": "task_id is required"}"""
                } else {
                    val task = taskManager.getTask(taskId)
                    if (task == null) {
                        """{"error": "Task not found: $taskId"}"""
                    } else {
                        buildJsonObject {
                            put("id", task.id)
                            put("type", task.type)
                            put("status", task.status)
                            put("created_at", task.createdAt)
                            put("updated_at", task.updatedAt)
                            put("completed_at", task.completedAt)
                            put("poll_count", task.pollCount)
                            put("conversation_id", task.conversationId)
                            if (task.errorMessage.isNotBlank()) put("error_message", task.errorMessage)
                            if (task.result.isNotBlank()) {
                                // 结果 JSON 原文透传（CITaskResult 等），AI 可直接读取结论与失败日志摘要
                                put("result", task.result)
                            }
                        }.toString()
                    }
                }
            }

            "cancel_task" -> {
                val taskId = obj["task_id"]?.jsonPrimitive?.content ?: ""
                if (taskId.isBlank()) {
                    """{"error": "task_id is required"}"""
                } else {
                    taskManager.cancelTask(taskId)
                    """{"status": "cancelled", "task_id": "$taskId"}"""
                }
            }

            else -> """{"error": "Unknown action: $action. Use create_ci_monitor, create_timer, list_tasks, get_task, or cancel_task"}"""
        }

        listOf(UIMessagePart.Text(result))
    }
)
