package me.rerere.rikkahub.data.task

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GitHub API rate limit 命中（403 配额耗尽 / 429 太频繁）。
 * 与普通网络错误区分：调用方应退避而不是重试。
 */
class GitHubRateLimitException(message: String) : IOException(message)

/**
 * GitHub Actions API 客户端。
 * 轮询 workflow run 状态，获取失败 job 日志摘要。
 */
class GitHubActionsClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun Request.Builder.withGitHubHeaders(token: String): Request.Builder {
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
        header("User-Agent", "RikkaHub-Turbo/1.0")
        if (token.isNotBlank()) header("Authorization", "Bearer $token")
        return this
    }

    /**
     * 获取指定 repo 最新的 workflow runs。
     * 如果指定了 runId 则直接获取该 run。
     */
    fun getLatestRun(config: TaskConfig.CIMonitor): Result<CITaskResult> {
        return try {
            val result = if (config.runId > 0) {
                getRunById(config.repo, config.runId, config.githubToken)
            } else {
                getLatestRunByBranch(config.repo, config.branch, config.workflowName, config.githubToken)
            }
            Result.success(result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 协程取消必须传播，不能吞掉
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 重新触发一个已完成/失败的 workflow run。
     * 需要 token 具备 actions:write 权限；403 会以失败 Result 返回。
     */
    fun rerunWorkflow(repo: String, runId: Long, token: String): Result<Unit> {
        return try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/actions/runs/$runId/rerun")
                .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                .withGitHubHeaders(token)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("GitHub API error: ${response.code} ${response.message}")
                }
            }
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 协程取消必须传播，不能吞掉
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 是否为 GitHub rate limit 错误（403 配额耗尽 / 429 太频繁）。
     * BackgroundTaskManager 据此触发强制退避而不是累加失败计数。
     */
    fun isRateLimitError(e: Throwable): Boolean = when (e) {
        is GitHubRateLimitException -> true
        is IOException -> {
            val message = e.message.orEmpty()
            // 精确匹配 403/429（旧版消息格式 "GitHub API error: <code>"）
            message.contains("GitHub API error: 403") || message.contains("GitHub API error: 429")
        }
        else -> false
    }

    /**
     * 获取失败 job 的错误日志摘要（最后 50 行）。
     */
    fun getFailedJobLogs(repo: String, runId: Long, token: String): List<FailedJob> {
        return try {
            val jobs = getJobs(repo, runId, token)
            jobs.filter { it.conclusion == "failure" }.map { job ->
                val logSummary = try {
                    getJobLogSummary(repo, job.id, token)
                } catch (_: Exception) {
                    ""
                }
                FailedJob(
                    name = job.name,
                    conclusion = job.conclusion,
                    htmlUrl = job.htmlUrl,
                    errorSummary = logSummary,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- Private API calls ----

    private fun getRunById(repo: String, runId: Long, token: String): CITaskResult {
        val url = "https://api.github.com/repos/$repo/actions/runs/$runId"
        val body = httpGet(url, token)
        val run = json.decodeFromString<WorkflowRun>(body)
        return run.toResult()
    }

    private fun getLatestRunByBranch(
        repo: String,
        branch: String,
        workflowName: String,
        token: String
    ): CITaskResult {
        val params = buildList {
            // 未指定 workflow 时 5 条足够取到最新 run；指定时取更多用于客户端过滤，
            // 避免目标 workflow 的最新 run 被其他 workflow 的 run 挤出列表
            add(if (workflowName.isNotBlank()) "per_page=30" else "per_page=5")
            if (branch.isNotBlank()) add("branch=$branch")
        }.joinToString("&")

        val url = "https://api.github.com/repos/$repo/actions/runs?$params"
        val body = httpGet(url, token)
        val response = json.decodeFromString<WorkflowRunsResponse>(body)

        val runs = response.workflowRuns
        val targetRun = if (workflowName.isNotBlank()) {
            runs.firstOrNull { it.name.equals(workflowName, ignoreCase = true) }
        } else {
            runs.firstOrNull()
        }

        return targetRun?.toResult() ?: CITaskResult(status = "not_found")
    }

    private fun getJobs(repo: String, runId: Long, token: String): List<WorkflowJob> {
        val url = "https://api.github.com/repos/$repo/actions/runs/$runId/jobs?per_page=30"
        val body = httpGet(url, token)
        val response = json.decodeFromString<WorkflowJobsResponse>(body)
        return response.jobs
    }

    private fun getJobLogSummary(repo: String, jobId: Long, token: String): String {
        val url = "https://api.github.com/repos/$repo/actions/jobs/$jobId/logs"
        val request = Request.Builder()
            .url(url)
            .withGitHubHeaders(token)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ""
            // [FIX] 错误行通常在日志末尾，只取前 512KB 会截掉末尾错误。
            // 读取最多 4MB（GitHub 日志一般 <2MB），超限截断。source.request 会在
            // 达到 4MB 时返回 false，snapshot 拿到已缓冲内容。
            val source = response.body?.source() ?: return ""
            source.request(4L * 1024 * 1024)
            val logText = source.buffer.snapshot().utf8()
            // 提取错误行（包含 Error、FAILED、error: 的行），取最后 30 行
            val errorLines = logText.lines()
                .filter { line ->
                    line.contains("Error", ignoreCase = true) ||
                    line.contains("FAILED", ignoreCase = true) ||
                    line.contains("error:", ignoreCase = true) ||
                    line.contains("Exception", ignoreCase = true)
                }
                .takeLast(30)
            return errorLines.joinToString("\n").take(2000)
        }
    }

    private fun httpGet(url: String, token: String): String {
        val request = Request.Builder()
            .url(url)
            .withGitHubHeaders(token)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // rate limit 命中（403 且配额耗尽，或 429 太频繁）→ 抛专用异常
                if (response.code == 403 || response.code == 429) {
                    val remaining = response.header("X-RateLimit-Remaining")
                    val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()
                    if (remaining == "0" || response.code == 429) {
                        throw GitHubRateLimitException(
                            "GitHub API rate limit (${response.code}), reset=${reset ?: "unknown"}"
                        )
                    }
                }
                throw IOException("GitHub API error: ${response.code} ${response.message}")
            }
            // [FIX] 限量读取：与 getJobLogSummary 的 4MB 上限保持一致，异常响应
            // （代理拦截页等）不会被全量读入内存。
            val source = response.body?.source() ?: throw IOException("Empty response body")
            source.request(4L * 1024 * 1024)
            return source.buffer.snapshot().utf8()
        }
    }

    // ---- Response models ----

    @Serializable
    private data class WorkflowRunsResponse(
        @SerialName("total_count") val totalCount: Int = 0,
        @SerialName("workflow_runs") val workflowRuns: List<WorkflowRun> = emptyList(),
    )

    @Serializable
    private data class WorkflowRun(
        val id: Long = 0,
        @SerialName("run_number") val runNumber: Int = 0,
        val name: String = "",
        val status: String = "", // "queued", "in_progress", "completed"
        val conclusion: String? = null, // "success", "failure", "cancelled", "timed_out"
        @SerialName("head_branch") val headBranch: String = "",
        @SerialName("head_sha") val headSha: String = "",
        @SerialName("head_commit") val headCommit: HeadCommit? = null,
        @SerialName("html_url") val htmlUrl: String = "",
        @SerialName("created_at") val createdAt: String = "",
        @SerialName("updated_at") val updatedAt: String = "",
    ) {
        fun toResult() = CITaskResult(
            runId = id,
            runNumber = runNumber,
            status = status,
            conclusion = conclusion ?: "",
            workflowName = name,
            branch = headBranch,
            commitMessage = headCommit?.message?.lines()?.firstOrNull() ?: "",
            commitSha = headSha.take(7),
            htmlUrl = htmlUrl,
            startedAt = createdAt,
            completedAt = updatedAt,
        )
    }

    @Serializable
    private data class HeadCommit(
        val message: String = "",
    )

    @Serializable
    private data class WorkflowJobsResponse(
        @SerialName("total_count") val totalCount: Int = 0,
        val jobs: List<WorkflowJob> = emptyList(),
    )

    @Serializable
    private data class WorkflowJob(
        val id: Long = 0,
        val name: String = "",
        val status: String = "",
        val conclusion: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
    )
}
