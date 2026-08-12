package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh03
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.transformers.DefaultPlaceholderProvider
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TextArea
import me.rerere.rikkahub.ui.theme.ChatFontProvider
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.insertAtCursor
import me.rerere.rikkahub.utils.onError
import me.rerere.rikkahub.utils.onSuccess
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

// [拆分] 助手正则卡片域（拆自 AssistantPromptPage.kt，Strangler Fig）

@Composable
internal fun AssistantRegexCard(
    regex: AssistantRegex,
    onUpdate: (Assistant) -> Unit,
    assistant: Assistant,
    index: Int
) {
    var expanded by remember {
        mutableStateOf(false)
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = regex.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 200.dp)
                )
                Switch(
                    checked = regex.enabled,
                    onCheckedChange = { enabled ->
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                    if (i == index) {
                                        reg.copy(enabled = enabled)
                                    } else {
                                        reg
                                    }
                                }
                            )
                        )
                    },
                    modifier = Modifier.padding(start = 8.dp)
                )
                IconButton(
                    onClick = {
                        expanded = !expanded
                    }
                ) {
                    Icon(
                        imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = null
                    )
                }
            }

            if (expanded) {

                OutlinedTextField(
                    value = regex.name,
                    onValueChange = { name ->
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                    if (i == index) {
                                        reg.copy(name = name)
                                    } else {
                                        reg
                                    }
                                }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_page_regex_name)) }
                )

                OutlinedTextField(
                    value = regex.findRegex,
                    onValueChange = { findRegex ->
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                    if (i == index) {
                                        reg.copy(findRegex = findRegex.trim())
                                    } else {
                                        reg
                                    }
                                }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_page_regex_find_regex)) },
                    placeholder = { Text("e.g., \\b\\w+@\\w+\\.\\w+\\b") },
                )

                OutlinedTextField(
                    value = regex.replaceString,
                    onValueChange = { replaceString ->
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                    if (i == index) {
                                        reg.copy(replaceString = replaceString)
                                    } else {
                                        reg
                                    }
                                }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_page_regex_replace_string)) },
                    placeholder = { Text("e.g., [EMAIL]") }
                )

                Column {
                    Text(
                        text = stringResource(R.string.assistant_page_regex_affecting_scopes),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AssistantAffectScope.entries.forEach { scope ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Checkbox(
                                    checked = scope in regex.affectingScope,
                                    onCheckedChange = { checked ->
                                        val newScopes = if (checked) {
                                            regex.affectingScope + scope
                                        } else {
                                            regex.affectingScope - scope
                                        }
                                        onUpdate(
                                            assistant.copy(
                                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                                    if (i == index) {
                                                        reg.copy(affectingScope = newScopes)
                                                    } else {
                                                        reg
                                                    }
                                                }
                                            )
                                        )
                                    }
                                )
                                Text(
                                    text = scope.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = regex.visualOnly,
                        onCheckedChange = { visualOnly ->
                            onUpdate(
                                assistant.copy(
                                    regexes = assistant.regexes.mapIndexed { i, reg ->
                                        if (i == index) {
                                            reg.copy(visualOnly = visualOnly)
                                        } else {
                                            reg
                                        }
                                    }
                                )
                            )
                        }
                    )
                    Text(
                        text = stringResource(R.string.assistant_page_regex_visual_only),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                TextButton(
                    onClick = {
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.filterIndexed { i, _ ->
                                    i != index
                                }
                            )
                        )
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(HugeIcons.Delete01, null)
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        }
    }
}
