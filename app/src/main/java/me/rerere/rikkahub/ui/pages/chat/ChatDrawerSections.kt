package me.rerere.rikkahub.ui.pages.chat

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
