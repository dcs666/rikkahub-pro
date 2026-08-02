package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock01
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
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingTasksPage() {
    val taskManager: BackgroundTaskManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    val activeTaskCount by taskManager.activeTaskCount.collectAsStateWithLifecycle()
    val recentTasks by taskManager.recentTasks.collectAsStateWithLifecycle()

    var showToken by remember { mutableStateOf(false) }
    var tokenInput by remember(settings.taskGithubToken) { mutableStateOf(settings.taskGithubToken) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Background Tasks") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- Settings Section ----
            item("settings") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("Task Settings") },
                ) {
                    // GitHub Token
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(HugeIcons.Github, null, modifier = Modifier.padding(end = 8.dp))
                                Text("GitHub Token", style = MaterialTheme.typography.bodyLarge)
                            }
                            Text(
                                "Optional. Required for private repos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 32.dp, bottom = 4.dp),
                            )
                            TextField(
                                value = tokenInput,
                                onValueChange = { tokenInput = it },
                                modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
                                placeholder = { Text("ghp_xxxx or github_pat_xxxx") },
                                singleLine = true,
                                visualTransformation = if (showToken) VisualTransformation.None
                                    else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { showToken = !showToken }) {
                                        Icon(if (showToken) HugeIcons.View else HugeIcons.ViewOff, null)
                                    }
                                },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        settingsStore.update(settings.copy(taskGithubToken = tokenInput.trim()))
                                    }
                                },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text("Save Token")
                            }
                        }
                    }

                    // Auto Analyze
                    item(
                        leadingContent = { Icon(HugeIcons.Settings03, null) },
                        headlineContent = { Text("Auto-analyze on failure") },
                        supportingContent = { Text("Automatically ask AI to analyze CI errors") },
                        trailingContent = {
                            Switch(
                                checked = settings.taskAutoAnalyze,
                                onCheckedChange = {
                                    scope.launch {
                                        settingsStore.update(settings.copy(taskAutoAnalyze = it))
                                    }
                                },
                            )
                        },
                    )

                    // Notify on success
                    item(
                        leadingContent = { Icon(HugeIcons.Tick02, null) },
                        headlineContent = { Text("Notify on success") },
                        supportingContent = { Text("Show notification when CI passes") },
                        trailingContent = {
                            Switch(
                                checked = settings.taskNotifyOnSuccess,
                                onCheckedChange = {
                                    scope.launch {
                                        settingsStore.update(settings.copy(taskNotifyOnSuccess = it))
                                    }
                                },
                            )
                        },
                    )

                    // Poll interval
                    item(
                        leadingContent = { Icon(HugeIcons.Clock01, null) },
                        headlineContent = { Text("Poll interval") },
                        supportingContent = { Text("${settings.taskPollIntervalSec}s between status checks") },
                    )
                }
            }

            // ---- Active Tasks ----
            if (activeTaskCount > 0) {
                item("activeHeader") {
                    Text(
                        "Active Tasks ($activeTaskCount)",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                items(
                    items = recentTasks.filter { it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING },
                    key = { "active_${it.id}" },
                ) { task ->
                    TaskCard(
                        task = task,
                        onCancel = {
                            scope.launch { taskManager.cancelTask(task.id) }
                        },
                    )
                }
            }

            // ---- Recent Tasks ----
            item("recentHeader") {
                Text(
                    "Recent Tasks",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            val completedTasks = recentTasks.filter {
                it.status == TaskStatus.COMPLETED || it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED
            }
            if (completedTasks.isEmpty()) {
                item("empty") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                    ) {
                        Text(
                            "No tasks yet. Ask AI to monitor a CI build!",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(
                    items = completedTasks,
                    key = { "recent_${it.id}" },
                ) { task ->
                    TaskCard(task = task, onCancel = null)
                }
            }

            // ---- Cancel All ----
            if (activeTaskCount > 0) {
                item("cancelAll") {
                    TextButton(
                        onClick = { scope.launch { taskManager.cancelAll() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(HugeIcons.Cancel01, null, modifier = Modifier.padding(end = 4.dp))
                        Text("Cancel All Active Tasks")
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskEntity,
    onCancel: (() -> Unit)?,
) {
    val isActive = task.status == TaskStatus.PENDING || task.status == TaskStatus.RUNNING

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
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

            // Timestamp
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatTime(task.createdAt),
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

private fun buildTaskDescription(task: TaskEntity): String {
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
                config.message.ifBlank { "Timer (${config.delayMs / 1000}s)" }
            }
            else -> ""
        }
    } catch (_: Exception) {
        ""
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
