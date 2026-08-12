import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.ui.components.ui.Greeting
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.EditState
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.utils.toDp
import kotlin.uuid.Uuid
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ui.Tooltip
import androidx.compose.ui.draw.clip
import me.rerere.rikkahub.ui.hooks.EditStateContent



/**
 * 抽屉用户区：头像 + 昵称（可点击编辑）+ 问候语。拆自 ChatDrawerContent（Strangler Fig）。
 */
@Composable
internal fun DrawerUserSection(
    settings: Settings,
    nicknameEditState: EditState<String>,
    vm: ChatVM,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        UIAvatar(
            name = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
            value = settings.displaySetting.userAvatar,
            onUpdate = { newAvatar ->
                vm.updateSettings(
                    settings.copy(
                        displaySetting = settings.displaySetting.copy(
                            userAvatar = newAvatar
                        )
                    )
                )
            },
            modifier = Modifier.size(50.dp),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable {
                        nicknameEditState.open(settings.displaySetting.userNickname)
                    }
                )

                Icon(
                    imageVector = HugeIcons.PencilEdit01,
                    contentDescription = "Edit",
                    modifier = Modifier
                        .onClick {
                            nicknameEditState.open(settings.displaySetting.userNickname)
                        }
                        .size(LocalTextStyle.current.fontSize.toDp())
                )
            }
            Greeting(
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * 抽屉底部导航：助手/菜单/收藏/统计/设置。拆自 ChatDrawerContent（Strangler Fig）。
 */
@Composable
internal fun DrawerBottomActions(navController: Navigator) {
    var showMenuPopup by remember { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        DrawerAction(
            icon = {
                Icon(
                    imageVector = HugeIcons.LookTop,
                    contentDescription = stringResource(R.string.assistant_page_title)
                )
            },
            label = {
                Text(stringResource(R.string.assistant_page_title))
            },
            onClick = {
                navController.navigate(Screen.Assistant)
            },
        )

        Box {
            DrawerAction(
                icon = {
                    Icon(HugeIcons.Sparkles, "Menu")
                },
                label = {
                    Text(stringResource(R.string.menu))
                },
                onClick = {
                    showMenuPopup = true
                },
            )
            DropdownMenu(
                expanded = showMenuPopup,
                onDismissRequest = { showMenuPopup = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_page_menu_ai_translator)) },
                    leadingIcon = { Icon(HugeIcons.LanguageCircle, null) },
                    onClick = {
                        showMenuPopup = false
                        navController.navigate(Screen.Translator)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_page_menu_image_generation)) },
                    leadingIcon = { Icon(HugeIcons.Image02, null) },
                    onClick = {
                        showMenuPopup = false
                        navController.navigate(Screen.ImageGen)
                    }
                )
            }
        }

        DrawerAction(
            icon = {
                Icon(HugeIcons.InLove, stringResource(R.string.favorite_page_title))
            },
            label = {
                Text(stringResource(R.string.favorite_page_title))
            },
            onClick = {
                navController.navigate(Screen.Favorite)
            },
        )

        DrawerAction(
            icon = {
                Icon(HugeIcons.ChartColumn, "统计数据")
            },
            label = {
                Text("统计数据")
            },
            onClick = {
                navController.navigate(Screen.Stats)
            },
        )

        Spacer(Modifier.weight(1f))

        DrawerAction(
            icon = {
                Icon(HugeIcons.Settings03, null)
            },
            label = { Text(stringResource(R.string.settings)) },
            onClick = {
                navController.navigate(Screen.Setting)
            },
        )
    }
}

/** 昵称编辑对话框。拆自 ChatDrawerContent（Strangler Fig）。 */
@Composable
internal fun NicknameEditDialog(nicknameEditState: EditState<String>) {
    nicknameEditState.EditStateContent { nickname, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                nicknameEditState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_nickname))
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

/** 移动到文件夹 Bottom Sheet。拆自 ChatDrawerContent（Strangler Fig）。 */
@Composable
internal fun MoveToFolderSheet(
    folders: List<Folder>,
    conversationToMoveFolder: Conversation?,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onMove: (Uuid?) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_page_move_to_folder),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 移出文件夹（未归类）
            Surface(
                onClick = { onMove(null) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = if (conversationToMoveFolder?.folderId == null) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(HugeIcons.Folder01, null)
                    Text(
                        text = stringResource(R.string.chat_page_remove_from_folder),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(folders, key = { it.id }) { folder ->
                    val isCurrent = folder.id == conversationToMoveFolder?.folderId
                    Surface(
                        onClick = { onMove(folder.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        tonalElevation = if (isCurrent) 2.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(HugeIcons.Folder01, null)
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 新建文件夹对话框。拆自 ChatDrawerContent（Strangler Fig）。 */
@Composable
internal fun CreateFolderDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_page_create_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.chat_page_folder_name)) }
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(name)
                },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.chat_page_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_page_cancel))
            }
        }
    )
}

/** 重命名文件夹对话框。拆自 ChatDrawerContent（Strangler Fig）。 */
@Composable
internal fun RenameFolderDialog(
    folder: Folder?,
    onRename: (Folder, String) -> Unit,
    onDismiss: () -> Unit,
) {
    folder?.let { current ->
        var name by remember(current.id) { mutableStateOf(current.name) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.chat_page_rename_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(current, name)
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

/** 删除文件夹确认对话框。拆自 ChatDrawerContent（Strangler Fig）。 */
@Composable
internal fun DeleteFolderDialog(
    folder: Folder?,
    onDelete: (Folder) -> Unit,
    onDismiss: () -> Unit,
) {
    folder?.let { current ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.chat_page_delete_folder)) },
            text = { Text(stringResource(R.string.chat_page_delete_folder_confirm, current.name)) },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(current) }
                ) { Text(stringResource(R.string.chat_page_delete)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

/** 移动到助手 Bottom Sheet。拆自 ChatDrawerContent（Strangler Fig）。 */
@Composable
internal fun MoveToAssistantSheet(
    assistants: List<Assistant>,
    conversationToMove: Conversation?,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onMove: (Conversation, Uuid) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_page_move_to_assistant),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(assistants, key = { it.id }) { assistant ->
                    AssistantItem(
                        assistant = assistant,
                        isCurrentAssistant = assistant.id == conversationToMove?.assistantId,
                        onClick = {
                            conversationToMove?.let { conversation ->
                                onMove(conversation, assistant.id)
                            }
                        }
                    )
                }
            }
        }
    }
}



// [拆分] ChatDrawer 侧栏子组件域（拆自 ChatDrawer.kt，Strangler Fig）

@Composable
internal fun DrawerActions(navController: Navigator) {
    Column {
        // 搜索入口
        Surface(
            onClick = { navController.navigate(Screen.MessageSearch) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.chat_page_search_chats),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // 历史记录入口
        Surface(
            onClick = { navController.navigate(Screen.History) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.TransactionHistory,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.chat_page_history),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
internal fun DrawerAction(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Tooltip(
            tooltip = {
                label()
            }
        ) {
            Box(
                modifier = Modifier
                    .padding(10.dp)
                    .size(20.dp),
            ) {
                icon()
            }
        }
    }
}

@Composable
internal fun FolderBar(
    folders: List<Folder>,
    selectedFolderId: Uuid?,
    onSelect: (Uuid?) -> Unit,
    onCreate: () -> Unit,
    onRename: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            FolderChip(
                label = stringResource(R.string.chat_page_folder_default),
                selected = selectedFolderId == null,
                onClick = { onSelect(null) },
                onLongClick = {},
            )
        }
        items(folders, key = { it.id }) { folder ->
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                FolderChip(
                    label = folder.name,
                    icon = HugeIcons.Folder01,
                    selected = selectedFolderId == folder.id,
                    onClick = { onSelect(folder.id) },
                    onLongClick = { menuExpanded = true },
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_rename)) },
                        leadingIcon = { Icon(HugeIcons.PencilEdit01, null) },
                        onClick = {
                            onRename(folder)
                            menuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_delete)) },
                        leadingIcon = { Icon(HugeIcons.Delete01, null) },
                        onClick = {
                            onDelete(folder)
                            menuExpanded = false
                        }
                    )
                }
            }
        }
        item {
            FolderChip(
                label = stringResource(R.string.chat_page_folder_add),
                icon = HugeIcons.FolderAdd,
                selected = false,
                onClick = onCreate,
                onLongClick = {},
            )
        }
    }
}

@Composable
internal fun FolderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    icon: ImageVector? = null,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(14.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun AssistantItem(
    assistant: Assistant,
    isCurrentAssistant: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrentAssistant) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isCurrentAssistant) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UIAvatar(
                name = assistant.name,
                value = assistant.avatar,
                onUpdate = {},
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrentAssistant) {
                    Text(
                        text = stringResource(R.string.assistant_page_current_assistant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
