package me.rerere.rikkahub.ui.pages.imggen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.ImageGenSize
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Colors
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

// [拆分] 图片画廊/设置域（拆自 ImgGenPage.kt，Strangler Fig）

@Composable
internal fun ImageGalleryScreen(
    vm: ImgGenVM,
) {
    val generatedImages = vm.generatedImages.collectAsLazyPagingItems()
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val pullToRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = { generatedImages.refresh() },
        state = pullToRefreshState
    ) {
        if (generatedImages.itemCount == 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = HugeIcons.Image03,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.imggen_page_no_generated_images),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    count = generatedImages.itemCount,
                    key = generatedImages.itemKey { it.id },
                    contentType = generatedImages.itemContentType { "GeneratedImage" }
                ) { index ->
                    val image = generatedImages[index]
                    image?.let {
                        var showPreview by remember { mutableStateOf(false) }

                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                AsyncImage(
                                    model = File(it.filePath),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clickable { showPreview = true },
                                    contentScale = ContentScale.Crop
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = it.model,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = it.prompt.take(20) + if (it.prompt.length > 20) "..." else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }

                                    Row {
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(it.prompt))
                                                toaster.show(
                                                    message = "Prompt copied to clipboard",
                                                    type = ToastType.Success
                                                )
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = HugeIcons.Copy01,
                                                contentDescription = "Copy prompt",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        filesManager.saveMessageImage(context, "file://${it.filePath}")
                                                        toaster.show(
                                                            message = context.getString(R.string.imggen_page_image_saved_success),
                                                            type = ToastType.Success
                                                        )
                                                    } catch (e: Exception) {
                                                        toaster.show(
                                                            message = context.getString(
                                                                R.string.imggen_page_save_failed,
                                                                e.message
                                                            ),
                                                            type = ToastType.Error
                                                        )
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = HugeIcons.FloppyDisk,
                                                contentDescription = stringResource(R.string.imggen_page_save),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { vm.deleteImage(it) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = HugeIcons.Delete01,
                                                contentDescription = stringResource(R.string.imggen_page_delete),
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (showPreview) {
                            ImagePreviewDialog(
                                images = listOf(it.filePath),
                                onDismissRequest = { showPreview = false }
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
internal fun SettingsBottomSheet(
    vm: ImgGenVM,
    numberOfImages: Int,
    size: String,
    scope: CoroutineScope,
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.imggen_page_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            FormItem(
                label = { Text(stringResource(R.string.imggen_page_generation_count)) },
                description = { Text(stringResource(R.string.imggen_page_generation_count_desc)) }
            ) {
                OutlinedNumberInput(
                    value = numberOfImages,
                    onValueChange = vm::updateNumberOfImages,
                    modifier = Modifier.width(120.dp)
                )
            }

            FormItem(
                label = { Text("Image Size") }
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ImageGenSize.entries.forEach { sizeOption ->
                        FilterChip(
                            selected = size == sizeOption.value,
                            onClick = { vm.updateSize(sizeOption.value) },
                            label = { Text(sizeOption.value) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = size,
                    onValueChange = vm::updateSize,
                    label = { Text("Custom size") },
                    placeholder = { Text("e.g. 1024x1024") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

