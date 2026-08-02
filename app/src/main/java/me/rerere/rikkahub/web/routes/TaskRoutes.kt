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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.task.BackgroundTaskManager
import me.rerere.rikkahub.data.task.TaskStatus

/**
 * 后台任务 REST API + GitHub Webhook 接收端点。
 *
 * 端点：
 * - GET  /api/tasks              -> 列出最近任务
 * - POST /api/tasks/ci           -> 手动创建 CI 监控
 * - POST /api/tasks/webhook      -> GitHub Actions webhook 接收
 * - POST /api/tasks/{id}/cancel  -> 取消任务
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

        // 创建定时任务
        post("/timer") {
            val request = call.receive<CreateTimerRequest>()
            val taskId = taskManager.createTimerTask(
                delayMs = request.delayMs ?: (request.delaySeconds?.times(1000)) ?: 0,
                message = request.message ?: "Timer",
                conversationId = request.conversationId ?: "",
            )
            call.respond(HttpStatusCode.Created, mapOf("taskId" to taskId))
        }

        // GitHub Actions Webhook 接收
        // 配置：GitHub repo Settings -> Webhooks -> http://<device-ip>:8080/api/tasks/webhook
        // Content type: application/json
        // Events: Actions (workflow_run)
        post("/webhook") {
            val eventType = call.request.headers["X-GitHub-Event"] ?: ""

            when (eventType) {
                "workflow_run" -> {
                    val body = call.receive<JsonObject>()
                    val handled = handleWorkflowRunEvent(taskManager, body)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "received", "handled" to handled))
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

/**
 * 处理 workflow_run webhook 事件。
 *
 * 逻辑：
 * 1. 只处理 action=completed 的事件
 * 2. 查找匹配的活跃 CI 监控任务（按 repo + branch 匹配）
 * 3. 如果找到 → 立即完成该任务（不需要等轮询）
 * 4. 如果没找到 → 忽略（可能是其他工具触发的）
 */
private suspend fun handleWorkflowRunEvent(taskManager: BackgroundTaskManager, body: JsonObject): Boolean {
    val action = body["action"]?.jsonPrimitive?.content ?: return false
    if (action != "completed") return false

    val workflowRun = body["workflow_run"]?.jsonObject ?: return false
    val repo = workflowRun["repository"]?.jsonObject
        ?.get("full_name")?.jsonPrimitive?.content ?: return false
    val branch = workflowRun["head_branch"]?.jsonPrimitive?.content ?: ""
    val conclusion = workflowRun["conclusion"]?.jsonPrimitive?.content ?: ""
    val runId = workflowRun["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
    val runNumber = workflowRun["run_number"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    val name = workflowRun["name"]?.jsonPrimitive?.content ?: ""
    val htmlUrl = workflowRun["html_url"]?.jsonPrimitive?.content ?: ""
    val commitMsg = workflowRun["head_commit"]?.jsonObject
        ?.get("message")?.jsonPrimitive?.content?.lines()?.firstOrNull() ?: ""

    // 通过 taskManager 完成匹配的任务
    return taskManager.completeCIMonitorByWebhook(
        repo = repo,
        branch = branch,
        runId = runId,
        runNumber = runNumber,
        workflowName = name,
        conclusion = conclusion,
        htmlUrl = htmlUrl,
        commitMessage = commitMsg,
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
data class CreateTimerRequest(
    val delayMs: Long? = null,
    val delaySeconds: Long? = null,
    val message: String? = null,
    val conversationId: String? = null,
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
    val pollCount: Int,
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
            pollCount = entity.pollCount,
        )
    }
}
