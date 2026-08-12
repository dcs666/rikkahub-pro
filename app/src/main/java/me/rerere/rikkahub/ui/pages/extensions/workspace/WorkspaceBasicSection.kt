package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.plus
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.RootfsUrls
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

// [拆分] Workspace 基础信息/工具审批/安装进度域（拆自 WorkspaceDetailPage.kt，Strangler Fig）

@Composable
internal fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    rootfsReady: Boolean,
    installProgress: RootfsInstallProgress?,
    prootStatus: String,
    prootUpdating: Boolean,
    prootMessage: String?,
    onInstallRootfs: () -> Unit,
    onUpdateProot: () -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    val shellStatus = workspace?.shellStatus
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    // [FIX] rootfsReady 使用 VM 实时磁盘检查结果（不再直接信任 DB shellStatus，
    // 避免 rootfs 丢失后界面仍显示"就绪"而 shell 报未安装）
    val installButtonText = when {
        installing -> stringResource(R.string.workspace_detail_installing)
        rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
        else -> stringResource(R.string.workspace_detail_install_rootfs)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_workspace_info),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_name), workspace?.name ?: stringResource(R.string.workspace_detail_loading))
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_shell_status), workspace?.shellStatus?.toShellStatusLabel() ?: "-")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onInstallRootfs,
                        enabled = workspace != null && !installing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(HugeIcons.Bash, contentDescription = null)
                        Text(
                            text = installButtonText,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    installProgress?.let { progress ->
                        RootfsProgress(progress)
                    }

                    // [TURBO] Proot 运行时：显示版本 + 手动更新按钮（不再自动下载解压）
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.workspace_detail_proot_runtime),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = prootStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            prootMessage?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (message.startsWith("Proot updated") || message.startsWith("Proot is up to date")) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = onUpdateProot,
                            enabled = !prootUpdating,
                        ) {
                            if (prootUpdating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    HugeIcons.Refresh01,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Text(
                                text = stringResource(
                                    if (prootUpdating) R.string.workspace_detail_proot_updating
                                    else R.string.workspace_detail_update_proot
                                ),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        item {
            WorkspaceToolApprovalCard(
                workspace = workspace,
                onToolApprovalChange = onToolApprovalChange,
            )
        }
    }
}

@Composable
internal fun WorkspaceToolApprovalCard(
    workspace: WorkspaceEntity?,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    val overrides = workspace?.toolApprovalOverrides().orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            workspaceToolApprovalItems().forEach { (toolName, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = toolName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = resolveWorkspaceToolApproval(toolName, overrides),
                        onCheckedChange = { onToolApprovalChange(toolName, it) },
                        enabled = workspace != null,
                    )
                }
            }
        }
    }
}

@Composable
internal fun workspaceToolApprovalItems() = listOf(
    "workspace_read_file" to stringResource(R.string.workspace_detail_tool_read_file),
    "workspace_write_file" to stringResource(R.string.workspace_detail_tool_write_file),
    "workspace_edit_file" to stringResource(R.string.workspace_detail_tool_edit_file),
    "workspace_shell" to stringResource(R.string.workspace_detail_tool_shell),
)

@Composable
internal fun WorkspaceInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(R.string.workspace_detail_downloading, progress.bytesRead.fileSizeToString(), total)
                }

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable(workspace.id) { mutableStateOf(RootfsUrls.DEFAULT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_detail_download_url)) },
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
