package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.task.BackgroundTaskManager
import me.rerere.rikkahub.data.task.TaskStatus

/**
 * 后台任务 REST API + GitHub Webhook 接收端点。
 *
 * 端点：
 * - GET  /api/tasks          -> 列出最近任务
 * - POST /api/tasks/ci       -> 手动创建 CI 监控
 * - POST /api/tasks/webhook  -> GitHub Actions webhook 接收
 * - POST /api/tasks/{id}/cancel -> 取消任务
 */
fun Route.taskRoutes(taskManager: BackgroundTaskManager) {
    route("/tasks") {
        // 列出最近任务
        get {
            val tasks = taskManager.getRecentTasks(20)
            call.respond(tasks.map { TaskDto.from(it) })
        }

        // 手动创建 CI 监控
        post("/ci") {
            val request = call.receive<CreateCIMonitorRequest>()
            val taskId = taskManager.createCIMonitorTask(
                repo = request.repo,
                branch = request.branch ?: "",
                runId = request.runId ?: 0,
                workflowName = request.workflow ?: "",
                conversationId = request.conversationId ?: "",
                autoAnalyzeOnFailure = request.autoAnalyze ?: true,
                notifyOnSuccess = request.notifyOnSuccess ?: true,
                githubToken = request.githubToken ?: "",
            )
            call.respond(HttpStatusCode.Created, mapOf("taskId" to taskId))
        }

        // GitHub Actions Webhook 接收
        // 配置：在 GitHub repo Settings -> Webhooks 添加
        // URL: http://<device-ip>:8080/api/tasks/webhook
        // Content type: application/json
        // Events: Workflow jobs (or Actions)
        post("/webhook") {
            val eventType = call.request.headers["X-GitHub-Event"] ?: ""
            val body = call.receive<JsonObject>()

            when (eventType) {
                "workflow_job", "workflow_run" -> {
                    handleWorkflowEvent(taskManager, body)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "received"))
                }
                "ping" -> {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "pong"))
                }
                else -> {
                    call.respond(HttpStatusCode.OK, mapOf("status" to "ignored", "event" to eventType))
                }
            }
        }

        // 取消任务
        post("/{id}/cancel") {
            val id = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
                return@post
            }
            taskManager.cancelTask(id)
            call.respond(mapOf("status" to "cancelled"))
        }
    }
}

private suspend fun handleWorkflowEvent(taskManager: BackgroundTaskManager, body: JsonObject) {
    // workflow_run 事件包含完整信息
    val action = body["action"]?.jsonPrimitive?.content ?: ""
    val workflowRun = body["workflow_run"] as? JsonObject ?: return

    val conclusion = workflowRun["conclusion"]?.jsonPrimitive?.content ?: ""
    val status = workflowRun["status"]?.jsonPrimitive?.content ?: ""

    // 只处理完成事件
    if (status != "completed") return

    // Webhook 收到完成事件后，可以立即通知（不需要轮询）
    // 这里通过 eventBus 发出事件，让 TaskNotificationManager 处理
    val repo = (workflowRun["repository"] as? JsonObject)
        ?.let { it["full_name"]?.jsonPrimitive?.content } ?: ""
    val runNumber = workflowRun["run_number"]?.jsonPrimitive?.content ?: ""
    val branch = workflowRun["head_branch"]?.jsonPrimitive?.content ?: ""
    val htmlUrl = workflowRun["html_url"]?.jsonPrimitive?.content ?: ""
    val name = workflowRun["name"]?.jsonPrimitive?.content ?: ""

    // 创建一个已完成的任务记录（用于历史查看）
    val success = conclusion == "success"
    val summary = buildString {
        if (success) append("✅") else append("❌")
        append(" CI $conclusion: $name #$runNumber ($branch)")
        if (htmlUrl.isNotBlank()) append("\n$htmlUrl")
    }

    // 直接通过 taskManager 的 eventBus 发事件
    // 这里简化处理：创建一个即时完成的任务
    taskManager.createCIMonitorTask(
        repo = repo,
        branch = branch,
        workflowName = name,
        notifyOnSuccess = true,
    )
}

@Serializable
data class CreateCIMonitorRequest(
    val repo: String,
    val branch: String? = null,
    val runId: Long? = null,
    val workflow: String? = null,
    val conversationId: String? = null,
    val autoAnalyze: Boolean? = null,
    val notifyOnSuccess: Boolean? = null,
    val githubToken: String? = null,
)

@Serializable
data class TaskDto(
    val id: String,
    val type: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long,
    val errorMessage: String,
) {
    companion object {
        fun from(entity: me.rerere.rikkahub.data.task.TaskEntity) = TaskDto(
            id = entity.id,
            type = entity.type,
            status = entity.status,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            completedAt = entity.completedAt,
            errorMessage = entity.errorMessage,
        )
    }
}
