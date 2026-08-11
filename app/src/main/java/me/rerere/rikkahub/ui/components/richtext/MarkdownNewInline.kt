package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.ui.components.table.DataTable
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.toDp
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// ==================== [F5] MarkdownNew 行内渲染（自 MarkdownNew.kt 拆出） ====================

@Composable
internal fun HtmlInlineGroup(nodes: List<Node>, onClickCitation: (String) -> Unit) {
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    val colorScheme = MaterialTheme.colorScheme
    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current

    val key = remember(nodes) { nodes.joinToString("") { if (it is Element) it.outerHtml() else it.toString() } }
    val (annotatedString, inlineContents) = remember(
        key,
        enableLatexRendering,
        colorScheme,
        density,
        textStyle,
        onClickCitation,
    ) {
        val contents = mutableMapOf<String, InlineTextContent>()
        val text = buildAnnotatedString {
            nodes.fastForEach { node ->
                appendHtmlInlineNode(
                    node = node,
                    colorScheme = colorScheme,
                    inlineContents = contents,
                    density = density,
                    style = textStyle,
                    enableLatexRendering = enableLatexRendering,
                    onClickCitation = onClickCitation,
                )
            }
        }
        text to contents
    }

    if (annotatedString.isNotEmpty()) {
        Text(text = annotatedString, inlineContent = inlineContents)
    }
}

// ---- Inline-as-Composable rendering (for FlowRow mixed content) ----

/**
 * Renders an individual Jsoup node as a standalone Composable.
 * Used inside FlowRow for paragraphs that mix images, math, and text.
 */
@Composable
internal fun HtmlInlineAsComposable(node: Node, onClickCitation: (String) -> Unit) {
    when (node) {
        is TextNode -> {
            val text = node.text()
            if (text.isNotEmpty()) Text(text = text)
        }

        is Element -> {
            val tag = node.tagName().lowercase()
            when {
                tag == "img" -> {
                    val src = node.attr("src")
                    val alt = node.attr("alt")
                    if (src.isNotEmpty()) {
                        ZoomableAsyncImage(
                            model = src,
                            contentDescription = alt.takeIf { it.isNotEmpty() },
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .widthIn(min = 120.dp)
                                .heightIn(min = 120.dp),
                        )
                    }
                }

                tag == "span" && node.hasClass("math") && node.attr("inline") != "true" -> {
                    HtmlMathBlock(formula = node.text())
                }

                tag == "br" -> {
                    // handled by inline text
                }

                else -> {
                    // Render as an inline text segment
                    val colorScheme = MaterialTheme.colorScheme
                    val textStyle = LocalTextStyle.current
                    val density = LocalDensity.current
                    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
                    val (annotated, inlineContents) = remember(
                        node.outerHtml(),
                        enableLatexRendering,
                        colorScheme,
                        density,
                        textStyle,
                        onClickCitation,
                    ) {
                        val contents = mutableMapOf<String, InlineTextContent>()
                        val text = buildAnnotatedString {
                            appendHtmlInlineElement(
                                element = node,
                                colorScheme = colorScheme,
                                inlineContents = contents,
                                density = density,
                                style = textStyle,
                                enableLatexRendering = enableLatexRendering,
                                onClickCitation = onClickCitation,
                            )
                        }
                        text to contents
                    }
                    Text(text = annotated, inlineContent = inlineContents)
                }
            }
        }
    }
}

// ---- Inline AnnotatedString building ----

internal fun AnnotatedString.Builder.appendHtmlInlineNode(
    node: Node,
    colorScheme: androidx.compose.material3.ColorScheme,
    inlineContents: MutableMap<String, InlineTextContent>,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean,
    onClickCitation: (String) -> Unit,
) {
    when (node) {
        is TextNode -> append(node.text())
        is Element -> appendHtmlInlineElement(
            element = node,
            colorScheme = colorScheme,
            inlineContents = inlineContents,
            density = density,
            style = style,
            enableLatexRendering = enableLatexRendering,
            onClickCitation = onClickCitation,
        )
    }
}

internal fun AnnotatedString.Builder.appendHtmlInlineElement(
    element: Element,
    colorScheme: androidx.compose.material3.ColorScheme,
    inlineContents: MutableMap<String, InlineTextContent>,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean,
    onClickCitation: (String) -> Unit,
) {
    val cssStyle = element.attr("style").takeIf { it.isNotBlank() }?.let {
        parseInlineSpanStyle(
            style = it,
            density = density,
            baseFontSize = style.fontSize,
        )
    }

    fun recurseChildren(el: Element, inheritedStyle: TextStyle = style) = el.childNodes().fastForEach {
        appendHtmlInlineNode(
            node = it,
            colorScheme = colorScheme,
            inlineContents = inlineContents,
            density = density,
            style = inheritedStyle,
            enableLatexRendering = enableLatexRendering,
            onClickCitation = onClickCitation,
        )
    }

    fun appendStyledChildren(spanStyle: SpanStyle) = withStyle(spanStyle) {
        recurseChildren(element, style.merge(spanStyle.asTextStyle()))
    }

    fun appendElementChildren(tagStyle: SpanStyle = SpanStyle()) {
        val elementStyle = tagStyle.merge(cssStyle ?: SpanStyle())
        if (elementStyle == SpanStyle()) {
            recurseChildren(element)
        } else {
            appendStyledChildren(elementStyle)
        }
    }

    when (element.tagName().lowercase()) {
        "b", "strong" -> appendElementChildren(SpanStyle(fontWeight = FontWeight.Bold))

        "i", "em" -> appendElementChildren(SpanStyle(fontStyle = FontStyle.Italic))

        "del", "s", "strike" -> appendElementChildren(SpanStyle(textDecoration = TextDecoration.LineThrough))

        "u" -> appendElementChildren(SpanStyle(textDecoration = TextDecoration.Underline))

        "code" -> withStyle(
            SpanStyle(
                fontFamily = JetbrainsMono,
                fontSize = 0.9.em,
                color = colorScheme.primary,
            ).merge(cssStyle ?: SpanStyle())
        ) {
            append(' ')
            append(element.text())
            append(' ')
        }

        "a" -> {
            val href = element.attr("href")
            val text = element.text()
            when {
                text.startsWith("citation,") -> {
                    // Citation link: [citation,domain](id)
                    val domain = text.substringAfter("citation,")
                    val id = href
                    if (id.length == 6) {
                        inlineContents.putIfAbsent(
                            "citation:$id",
                            InlineTextContent(
                                placeholder = Placeholder(
                                    width = (domain.length * 7).sp,
                                    height = 1.em,
                                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                                ),
                                children = {
                                    Box(
                                        modifier = Modifier
                                            .clickable { onClickCitation(id.trim()) }
                                            .fillMaxSize()
                                            .clip(CircleShape)
                                            .background(colorScheme.tertiaryContainer.copy(0.2f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = domain,
                                            modifier = Modifier.wrapContentSize(),
                                            style = TextStyle(
                                                fontSize = 10.sp,
                                                lineHeight = 10.sp,
                                                fontFamily = JetbrainsMono,
                                                color = colorScheme.onTertiaryContainer,
                                                fontWeight = FontWeight.Thin,
                                            ),
                                        )
                                    }
                                },
                            ),
                        )
                        appendInlineContent("citation:$id")
                    }
                }

                href.isNotEmpty() -> {
                    val linkStyle = SpanStyle(
                        color = colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ).merge(cssStyle ?: SpanStyle())
                    withLink(LinkAnnotation.Url(href)) {
                        withStyle(linkStyle) {
                            recurseChildren(element, style.merge(linkStyle.asTextStyle()))
                        }
                    }
                }

                else -> appendElementChildren()
            }
        }

        "span" -> {
            if (element.hasClass("math") && element.attr("inline") == "true") {
                val formula = element.text()
                if (enableLatexRendering) {
                    appendInlineContent(formula, "[Latex]")
                    val (width, height) = with(density) {
                        assumeLatexSize(latex = formula, fontSize = style.fontSize.toPx()).let {
                            it.width().toSp() to it.height().toSp()
                        }
                    }
                    inlineContents.putIfAbsent(
                        formula,
                        InlineTextContent(
                            placeholder = Placeholder(
                                width = width,
                                height = height,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ),
                            children = {
                                MathInline(latex = formula, modifier = Modifier, fontSize = style.fontSize)
                            },
                        ),
                    )
                } else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.95.em)) {
                        append(formula)
                    }
                }
            } else {
                appendElementChildren()
            }
        }

        "font" -> {
            val inlineStyle = buildFontTagStyle(
                element = element,
                density = density,
                baseFontSize = style.fontSize,
            )
            if (inlineStyle != null) {
                appendStyledChildren(inlineStyle)
            } else {
                appendElementChildren()
            }
        }

        "br" -> append("\n")

        else -> appendElementChildren()
    }
}

internal fun SpanStyle.asTextStyle(): TextStyle {
    return TextStyle(
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        background = background,
        textDecoration = textDecoration,
    )
}

internal fun buildFontTagStyle(
    element: Element,
    density: Density,
    baseFontSize: TextUnit,
): SpanStyle? {
    val color = element.attr("color").takeIf { it.isNotBlank() }?.let(::parseColor)
    val styleAttr = element.attr("style").takeIf { it.isNotBlank() }?.let {
        parseInlineSpanStyle(
            style = it,
            density = density,
            baseFontSize = baseFontSize,
        )
    }
    val sizeAttr = element.attr("size").takeIf { it.isNotBlank() }?.let {
        parseLegacyFontSize(
            fontSize = it,
            density = density,
            baseFontSize = baseFontSize,
        )
    }

    var resolvedStyle = styleAttr ?: SpanStyle()
    color?.let { resolvedStyle = resolvedStyle.merge(SpanStyle(color = it)) }
    sizeAttr?.let { resolvedStyle = resolvedStyle.merge(SpanStyle(fontSize = it)) }

    return resolvedStyle.takeIf {
        color != null || styleAttr != null || sizeAttr != null
    }
}

// [F5] parseColor：从 MarkdownNew.kt 复制（同包 SimpleHtmlBlock 有同名 private 函数，}internal 会冲突——本文件私有副本，互不影响）
private fun parseColor(colorString: String): Color? {
    return try {
        when {
            colorString.startsWith("#") -> {
                val hex = colorString.removePrefix("#")
                when (hex.length) {
                    6 -> Color("#$hex".toColorInt())
                    3 -> {
                        val r = hex[0].toString().repeat(2)
                        val g = hex[1].toString().repeat(2)
                        val b = hex[2].toString().repeat(2)
                        Color("#$r$g$b".toColorInt())
                    }

                    else -> null
                }
            }

            colorString.startsWith("rgb(") -> {
                val rgb = colorString.removePrefix("rgb(").removeSuffix(")")
                val values = rgb.split(",").map { it.trim().toIntOrNull() }
                if (values.size == 3 && values.all { it != null && it in 0..255 }) {
                    Color(values[0]!!, values[1]!!, values[2]!!)
                } else null
            }

            colorString.startsWith("rgba(") -> {
                val rgba = colorString.removePrefix("rgba(").removeSuffix(")")
                val values = rgba.split(",").map { it.trim() }
                if (values.size == 4) {
                    val r = values[0].toIntOrNull()
                    val g = values[1].toIntOrNull()
                    val b = values[2].toIntOrNull()
                    val a = values[3].toFloatOrNull()
                    if (r != null && g != null && b != null && a != null &&
                        r in 0..255 && g in 0..255 && b in 0..255 && a in 0f..1f
                    ) {
                        Color(r, g, b, (a * 255).toInt())
                    } else null
                } else null
            }

            else -> {
                when (colorString.lowercase()) {
                    "red" -> Color.Red
                    "green" -> Color.Green
                    "blue" -> Color.Blue
                    "black" -> Color.Black
                    "white" -> Color.White
                    "gray", "grey" -> Color.Gray
                    "yellow" -> Color.Yellow
                    "cyan" -> Color.Cyan
                    "magenta" -> Color.Magenta
                    "orange" -> Color(0xFFFFA500)
                    "purple" -> Color(0xFF800080)
                    "brown" -> Color(0xFFA52A2A)
                    "pink" -> Color(0xFFFFC0CB)
                    else -> null
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}
