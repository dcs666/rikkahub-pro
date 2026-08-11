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

// ==================== [F5] MarkdownNew 块级渲染（自 MarkdownNew.kt 拆出） ====================

@Composable
internal fun HtmlCodeBlock(element: Element) {
    val codeElement = element.selectFirst("code")
    val language = codeElement?.classNames()
        ?.find { it.startsWith("language-") }
        ?.removePrefix("language-")
        ?: "plaintext"
    val code = codeElement?.wholeText()?.trimEnd('\n') ?: element.wholeText().trimEnd('\n')

    HighlightCodeBlock(
        code = code,
        language = language,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        completeCodeBlock = true,
    )
}

@Composable
internal fun HtmlBlockquote(element: Element, onClickCitation: (String) -> Unit) {
    ProvideTextStyle(LocalTextStyle.current.copy(fontStyle = FontStyle.Italic)) {
        val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        Column(
            modifier = Modifier
                .drawWithContent {
                    drawContent()
                    drawRect(color = bgColor, size = size)
                    drawRect(color = borderColor, size = Size(10f, size.height))
                }
                .padding(8.dp),
        ) {
            element.childNodes().fastForEach { HtmlBodyNode(it, onClickCitation) }
        }
    }
}

@Composable
internal fun HtmlMathBlock(formula: String) {
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    if (enableLatexRendering) {
        MathBlock(
            latex = formula,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
    } else {
        Text(
            text = formula,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
internal fun HtmlTable(element: Element, onClickCitation: (String) -> Unit) {
    val headerElements = element.select("thead tr th")
    val columnCount = headerElements.size.takeIf { it > 0 }
        ?: element.select("tbody tr:first-child td").size
    if (columnCount == 0) return

    val headers = List(columnCount) { col ->
        @Composable {
            if (col < headerElements.size) {
                HtmlStyledElement(element = headerElements[col]) {
                    HtmlInlineGroup(
                        nodes = headerElements[col].childNodes(),
                        onClickCitation = onClickCitation,
                    )
                }
            }
        }
    }

    val bodyRows = element.select("tbody tr")
    val rows = bodyRows.map { tr ->
        val cellElements = tr.select("td")
        List(columnCount) { col ->
            @Composable {
                if (col < cellElements.size) {
                    HtmlStyledElement(element = cellElements[col]) {
                        HtmlInlineGroup(
                            nodes = cellElements[col].childNodes(),
                            onClickCitation = onClickCitation,
                        )
                    }
                }
            }
        }
    }

    DataTable(
        headers = headers,
        rows = rows,
        modifier = Modifier.padding(vertical = 8.dp),
        columnMinWidths = List(columnCount) { 80.dp },
        columnMaxWidths = List(columnCount) { 200.dp },
    )
}

@Composable
internal fun HtmlDetails(element: Element, onClickCitation: (String) -> Unit) {
    // Delegate to the existing SimpleHtmlBlock details renderer via a mini-document
    val summaryElement = element.children().find { it.tagName().lowercase() == "summary" }
    val summaryText = summaryElement?.text() ?: "Details"

    var expanded by remember { mutableStateOf(element.hasAttr("open")) }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = if (expanded) "▼ " else "▶ ")
            Text(text = summaryText, fontWeight = FontWeight.Medium)
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 16.dp)) {
                element.childNodes().fastForEach { child ->
                    if (!(child is Element && child.tagName().lowercase() == "summary")) {
                        HtmlBodyNode(child, onClickCitation)
                    }
                }
            }
        }
    }
}

@Composable
internal fun HtmlProgress(element: Element) {
    val value = element.attr("value").toFloatOrNull() ?: 0f
    val max = element.attr("max").toFloatOrNull()?.takeIf { it > 0 } ?: 100f
    val progress = (value / max).coerceIn(0f, 1f)

    val style = element.attr("style")
    val widthValue = parseCssDeclarations(style)["width"] ?: element.attr("width")

    val widthModifier = when {
        widthValue.endsWith("%") -> widthValue.removeSuffix("%").toFloatOrNull()
            ?.let { Modifier.fillMaxWidth(it / 100f) } ?: Modifier.fillMaxWidth()
        widthValue.endsWith("px") -> widthValue.removeSuffix("px").toIntOrNull()
            ?.let { Modifier.width(it.dp) } ?: Modifier.fillMaxWidth()
        widthValue.isNotEmpty() -> widthValue.toIntOrNull()
            ?.let { Modifier.width(it.dp) } ?: Modifier.fillMaxWidth()
        else -> Modifier.fillMaxWidth()
    }

    androidx.compose.material3.LinearProgressIndicator(
        progress = { progress },
        modifier = widthModifier.padding(vertical = 4.dp),
    )
}

// ---- Inline group rendering (for list items with mixed inline nodes) ----

/**
 * Renders a list of inline Jsoup nodes as a single Text composable with AnnotatedString.
 * This prevents inline siblings (e.g. <strong>A</strong>和<strong>B</strong>) from being
 * rendered on separate lines.
 */
