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

// [拆分] HTML 文本构建/样式解析域（拆自 SimpleHtmlBlock.kt，Strangler Fig）

internal fun buildAnnotatedStringFromElement(
    element: Element,
    onLinkClick: (String) -> Unit
): AnnotatedString {
    return buildAnnotatedString {
        processElementNodes(element, this, onLinkClick)
    }
}

internal fun processElementNodes(
    element: Element,
    builder: AnnotatedString.Builder,
    onLinkClick: (String) -> Unit
) {
    element.childNodes().forEach { node ->
        when (node) {
            is TextNode -> {
                builder.append(node.text())
            }

            is Element -> {
                when (node.tagName().lowercase()) {
                    "b", "strong" -> {
                        val start = builder.length
                        processElementNodes(node, builder, onLinkClick)
                        builder.addStyle(
                            SpanStyle(fontWeight = FontWeight.Bold),
                            start,
                            builder.length
                        )
                    }

                    "i", "em" -> {
                        val start = builder.length
                        processElementNodes(node, builder, onLinkClick)
                        builder.addStyle(
                            SpanStyle(fontStyle = FontStyle.Italic),
                            start,
                            builder.length
                        )
                    }

                    "u" -> {
                        val start = builder.length
                        processElementNodes(node, builder, onLinkClick)
                        builder.addStyle(
                            SpanStyle(textDecoration = TextDecoration.Underline),
                            start,
                            builder.length
                        )
                    }

                    "a" -> {
                        val href = node.attr("href")
                        val start = builder.length
                        processElementNodes(node, builder, onLinkClick)
                        if (href.isNotEmpty()) {
                            builder.addStyle(
                                SpanStyle(
                                    color = Color.Blue,
                                    textDecoration = TextDecoration.Underline
                                ),
                                start,
                                builder.length
                            )
                            builder.addStringAnnotation(
                                tag = "URL",
                                annotation = href,
                                start = start,
                                end = builder.length
                            )
                        }
                    }

                    "code" -> {
                        val start = builder.length
                        processElementNodes(node, builder, onLinkClick)
                        builder.addStyle(
                            SpanStyle(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                background = Color.Gray.copy(alpha = 0.2f)
                            ),
                            start,
                            builder.length
                        )
                    }

                    "br" -> {
                        builder.append("\n")
                    }

                    "span" -> {
                        val start = builder.length
                        processElementNodes(node, builder, onLinkClick)

                        // Handle inline styles
                        val style = node.attr("style")
                        if (style.isNotEmpty()) {
                            val spanStyle = parseInlineStyle(style)
                            if (spanStyle != null) {
                                builder.addStyle(
                                    spanStyle,
                                    start,
                                    builder.length
                                )
                            }
                        }
                    }

                    "font" -> {
                        val start = builder.length
                        processElementNodes(node, builder, onLinkClick)

                        // Handle font color attribute
                        val color = node.attr("color")
                        if (color.isNotEmpty()) {
                            val parsedColor = parseHtmlColor(color)
                            if (parsedColor != null) {
                                builder.addStyle(
                                    SpanStyle(color = parsedColor),
                                    start,
                                    builder.length
                                )
                            }
                        }
                    }

                    else -> {
                        processElementNodes(node, builder, onLinkClick)
                    }
                }
            }
        }
    }
}

internal fun parseInlineStyle(style: String): SpanStyle? {
    val properties = style.split(";")
        .mapNotNull { property ->
            val parts = property.split(":")
            if (parts.size == 2) {
                parts[0].trim() to parts[1].trim()
            } else null
        }
        .toMap()

    var color: Color? = null
    var fontWeight: FontWeight? = null

    properties["color"]?.let { colorValue ->
        color = parseHtmlColor(colorValue)
    }

    properties["font-weight"]?.let { weightValue ->
        fontWeight = parseHtmlFontWeight(weightValue)
    }

    return if (color != null || fontWeight != null) {
        SpanStyle(
            color = color ?: Color.Unspecified,
            fontWeight = fontWeight
        )
    } else null
}

internal fun parseHtmlColor(colorString: String): Color? {
    return try {
        when {
            colorString.startsWith("#") -> {
                // Hex color
                val hex = colorString.removePrefix("#")
                when (hex.length) {
                    6 -> Color("#$hex".toColorInt())
                    3 -> {
                        // Convert 3-digit hex to 6-digit
                        val r = hex[0].toString().repeat(2)
                        val g = hex[1].toString().repeat(2)
                        val b = hex[2].toString().repeat(2)
                        Color("#$r$g$b".toColorInt())
                    }

                    else -> null
                }
            }

            colorString.startsWith("rgb(") -> {
                // RGB color
                val rgb = colorString.removePrefix("rgb(").removeSuffix(")")
                val values = rgb.split(",").map { it.trim().toIntOrNull() }
                if (values.size == 3 && values.all { it != null && it in 0..255 }) {
                    Color(values[0]!!, values[1]!!, values[2]!!)
                } else null
            }

            colorString.startsWith("rgba(") -> {
                // RGBA color
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
                // Named colors
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
    } catch (e: Exception) {
        null
    }
}

internal fun parseHtmlFontWeight(weightString: String): FontWeight? {
    return when (weightString.lowercase()) {
        "normal" -> FontWeight.Normal
        "bold" -> FontWeight.SemiBold
        "bolder" -> FontWeight.ExtraBold
        "lighter" -> FontWeight.Light
        "100" -> FontWeight.W100
        "200" -> FontWeight.W200
        "300" -> FontWeight.W300
        "400" -> FontWeight.W400
        "500" -> FontWeight.W500
        "600" -> FontWeight.W600
        "700" -> FontWeight.W700
        "800" -> FontWeight.W800
        "900" -> FontWeight.W900
        else -> null
    }
}
