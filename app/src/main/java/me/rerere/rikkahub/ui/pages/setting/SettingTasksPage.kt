package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.task.BackgroundTaskManager
import me.rerere.rikkahub.data.task.TaskStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

// 轮询间隔档位（与工具端 10s 下限对齐，避免未认证配额被耗）
internal val POLL_INTERVAL_OPTIONS = listOf(10, 30, 60, 120, 300)

private enum class CreateTaskType { CI_MONITOR, TIMER }

@Composable
fun SettingTasksPage() {
    val taskManager: BackgroundTaskManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    val activeTaskCount by taskManager.activeTaskCount.collectAsStateWithLifecycle()
    val recentTasks by taskManager.recentTasks.collectAsStateWithLifecycle()

    var showCreateMenu by remember { mutableStateOf(false) }
    var createType by remember { mutableStateOf<CreateTaskType?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Background Tasks") },
                navigationIcon = { BackButton() },
                actions = {
                    Box {
                        IconButton(onClick = { showCreateMenu = true }) {
                            Icon(HugeIcons.Add01, "New task")
                        }
                        DropdownMenu(expanded = showCreateMenu, onDismissRequest = { showCreateMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Monitor CI build") },
                                onClick = {
                                    showCreateMenu = false
                                    createType = CreateTaskType.CI_MONITOR
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Create timer") },
                                onClick = {
                                    showCreateMenu = false
                                    createType = CreateTaskType.TIMER
                                },
                            )
                        }
                    }
                },
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
                TaskSettingsSection(
                    settings = settings,
                    settingsStore = settingsStore,
                    scope = scope,
                    toaster = toaster,
                )
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
                        onDelete = null,
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
                    TaskCard(
                        task = task,
                        onCancel = null,
                        // [④] 失败任务可一键重跑 CI（重置任务并重新监控）
                        onDelete = {
                            scope.launch { taskManager.deleteTask(task.id) }
                        },
                        onRerun = if (task.status == TaskStatus.FAILED) {
                            {
                                scope.launch {
                                    val result = taskManager.rerunTask(task.id, settings.taskGithubToken)
                                    result.fold(
                                        onSuccess = { toaster.show("Rerun triggered") },
                                        onFailure = { toaster.show("Rerun failed: ${it.message}") },
                                    )
                                }
                            }
                        } else null,
                    )
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

    // ---- 创建 CI 监控对话框 ----
    if (createType == CreateTaskType.CI_MONITOR) {
        CreateCIMonitorDialog(
            settings = settings,
            taskManager = taskManager,
            scope = scope,
            toaster = toaster,
            onDismiss = { createType = null },
        )
    }

    // ---- 创建定时器对话框 ----
    if (createType == CreateTaskType.TIMER) {
        CreateTimerDialog(
            taskManager = taskManager,
            scope = scope,
            toaster = toaster,
            onDismiss = { createType = null },
        )
    }
}
