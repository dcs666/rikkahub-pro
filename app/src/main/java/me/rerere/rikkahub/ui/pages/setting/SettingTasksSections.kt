package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.task.BackgroundTaskManager
import me.rerere.rikkahub.ui.components.ui.CardGroup
import com.dokar.sonner.ToasterState

/** 任务设置卡片区（Token / Auto-watch / Webhook / 开关 / 轮询档位）。拆自 SettingTasksPage（Strangler Fig）。 */
@Composable
internal fun TaskSettingsSection(
    settings: Settings,
    settingsStore: SettingsStore,
    scope: kotlinx.coroutines.CoroutineScope,
    toaster: ToasterState,
) {
    var showToken by remember { mutableStateOf(false) }
    var tokenInput by remember(settings.taskGithubToken) { mutableStateOf(settings.taskGithubToken) }

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
                            toaster.show("Token saved")
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Save Token")
                }
            }
        }

        // Auto Watch Repos（⑦ 全自动监控白名单）
        item {
            var watchInput by remember(settings.taskAutoWatchRepos) {
                mutableStateOf(settings.taskAutoWatchRepos)
            }
            val parsedRepos = remember(watchInput) { parseRepoList(watchInput) }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(HugeIcons.Github, null, modifier = Modifier.padding(end = 8.dp))
                    Text("Auto-watch repos", style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    "Repos whose new workflow runs get monitored automatically (webhook). Comma or newline separated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 32.dp, bottom = 4.dp),
                )
                TextField(
                    value = watchInput,
                    onValueChange = { watchInput = it },
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
                    placeholder = { Text("dcs666/rikkahub-turbo\noctocat/hello-world") },
                )
                // 已配置列表（可单个删除）
                parsedRepos.forEach { repo ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
                    ) {
                        Text(
                            repo,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                watchInput = parsedRepos.filter { it != repo }.joinToString(", ")
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                HugeIcons.Cancel01,
                                "Remove $repo",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                if (parsedRepos.isEmpty()) {
                    Text(
                        "⚠ Not configured — new CI runs will NOT be monitored automatically. Ask AI or create manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 24.dp, top = 2.dp),
                    )
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            settingsStore.update(settings.copy(taskAutoWatchRepos = parsedRepos.joinToString(", ")))
                            toaster.show("Auto-watch list saved")
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Save") }
            }
        }

        // Completion webhook URL（⑧ 外部回调）
        item {
            var webhookUrlInput by remember(settings.taskWebhookUrl) {
                mutableStateOf(settings.taskWebhookUrl)
            }
            var testing by remember { mutableStateOf(false) }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(HugeIcons.Clock01, null, modifier = Modifier.padding(end = 8.dp))
                    Text("Completion webhook URL", style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    "POST task result JSON to this URL when a task finishes (e.g. Server酱/Bark)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 32.dp, bottom = 4.dp),
                )
                TextField(
                    value = webhookUrlInput,
                    onValueChange = { webhookUrlInput = it },
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
                    placeholder = { Text("https://sctapi.ftqq.com/...") },
                    singleLine = true,
                )
                Row(modifier = Modifier.align(Alignment.End)) {
                    // 发送测试请求验证 URL 可用
                    TextButton(
                        enabled = webhookUrlInput.isNotBlank() && !testing,
                        onClick = {
                            val url = webhookUrlInput.trim()
                            scope.launch {
                                testing = true
                                val ok = withContext(Dispatchers.IO) { testCompletionWebhook(url) }
                                toaster.show(if (ok) "Test sent ✓ (HTTP ok)" else "Test failed — check URL/network")
                                testing = false
                            }
                        },
                    ) { Text(if (testing) "Sending..." else "Test") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                settingsStore.update(settings.copy(taskWebhookUrl = webhookUrlInput.trim()))
                                toaster.show("Webhook URL saved")
                            }
                        },
                    ) { Text("Save") }
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
            supportingContent = { Text("Time between GitHub status checks") },
            trailingContent = {
                // [OPT] 档位选择替代静态文本：直接约束在合理区间，
                // 与工具端 10s 下限保持一致
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) {
                        Text("${settings.taskPollIntervalSec}s")
                        Icon(HugeIcons.ArrowDown01, null, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        POLL_INTERVAL_OPTIONS.forEach { seconds ->
                            DropdownMenuItem(
                                text = { Text("$seconds seconds") },
                                onClick = {
                                    expanded = false
                                    scope.launch {
                                        settingsStore.update(
                                            settings.copy(taskPollIntervalSec = seconds)
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}

/** 创建 CI 监控任务对话框。拆自 SettingTasksPage（Strangler Fig）。 */
@Composable
internal fun CreateCIMonitorDialog(
    settings: Settings,
    taskManager: BackgroundTaskManager,
    scope: kotlinx.coroutines.CoroutineScope,
    toaster: ToasterState,
    onDismiss: () -> Unit,
) {
    var repoInput by remember { mutableStateOf("") }
    var branchInput by remember { mutableStateOf("") }
    var workflowInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monitor CI build") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = repoInput,
                    onValueChange = { repoInput = it },
                    label = { Text("Repository") },
                    placeholder = { Text("owner/repo") },
                    singleLine = true,
                )
                TextField(
                    value = branchInput,
                    onValueChange = { branchInput = it },
                    label = { Text("Branch") },
                    placeholder = { Text("empty = latest") },
                    singleLine = true,
                )
                TextField(
                    value = workflowInput,
                    onValueChange = { workflowInput = it },
                    label = { Text("Workflow") },
                    placeholder = { Text("e.g. Build APK (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = repoInput.isNotBlank(),
                onClick = {
                    val repo = repoInput.trim()
                    scope.launch {
                        taskManager.createCIMonitorTask(
                            repo = repo,
                            branch = branchInput.trim(),
                            workflowName = workflowInput.trim(),
                            pollIntervalMs = settings.taskPollIntervalSec.toLong() * 1000,
                        )
                        toaster.show("CI monitor created: $repo")
                    }
                    onDismiss()
                },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** 创建定时器任务对话框。拆自 SettingTasksPage（Strangler Fig）。 */
@Composable
internal fun CreateTimerDialog(
    taskManager: BackgroundTaskManager,
    scope: kotlinx.coroutines.CoroutineScope,
    toaster: ToasterState,
    onDismiss: () -> Unit,
) {
    var timerMinutesInput by remember { mutableStateOf("") }
    var timerMessageInput by remember { mutableStateOf("") }
    var timerRepeatMinutesInput by remember { mutableStateOf("") }
    var timerAutoAi by remember { mutableStateOf(false) }
    var timerStepsInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = timerMinutesInput,
                    onValueChange = { timerMinutesInput = it },
                    label = { Text("Delay (minutes)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                TextField(
                    value = timerMessageInput,
                    onValueChange = { timerMessageInput = it },
                    label = { Text("Message") },
                    placeholder = { Text("What should I remind you about?") },
                )
                // [⑥] 重复间隔（0 = 一次性）
                TextField(
                    value = timerRepeatMinutesInput,
                    onValueChange = { timerRepeatMinutesInput = it },
                    label = { Text("Repeat every (minutes, 0 = one-shot)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                // [⑨] 到期 AI 执行
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "AI executes on fire",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = timerAutoAi,
                        onCheckedChange = { timerAutoAi = it },
                    )
                }
                // [⑨ M2] 工作流步骤：每行一条指令，按序执行（需开启 AI executes）
                if (timerAutoAi) {
                    TextField(
                        value = timerStepsInput,
                        onValueChange = { timerStepsInput = it },
                        label = { Text("Workflow steps (one per line)") },
                        placeholder = {
                            Text("研究这个话题的进展\n把结论总结成 5 条要点\n保存到我的记忆里")
                        },
                        minLines = 2,
                        maxLines = 8,
                    )
                }
            }
        },
        confirmButton = {
            val minutes = timerMinutesInput.toLongOrNull()
            TextButton(
                enabled = minutes != null && minutes > 0,
                onClick = {
                    val delayMs = minutes!! * 60_000
                    val repeatMs = timerRepeatMinutesInput.toLongOrNull()?.times(60_000) ?: 0L
                    val message = timerMessageInput.trim().ifBlank { "Timer" }
                    // [⑨ M2] 多行输入按行拆分为步骤（去空白行）
                    val steps = timerStepsInput.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    scope.launch {
                        taskManager.createTimerTask(
                            delayMs = delayMs,
                            message = message,
                            repeatIntervalMs = repeatMs,
                            autoAi = timerAutoAi,
                            steps = steps,
                        )
                        toaster.show("Timer set for ${minutes}m" + if (steps.size > 1) " (workflow: ${steps.size} steps)" else "")
                    }
                    onDismiss()
                },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
