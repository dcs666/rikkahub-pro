package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.task.BackgroundTaskManager
import me.rerere.rikkahub.data.task.TaskStatus
import me.rerere.rikkahub.utils.JsonInstant
import java.security.MessageDigest

/** GitHub repo 全名格式（owner/name）。 */
private val REPO_PATTERN = me.rerere.rikkahub.data.task.REPO_PATTERN

/**
 * 后台任务 REST API + GitHub Webhook 接收端点。
 *
 * 端点：
 * - GET    /api/tasks              -> 列出最近任务（?limit=1..50，默认 20）
 * - POST   /api/tasks/ci           -> 手动创建 CI 监控
 * - POST   /api/tasks/timer        -> 创建定时任务
 * - POST   /api/tasks/webhook      -> GitHub Actions webhook 接收（配置了 GitHub Token 时校验 HMAC 签名）
 * - POST   /api/tasks/{id}/cancel  -> 取消任务
 * - DELETE /api/tasks/{id}         -> 删除任务记录（历史清理）
 */
fun Route.taskRoutes(
    taskManager: BackgroundTaskManager,
    settingsStore: SettingsStore,
) {
    route("/tasks") {
        // 列出最近任务
        get {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
            val tasks = taskManager.getRecentTasks(limit)
            call.respond(tasks.map { TaskDto.from(it) })
        }

        // 手动创建 CI 监控
        post("/ci") {
            val request = call.receive<CreateCIMonitorRequest>()
            if (request.repo.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "repo is required"))
                return@post
            }
            if (!REPO_PATTERN.matches(request.repo)) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "repo must be in 'owner/name' format, e.g. 'dcs666/rikkahub-turbo'")
                )
                return@post
            }
            val taskId = taskManager.createCIMonitorTask(
                repo = request.repo,
                branch = request.branch ?: "",
                runId = request.runId ?: 0,
                workflowName = request.workflow ?: "",
                conversationId = request.conversationId ?: "",
                autoAnalyzeOnFailure = request.autoAnalyze ?: true,
                notifyOnSuccess = request.notifyOnSuccess ?: true,
                // [FIX] 未显式传 token 时回退到设置里的全局 token（与 AI 工具一致），
                // 否则私有仓库监控会在 GitHub API 层 404
                githubToken = request.githubToken?.takeIf { it.isNotBlank() }
                    ?: settingsStore.settingsFlow.value.taskGithubToken,
                // [FIX] 与 AI 工具一致：最小轮询间隔钳制 10s，防未认证配额被秒耗
                pollIntervalMs = (request.pollIntervalSec?.coerceAtLeast(10L) ?: 30L) * 1000,
            )
            call.respond(HttpStatusCode.Created, mapOf("taskId" to taskId))
        }

        // 创建定时任务
        post("/timer") {
            val request = call.receive<CreateTimerRequest>()
            val delayMs = request.delayMs ?: (request.delaySeconds?.times(1000)) ?: 0
            if (delayMs <= 0) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "delayMs or delaySeconds must be positive"))
                return@post
            }
            val taskId = taskManager.createTimerTask(
                delayMs = delayMs,
                message = request.message ?: "Timer",
                conversationId = request.conversationId ?: "",
            )
            call.respond(HttpStatusCode.Created, mapOf("taskId" to taskId))
        }

        // GitHub Actions Webhook 接收
        // 配置：GitHub repo Settings -> Webhooks -> http://<device-ip>:8080/api/tasks/webhook
        // Content type: application/json
        // Events: Actions (workflow_run)
        // Secret（可选）：设置页配置的 GitHub Token；配置后所有请求必须带合法的
        // X-Hub-Signature-256 签名，防止伪造 webhook 事件。
        post("/webhook") {
            val eventType = call.request.headers["X-GitHub-Event"] ?: ""
            val secret = settingsStore.settingsFlow.value.taskGithubToken

            // 读原始 body 一次：需要 HMAC 校验时用文本，否则直接 JSON 解析
            val bodyText = call.receiveText()
            val signature = call.request.headers["X-Hub-Signature-256"]

            if (secret.isNotBlank()) {
                val expected = "sha256=" + hmacSha256Hex(secret, bodyText)
                val provided = signature?.lowercase()?.removePrefix("sha256=")
                val valid = provided != null &&
                    MessageDigest.isEqual(
                        expected.toByteArray(Charsets.UTF_8),
                        provided.toByteArray(Charsets.UTF_8)
                    )
                if (!valid) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid webhook signature"))
                    return@post
                }
            }

            val body = runCatching {
                JsonInstant.parseToJsonElement(bodyText).jsonObject
            }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON body"))
                return@post
            }

            when (eventType) {
                "workflow_run" -> {
                    val handled = handleWorkflowRunEvent(
                        taskManager = taskManager,
                        body = body,
                        fallbackGithubToken = settingsStore.settingsFlow.value.taskGithubToken,
                    )
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

        // 单任务详情（供外部客户端查询结果）
        get("/{id}") {
            val id = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
                return@get
            }
            val task = taskManager.getTask(id)
            if (task == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
            } else {
                call.respond(TaskDto.from(task))
            }
        }

        // 取消任务
        post("/{id}/cancel") {
            val id = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
                return@post
            }
            val cancelled = taskManager.cancelTask(id)
            if (cancelled) {
                call.respond(mapOf("status" to "cancelled"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found or already finished"))
            }
        }
        // 删除任务记录（历史清理）
        delete("/{id}") {
            val id = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
                return@delete
            }
            val deleted = taskManager.deleteTask(id)
            if (deleted) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
            }
        }
    }
}

/** GitHub webhook HMAC-SHA256 签名（X-Hub-Signature-256 的 sha256= 部分）。 */
private fun hmacSha256Hex(secret: String, body: String): String {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    // 注意：不能用 "%02x".format(byte)——负数 Byte 会符号扩展输出 8 位（ffffffff），
    // 必须 and 0xFF 后按无符号字节格式化
    return mac.doFinal(body.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * 处理 workflow_run webhook 事件。
 *
 * 逻辑：
 * 1. 只处理 action=completed 的事件
 * 2. 查找匹配的活跃 CI 监控任务（按 repo + branch 匹配）
 * 3. 如果找到 → 立即完成该任务（不需要等轮询）
 * 4. 如果没找到 → 忽略（可能是其他工具触发的）
 *
 * @param fallbackGithubToken 设置里的全局 GitHub token；任务的 config 未存 token 时用于抓失败日志
 */
private suspend fun handleWorkflowRunEvent(
    taskManager: BackgroundTaskManager,
    body: JsonObject,
    fallbackGithubToken: String = "",
): Boolean {
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

    // 通过 taskManager 完成匹配的任务（失败日志抓取时用设置里的全局 token 兜底）
    return taskManager.completeCIMonitorByWebhook(
        repo = repo,
        branch = branch,
        runId = runId,
        runNumber = runNumber,
        workflowName = name,
        conclusion = conclusion,
        htmlUrl = htmlUrl,
        commitMessage = commitMsg,
        fallbackGithubToken = fallbackGithubToken,
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
    val pollIntervalSec: Long? = null,
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
    val description: String,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long,
    val errorMessage: String,
    val pollCount: Int,
    val result: String = "",
) {
    companion object {
        fun from(entity: me.rerere.rikkahub.data.task.TaskEntity) = TaskDto(
            id = entity.id,
            type = entity.type,
            status = entity.status,
            description = buildDescription(entity),
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            completedAt = entity.completedAt,
            errorMessage = entity.errorMessage,
            pollCount = entity.pollCount,
            result = entity.result,
        )

        private fun buildDescription(entity: me.rerere.rikkahub.data.task.TaskEntity): String {
            return try {
                val json = me.rerere.rikkahub.utils.JsonInstant
                when (entity.type) {
                    "ci_monitor" -> {
                        val config = json.decodeFromString(
                            me.rerere.rikkahub.data.task.TaskConfig.serializer(), entity.config
                        ) as? me.rerere.rikkahub.data.task.TaskConfig.CIMonitor ?: return ""
                        buildString {
                            append(config.repo)
                            if (config.branch.isNotBlank()) append(" @ ${config.branch}")
                        }
                    }
                    "timer" -> {
                        val config = json.decodeFromString(
                            me.rerere.rikkahub.data.task.TaskConfig.serializer(), entity.config
                        ) as? me.rerere.rikkahub.data.task.TaskConfig.Timer ?: return ""
                        config.message.ifBlank { "${config.delayMs / 1000}s timer" }
                    }
                    else -> ""
                }
            } catch (_: Exception) { "" }
        }
    }
}
