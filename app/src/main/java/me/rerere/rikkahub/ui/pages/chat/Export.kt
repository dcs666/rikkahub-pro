package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Book04
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Wrench01
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.Navigator
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.ai.util.encodeBase64
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.BitmapComposer
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import com.dokar.sonner.rememberToasterState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.getActivity
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.toLocalString
import java.io.FileOutputStream
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

@Composable
fun ChatExportSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    conversation: Conversation,
    selectedMessages: List<UIMessage>
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val settings = LocalSettings.current
    var imageExportOptions by remember { mutableStateOf(ImageExportOptions()) }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = stringResource(id = R.string.chat_page_export_format))

                val markdownSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "Markdown")
                OutlinedCard(
                    onClick = {
                        exportToMarkdown(context, conversation, selectedMessages)
                        toaster.show(
                            markdownSuccessMessage,
                            type = ToastType.Success
                        )
                        onDismissRequest()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(id = R.string.chat_page_export_markdown))
                        },
                        supportingContent = {
                            Text(stringResource(id = R.string.chat_page_export_markdown_desc))
                        },
                        leadingContent = {
                            Icon(HugeIcons.File02, contentDescription = null)
                        }
                    )
                }

                val imageSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "Image")
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(id = R.string.chat_page_export_image))
                            },
                            supportingContent = {
                                Text(stringResource(id = R.string.chat_page_export_image_desc))
                            },
                            leadingContent = {
                                Icon(HugeIcons.Image02, contentDescription = null)
                            }
                        )

                        HorizontalDivider()

                        ListItem(
                            headlineContent = { Text(stringResource(R.string.chat_page_export_image_expand_reasoning)) },
                            trailingContent = {
                                Switch(
                                    checked = imageExportOptions.expandReasoning,
                                    onCheckedChange = {
                                        imageExportOptions = imageExportOptions.copy(expandReasoning = it)
                                    }
                                )
                            }
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            exportToImage(
                                                context = context,
                                                scope = scope,
                                                density = density,
                                                conversation = conversation,
                                                messages = selectedMessages,
                                                settings = settings,
                                                options = imageExportOptions
                                            )
                                        }.onFailure {
                                            it.printStackTrace()
                                            toaster.show(
                                                message = "Failed to export image: ${it.message}",
                                                type = ToastType.Error
                                            )
                                        }
                                    }
                                    toaster.show(
                                        imageSuccessMessage,
                                        type = ToastType.Success
                                    )
                                    onDismissRequest()
                                }
                            ) {
                                Text(stringResource(R.string.mermaid_export))
                            }
                        }
                    }
                }
            }
        }
    }
}
