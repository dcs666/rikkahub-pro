package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.task.BackgroundTaskManager
import me.rerere.rikkahub.data.task.CITaskResult
import me.rerere.rikkahub.data.task.TaskEntity
import me.rerere.rikkahub.data.task.TaskStatus
import me.rerere.rikkahub.data.task.TaskType
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.plus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// [拆分] 任务卡片 UI 域（拆自 SettingTasksPage.kt，Strangler Fig）

@Composable
internal fun TaskCard(
    task: TaskEntity,
    onCancel: (() -> Unit)?,
    onDelete: (() -> Unit)? = null,
    onRerun: (() -> Unit)? = null,
) {
    val isActive = task.status == TaskStatus.PENDING || task.status == TaskStatus.RUNNING
    // 已完成任务：点击卡片展开/收起结果详情
    var expanded by remember { mutableStateOf(false) }
    val resultText = remember(task) { buildTaskResultText(task) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clickable(enabled = resultText != null) { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = when (task.status) {
                TaskStatus.COMPLETED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                TaskStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                TaskStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
            }
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Status icon
                    Icon(
                        when (task.status) {
                            TaskStatus.COMPLETED -> HugeIcons.Tick02
                            TaskStatus.FAILED -> HugeIcons.AlertCircle
                            TaskStatus.CANCELLED -> HugeIcons.Cancel01
                            else -> HugeIcons.Refresh01
                        },
                        null,
                        tint = when (task.status) {
                            TaskStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                            TaskStatus.FAILED -> MaterialTheme.colorScheme.error
                            TaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.secondary
                        },
                    )
                    Text(
                        text = when (task.type) {
                            TaskType.CI_MONITOR -> "CI Monitor"
                            TaskType.TIMER -> "Timer"
                            TaskType.WEBHOOK -> "Webhook"
                            else -> "Task"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                if (isActive && onCancel != null) {
                    IconButton(onClick = onCancel) {
                        Icon(HugeIcons.Cancel01, "Cancel", tint = MaterialTheme.colorScheme.error)
                    }
                } else if (!isActive && onDelete != null && onRerun == null) {
                    // 历史任务：删除记录（数据清理）
                    IconButton(onClick = onDelete) {
                        Icon(HugeIcons.Delete01, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (!isActive && onRerun != null) {
                    // 失败任务：一键重跑 CI（④）；删除仍可通过…（保留删除在重跑旁）
                    Row {
                        if (onDelete != null) {
                            IconButton(onClick = onDelete) {
                                Icon(HugeIcons.Delete01, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = onRerun) {
                            Icon(HugeIcons.Refresh01, "Rerun CI", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // Description
            val description = remember(task) { buildTaskDescription(task) }
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Error message
            if (task.errorMessage.isNotBlank()) {
                Text(
                    text = task.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 3,
                )
            }

            // 结果详情（点击卡片展开）
            if (resultText != null) {
                if (expanded) {
                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    Text(
                        text = "Tap to view result",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // Timestamp
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (task.completedAt > 0) {
                        "done ${formatTime(task.completedAt)}"
                    } else {
                        formatTime(task.createdAt)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (task.pollCount > 0 && isActive) {
                    Text(
                        text = "polls: ${task.pollCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = task.status.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (task.status) {
                        TaskStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                        TaskStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

internal fun buildTaskDescription(task: TaskEntity): String {
    return try {
        when (task.type) {
            TaskType.CI_MONITOR -> {
                val config = JsonInstant.decodeFromString(me.rerere.rikkahub.data.task.TaskConfig.serializer(), task.config)
                    as? me.rerere.rikkahub.data.task.TaskConfig.CIMonitor ?: return ""
                buildString {
                    append(config.repo)
                    if (config.branch.isNotBlank()) append(" @ ${config.branch}")
                    if (config.workflowName.isNotBlank()) append(" (${config.workflowName})")
                }
            }
            TaskType.TIMER -> {
                val config = JsonInstant.decodeFromString(me.rerere.rikkahub.data.task.TaskConfig.serializer(), task.config)
                    as? me.rerere.rikkahub.data.task.TaskConfig.Timer ?: return ""
                buildString {
                    append(config.message.ifBlank { "Timer (${config.delayMs / 1000}s)" })
                    if (config.repeatIntervalMs > 0) {
                        append(" (every ${config.repeatIntervalMs / 60_000}min")
                        if (config.repeatCount > 0) append(", max ${config.repeatCount}x")
                        append(")")
                    }
                    if (config.autoAi) append(" [AI]")
                }
            }
            else -> ""
        }
    } catch (_: Exception) {
        ""
    }
}

/**
 * 把任务的 result JSON 解析为可读文本（UI 展开详情用）。
 * CI 任务 → 结论/工作流/run 号/分支/commit/失败 job 摘要/链接；
 * 其他任务（如 timer 的 {"message": "..."}）→ 解析失败时原文截断展示。
 */
internal fun buildTaskResultText(task: TaskEntity): String? {
    if (task.result.isBlank()) return null
    return try {
        val result = JsonInstant.decodeFromString(CITaskResult.serializer(), task.result)
        buildString {
            append("Conclusion: ").append(result.conclusion.ifBlank { "n/a" })
            append("\nWorkflow: ").append(result.workflowName.ifBlank { "n/a" })
            append(" #").append(result.runNumber)
            append("\nBranch: ").append(result.branch.ifBlank { "n/a" })
            if (result.commitMessage.isNotBlank()) append("\nCommit: ").append(result.commitMessage)
            result.failedJobs.forEach { job ->
                append("\n\nFailed job: ").append(job.name)
                if (job.errorSummary.isNotBlank()) {
                    append("\n").append(job.errorSummary.take(400))
                }
            }
            if (result.htmlUrl.isNotBlank()) append("\n\n").append(result.htmlUrl)
        }
    } catch (_: Exception) {
        // 非 CITaskResult 格式（timer 等）：直接展示原文
        task.result.take(300)
    }
}

/** [⑦] 解析自动监控白名单输入（逗号/换行分隔，去空白去重）。 */
internal fun parseRepoList(input: String): List<String> =
    input.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }.distinct()

/** [⑧] 发送测试请求到完成回调 URL，验证可用性。 */
internal suspend fun testCompletionWebhook(url: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val payload = """{"event":"test","message":"RikkaHub background-task webhook test","timestamp":${System.currentTimeMillis()}}"""
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response -> response.isSuccessful }
    } catch (e: Exception) {
        false
    }
}

internal fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

