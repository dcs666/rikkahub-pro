package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import me.rerere.rikkahub.ui.components.table.DataTable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// [拆分] HTML 进度条/表格渲染域（拆自 SimpleHtmlBlock.kt，Strangler Fig）

@Composable
internal fun RenderProgress(
    progressElement: Element
) {
    val value = progressElement.attr("value").toFloatOrNull() ?: 0f
    val max = progressElement.attr("max").toFloatOrNull() ?: 100f
    val progress = if (max > 0) (value / max).coerceIn(0f, 1f) else 0f

    // Check for width in style attribute first, then width attribute
    val style = progressElement.attr("style")
    var width = ""
    if (style.isNotEmpty()) {
        val properties = style.split(";")
            .mapNotNull { property ->
                val parts = property.split(":")
                if (parts.size == 2) {
                    parts[0].trim() to parts[1].trim()
                } else null
            }
            .toMap()
        width = properties["width"] ?: ""
    }
    if (width.isEmpty()) {
        width = progressElement.attr("width")
    }

    val widthModifier = if (width.isNotEmpty()) {
        when {
            width.endsWith("%") -> {
                val percentage = width.removeSuffix("%").toFloatOrNull()
                if (percentage != null && percentage > 0) {
                    Modifier.fillMaxWidth(percentage / 100f)
                } else {
                    Modifier.fillMaxWidth()
                }
            }

            width.endsWith("px") -> {
                val pixels = width.removeSuffix("px").toIntOrNull()
                if (pixels != null && pixels > 0) {
                    Modifier.width(pixels.dp)
                } else {
                    Modifier.fillMaxWidth()
                }
            }

            else -> {
                val pixels = width.toIntOrNull()
                if (pixels != null && pixels > 0) {
                    Modifier.width(pixels.dp)
                } else {
                    Modifier.fillMaxWidth()
                }
            }
        }
    } else {
        Modifier.fillMaxWidth()
    }

    LinearProgressIndicator(
        progress = { progress },
        modifier = widthModifier,
    )
}
@Composable
internal fun RenderTable(
    tableElement: Element,
    onLinkClick: (String) -> Unit
) {
    val rows = mutableListOf<List<@Composable () -> Unit>>()
    var headers = emptyList<@Composable () -> Unit>()

    // Extract table headers and rows
    tableElement.select("tr").forEach { tr ->
        val cells = mutableListOf<@Composable () -> Unit>()

        tr.select("th, td").forEach { cell ->
            cells.add {
                val annotatedString = buildAnnotatedStringFromElement(cell, onLinkClick)
                if (annotatedString.text.isNotBlank()) {
                    Text(
                        text = annotatedString,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = LocalContentColor.current
                        )
                    )
                }
            }
        }

        if (cells.isNotEmpty()) {
            // Check if this row contains header cells (th)
            val isHeaderRow = tr.select("th").isNotEmpty()
            if (isHeaderRow && headers.isEmpty()) {
                headers = cells
            } else {
                rows.add(cells)
            }
        }
    }

    // If no headers found, create empty headers for consistency
    if (headers.isEmpty() && rows.isNotEmpty()) {
        headers = rows.firstOrNull()?.mapIndexed { _, _ ->
            @Composable { Text("") }
        } ?: emptyList()
    }

    if (headers.isNotEmpty() || rows.isNotEmpty()) {
        Box(modifier = Modifier.padding(vertical = 8.dp)) {
            DataTable(
                headers = headers,
                rows = rows,
                cellBorder = null,
                headerBackground = Color.Transparent,
                zebraStriping = false
            )
        }
    }
}
