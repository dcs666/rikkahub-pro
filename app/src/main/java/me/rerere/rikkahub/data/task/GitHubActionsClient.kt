package me.rerere.rikkahub.data.task

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GitHub Actions API 客户端。
 * 轮询 workflow run 状态，获取失败 job 日志摘要。
 */
class GitHubActionsClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

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
        } catch (e: Exception) {
            Result.failure(e)
        }
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
            add("per_page=5")
            if (branch.isNotBlank()) add("branch=$branch")
            if (workflowName.isNotBlank()) add("event=push")
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
            .apply {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ""
            val logText = response.body?.string() ?: return ""
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
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub API error: ${response.code} ${response.message}")
            }
            return response.body?.string() ?: throw IOException("Empty response body")
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
