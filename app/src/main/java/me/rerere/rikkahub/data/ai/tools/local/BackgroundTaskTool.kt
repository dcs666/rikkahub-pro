package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.task.BackgroundTaskManager

/**
 * AI 工具：创建后台任务（CI 监控、定时器等）。
 *
 * 使用场景：
 * - 用户 push 代码后，AI 自动创建 CI 监控任务
 * - 用户说"CI 跑完了告诉我"，AI 创建监控
 * - 用户说"5分钟后提醒我"，AI 创建定时器
 */
internal fun buildBackgroundTaskTool(taskManager: BackgroundTaskManager): Tool = Tool(
    name = "background_task",
    description = """
        Create and manage background tasks that run asynchronously.
        Use this when the user wants to be notified about events that happen in the future,
        such as CI/CD pipeline results, timers, or other async operations.

        Supported actions:
        - "create_ci_monitor": Monitor a GitHub Actions workflow run. You'll be notified when it completes.
        - "create_timer": Set a timer that fires after a delay.
        - "list_tasks": List active and recent background tasks.
        - "cancel_task": Cancel an active task by ID.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("type", "object")
                // Using a flat schema for simplicity
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

        val result = when (action) {
            "create_ci_monitor" -> {
                val repo = obj["repo"]?.jsonPrimitive?.content ?: ""
                val branch = obj["branch"]?.jsonPrimitive?.content ?: ""
                val runId = obj["run_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                val workflow = obj["workflow"]?.jsonPrimitive?.content ?: ""
                val conversationId = obj["conversation_id"]?.jsonPrimitive?.content ?: ""
                val autoAnalyze = obj["auto_analyze"]?.jsonPrimitive?.content?.toBoolean() ?: true
                val token = obj["github_token"]?.jsonPrimitive?.content ?: ""

                if (repo.isBlank()) {
                    """{"error": "repo is required, e.g. 'dcs666/rikkahub-turbo'"}"""
                } else {
                    val taskId = taskManager.createCIMonitorTask(
                        repo = repo,
                        branch = branch,
                        runId = runId,
                        workflowName = workflow,
                        conversationId = conversationId,
                        autoAnalyzeOnFailure = autoAnalyze,
                        githubToken = token,
                    )
                    buildJsonObject {
                        put("status", "created")
                        put("task_id", taskId)
                        put("message", "CI monitor created for $repo. I'll notify you when it completes.")
                    }.toString()
                }
            }

            "create_timer" -> {
                val delayMs = obj["delay_ms"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: obj["delay_seconds"]?.jsonPrimitive?.content?.toLongOrNull()?.let { it * 1000 }
                    ?: obj["delay_minutes"]?.jsonPrimitive?.content?.toLongOrNull()?.let { it * 60_000 }
                    ?: 0L
                val message = obj["message"]?.jsonPrimitive?.content ?: "Timer"
                val conversationId = obj["conversation_id"]?.jsonPrimitive?.content ?: ""

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
                if (tasks.isEmpty()) {
                    """{"tasks": [], "message": "No tasks found"}"""
                } else {
                    buildString {
                        append("""{"tasks": [""")
                        tasks.forEachIndexed { index, task ->
                            if (index > 0) append(",")
                            append("""{"id":"${task.id}","type":"${task.type}","status":"${task.status}","created_at":${task.createdAt}}""")
                        }
                        append("]}")
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

            else -> """{"error": "Unknown action: $action. Use create_ci_monitor, create_timer, list_tasks, or cancel_task"}"""
        }

        listOf(UIMessagePart.Text(result))
    }
)
