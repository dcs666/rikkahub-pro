package me.rerere.rikkahub.ui.components.richtext

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.table.DataTable
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.toDp
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.time.Clock

/**
 * [拆分] Markdown 内容构建域：Paragraph 渲染 + appendMarkdownNodeContent 递归文本构建
 * + getTextInNode。纯参数驱动（无状态），从 Markdown.kt 提取。
 */

internal fun Paragraph(
node: ASTNode,
content: String,
trim: Boolean = false,
onClickCitation: (String) -> Unit = {},
modifier: Modifier,
) {
// dumpAst(node, content)
if (node.findChildOfTypeRecursive(MarkdownElementTypes.IMAGE, GFMElementTypes.BLOCK_MATH) != null) {
    FlowRow(modifier = modifier) {
        node.children.fastForEach { child ->
            MarkdownNode(
                node = child, content = content, onClickCitation = onClickCitation
            )
        }
    }
    return
}

val colorScheme = MaterialTheme.colorScheme
val inlineContents = remember {
    mutableStateMapOf<String, InlineTextContent>()
}
val hasInlineMath = remember(node) {
    node.findChildOfTypeRecursive(GFMElementTypes.INLINE_MATH) != null
}
val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering

val textStyle = LocalTextStyle.current
val density = LocalDensity.current
val latexColorArgb = LocalContentColor.current.toArgb()
FlowRow(
    modifier = modifier.then(
        if (node.nextSibling() != null) Modifier.padding(bottom = LocalTextStyle.current.fontSize.toDp())
        else Modifier
    )
) {
    val annotatedString = remember(content, enableLatexRendering, latexColorArgb) {
        buildAnnotatedString {
            node.children.fastForEach { child ->
                appendMarkdownNodeContent(
                    node = child,
                    content = content,
                    inlineContents = inlineContents,
                    colorScheme = colorScheme,
                    onClickCitation = onClickCitation,
                    style = textStyle,
                    density = density,
                    trim = trim,
                    enableLatexRendering = enableLatexRendering,
                    latexColorArgb = latexColorArgb,
                )
            }
        }
    }
    Text(
        text = annotatedString,
        modifier = Modifier,
        inlineContent = inlineContents,
        softWrap = true,
        overflow = TextOverflow.Visible,
        style = LocalTextStyle.current.copy(
            lineHeight = if (hasInlineMath && enableLatexRendering) TextUnit.Unspecified else LocalTextStyle.current.lineHeight
        )
    )
}
}

private fun AnnotatedString.Builder.appendMarkdownNodeContent(
node: ASTNode,
content: String,
trim: Boolean = false,
inlineContents: MutableMap<String, InlineTextContent>,
colorScheme: ColorScheme,
density: Density,
style: TextStyle,
enableLatexRendering: Boolean = true,
latexColorArgb: Int = 0,
onClickCitation: (String) -> Unit = {},
) {
when {
    node.type == MarkdownTokenTypes.BLOCK_QUOTE -> {}

    node.type == GFMTokenTypes.GFM_AUTOLINK -> {
        val link = node.getTextInNode(content)
        withLink(LinkAnnotation.Url(link)) {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(link)
            }
        }
    }

    node is LeafASTNode -> {
        val text = node.getTextInNode(content).let {
            if (trim) {
                it.trim()
            } else {
                it
            }.replace(BREAK_LINE_REGEX, "\n")
        }
        append(
            text = text,
        )
    }

    node.type == MarkdownElementTypes.EMPH -> {
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            node.children.trim(MarkdownTokenTypes.EMPH, 1).fastForEach {
                appendMarkdownNodeContent(
                    node = it,
                    content = content,
                    inlineContents = inlineContents,
                    colorScheme = colorScheme,
                    density = density,
                    style = style,
                    enableLatexRendering = enableLatexRendering,
                    latexColorArgb = latexColorArgb,
                    onClickCitation = onClickCitation
                )
            }
        }
    }

    node.type == MarkdownElementTypes.STRONG -> {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            node.children.trim(MarkdownTokenTypes.EMPH, 2).fastForEach {
                appendMarkdownNodeContent(
                    node = it,
                    content = content,
                    inlineContents = inlineContents,
                    colorScheme = colorScheme,
                    density = density,
                    style = style,
                    enableLatexRendering = enableLatexRendering,
                    latexColorArgb = latexColorArgb,
                    onClickCitation = onClickCitation
                )
            }
        }
    }

    node.type == GFMElementTypes.STRIKETHROUGH -> {
        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            node.children.trim(GFMTokenTypes.TILDE, 2).fastForEach {
                appendMarkdownNodeContent(
                    node = it,
                    content = content,
                    inlineContents = inlineContents,
                    colorScheme = colorScheme,
                    density = density,
                    style = style,
                    enableLatexRendering = enableLatexRendering,
                    latexColorArgb = latexColorArgb,
                    onClickCitation = onClickCitation
                )
            }
        }
    }

    node.type == MarkdownElementTypes.INLINE_LINK -> {
        val linkDest =
            node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
        val linkText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content)
            ?.trim { it == '[' || it == ']' } ?: linkDest
        if (linkText.startsWith("citation,")) {
            // 如果是引用，则特殊处理
            val domain = linkText.substringAfter("citation,")
            val id = linkDest
            if (id.length == 6) {
                inlineContents.putIfAbsent(
                    "citation:$linkDest", InlineTextContent(
                        placeholder = Placeholder(
                            width = (domain.length * 7).sp,
                            height = 1.em,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        ), children = {
                            Box(
                                modifier = Modifier
                                    .clickable {
                                        onClickCitation(id.trim())
                                    }
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(colorScheme.tertiaryContainer.copy(0.2f)),
                                contentAlignment = Alignment.Center) {
                                Text(
                                    text = domain,
                                    modifier = Modifier.wrapContentSize(),
                                    style = TextStyle(
                                        fontSize = 10.sp,
                                        lineHeight = 10.sp,
                                        fontFamily = JetbrainsMono,
                                        color = colorScheme.onTertiaryContainer,
                                        fontWeight = FontWeight.Thin
                                    ),
                                )
                            }
                        })
                )
                appendInlineContent("citation:$linkDest")
            }
        } else {
            withLink(LinkAnnotation.Url(linkDest)) {
                withStyle(
                    SpanStyle(
                        color = colorScheme.primary, textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(linkText)
                }
            }
        }
    }

    node.type == MarkdownElementTypes.AUTOLINK -> {
        val links = node.children.trim(MarkdownTokenTypes.LT, 1).trim(MarkdownTokenTypes.GT, 1)
        links.fastForEach { link ->
            withLink(LinkAnnotation.Url(link.getTextInNode(content))) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(link.getTextInNode(content))
                }
            }
        }
    }

    node.type == MarkdownElementTypes.CODE_SPAN -> {
        val code = node.getTextInNode(content).trim('`')
        withStyle(
            SpanStyle(
                fontFamily = JetbrainsMono,
                fontSize = 0.9.em,
                color = colorScheme.primary,
            )
        ) {
            append(' ')
            append(code)
            append(' ')
        }
    }

    node.type == GFMElementTypes.INLINE_MATH -> {
        val formula = node.getTextInNode(content)
        if (enableLatexRendering) {
            val fontSizePx = with(density) { style.fontSize.toPx() }
            // 将过长的行内公式按顶层运算符水平拆分为多段，每段最大宽度限制为字号的两倍，
            // 使其能在文本流中换行，避免单体公式超出可用宽度被挤出屏幕
            val drawables = splitLatex(
                latex = formula,
                maxWidthPx = fontSizePx * 2,
                fontSize = fontSizePx,
                color = latexColorArgb,
            )
            if (drawables.isEmpty()) {
                // 拆分失败时回退为单体内联渲染
                appendInlineContent(formula, "[Latex]")
                val (width, height) = with(density) {
                    assumeLatexSize(
                        latex = formula, fontSize = fontSizePx
                    ).let {
                        it.width().toSp() to it.height().toSp()
                    }
                }
                inlineContents.putIfAbsent(/* key = */ formula,/* value = */ InlineTextContent(
                    placeholder = Placeholder(
                        width = width,
                        height = height,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                    ), children = {
                        MathInline(
                            latex = formula, modifier = Modifier
                        )
                    })
                )
            } else {
                drawables.forEachIndexed { index, drawable ->
                    // 段间插入零宽空格，提供换行点
                    if (index > 0) append('\u200B')
                    val key = "latex:${formula.hashCode()}:$index"
                    appendInlineContent(key, "[Latex]")
                    val (width, height) = with(density) {
                        drawable.bounds.width().toSp() to drawable.bounds.height().toSp()
                    }
                    inlineContents.putIfAbsent(
                        key, InlineTextContent(
                            placeholder = Placeholder(
                                width = width,
                                height = height,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                            ), children = {
                                LatexDrawable(drawable = drawable)
                            })
                    )
                }
            }
        } else {
            // 禁用 LaTeX 渲染时，以等宽字体显示原始公式
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 0.95.em,
                )
            ) {
                append(formula)
            }
        }
    }

    // 其他类型继续递归处理
    else -> {
        node.children.fastForEach {
            appendMarkdownNodeContent(
                node = it,
                content = content,
                inlineContents = inlineContents,
                colorScheme = colorScheme,
                density = density,
                style = style,
                enableLatexRendering = enableLatexRendering,
                latexColorArgb = latexColorArgb,
                onClickCitation = onClickCitation
            )
        }
    }
}
}

internal fun ASTNode.getTextInNode(text: String): String {
return text.substring(startOffset, endOffset)
}

internal fun ASTNode.getTextInNode(text: String, type: IElementType): String {
var startOffset = -1
var endOffset = -1
children.fastForEach {
    if (it.type == type) {
        if (startOffset == -1) {
            startOffset = it.startOffset
        }
        endOffset = it.endOffset
    }
}
if (startOffset == -1 || endOffset == -1) {
    return ""
}
return text.substring(startOffset, endOffset)
}
