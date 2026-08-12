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

// [拆分] MarkdownNew 的 CSS 样式解析域（拆自 MarkdownNew.kt，Strangler Fig）

internal fun parseInlineSpanStyle(
    style: String,
    density: Density,
    baseFontSize: TextUnit,
): SpanStyle? {
    val properties = parseCssDeclarations(style)

    var hasStyle = false
    var spanStyle = SpanStyle()

    properties["color"]?.let { value ->
        parseColor(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(color = it))
            hasStyle = true
        }
    }

    properties["background-color"]?.let { value ->
        parseColor(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(background = it))
            hasStyle = true
        }
    }

    properties["font-weight"]?.let { value ->
        parseFontWeight(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(fontWeight = it))
            hasStyle = true
        }
    }

    properties["font-style"]?.let { value ->
        parseFontStyle(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(fontStyle = it))
            hasStyle = true
        }
    }

    properties["font-family"]?.let { value ->
        parseFontFamily(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(fontFamily = it))
            hasStyle = true
        }
    }

    properties["font-size"]?.let { value ->
        parseFontSize(
            fontSize = value,
            density = density,
            baseFontSize = baseFontSize,
        )?.let {
            spanStyle = spanStyle.merge(SpanStyle(fontSize = it))
            hasStyle = true
        }
    }

    properties["letter-spacing"]?.let { value ->
        parseSpacing(
            spacing = value,
            density = density,
            baseFontSize = baseFontSize,
        )?.let {
            spanStyle = spanStyle.merge(SpanStyle(letterSpacing = it))
            hasStyle = true
        }
    }

    properties["text-decoration"]?.let { value ->
        parseTextDecoration(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(textDecoration = it))
            hasStyle = true
        }
    }

    val backgroundValue = properties["background-color"] ?: properties["background"]
    backgroundValue?.let { value ->
        parseColor(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(background = it))
            hasStyle = true
        }
    }

    return spanStyle.takeIf { hasStyle }
}

private fun parseBlockTextStyle(
    style: String,
    density: Density,
    baseTextStyle: TextStyle,
): TextStyle? {
    val properties = parseCssDeclarations(style)

    val inlineStyle = parseInlineSpanStyle(
        style = style,
        density = density,
        baseFontSize = baseTextStyle.fontSize,
    )

    var hasStyle = inlineStyle != null
    var textStyle = TextStyle(
        color = inlineStyle?.color ?: Color.Unspecified,
        fontSize = inlineStyle?.fontSize ?: TextUnit.Unspecified,
        fontWeight = inlineStyle?.fontWeight,
        fontStyle = inlineStyle?.fontStyle,
        fontFamily = inlineStyle?.fontFamily,
        letterSpacing = inlineStyle?.letterSpacing ?: TextUnit.Unspecified,
        background = inlineStyle?.background ?: Color.Unspecified,
        textDecoration = inlineStyle?.textDecoration,
    )

    properties["line-height"]?.let { value ->
        parseLineHeight(
            lineHeight = value,
            density = density,
            baseFontSize = baseTextStyle.fontSize,
        )?.let {
            textStyle = textStyle.merge(TextStyle(lineHeight = it))
            hasStyle = true
        }
    }

    properties["text-align"]?.let { value ->
        parseTextAlign(value)?.let {
            textStyle = textStyle.merge(TextStyle(textAlign = it))
            hasStyle = true
        }
    }

    return textStyle.takeIf { hasStyle }
}

internal fun parseCssDeclarations(style: String): Map<String, String> {
    return style
        .split(";")
        .mapNotNull { property ->
            val parts = property.split(":", limit = 2)
            if (parts.size == 2) parts[0].trim().lowercase() to parts[1].trim() else null
        }
        .toMap()
}

private fun parseFontSize(
    fontSize: String,
    density: Density,
    baseFontSize: TextUnit,
): TextUnit? {
    val normalized = fontSize.trim().lowercase()
    if (normalized.isEmpty()) return null

    fun scaleBase(multiplier: Float): TextUnit? {
        if (!baseFontSize.isSpecified) return null
        return when (baseFontSize.type) {
            TextUnitType.Sp -> (baseFontSize.value * multiplier).sp
            TextUnitType.Em -> (baseFontSize.value * multiplier).em
            else -> null
        }
    }

    val absoluteKeywordScale = when (normalized) {
        "xx-small" -> 0.6f
        "x-small" -> 0.75f
        "small" -> 0.89f
        "medium" -> 1f
        "large" -> 1.2f
        "x-large" -> 1.5f
        "xx-large" -> 2f
        "smaller" -> 0.833f
        "larger" -> 1.2f
        else -> null
    }
    if (absoluteKeywordScale != null) {
        return scaleBase(absoluteKeywordScale)
    }

    return when {
        normalized.endsWith("sp") -> normalized.removeSuffix("sp").trim().toFloatOrNull()?.sp
        normalized.endsWith("px") -> normalized.removeSuffix("px").trim().toFloatOrNull()?.let {
            with(density) { it.toSp() }
        }

        normalized.endsWith("em") -> normalized.removeSuffix("em").trim().toFloatOrNull()?.em
        normalized.endsWith("rem") -> normalized.removeSuffix("rem").trim().toFloatOrNull()?.let {
            if (baseFontSize.isSpecified && baseFontSize.type == TextUnitType.Sp) {
                (baseFontSize.value * it).sp
            } else {
                16.sp * it
            }
        }

        normalized.endsWith("%") -> normalized.removeSuffix("%").trim().toFloatOrNull()?.let {
            scaleBase(it / 100f)
        }

        else -> normalized.toFloatOrNull()?.let {
            with(density) { it.toSp() }
        }
    }
}

private fun parseSpacing(
    spacing: String,
    density: Density,
    baseFontSize: TextUnit,
): TextUnit? {
    val normalized = spacing.trim().lowercase()
    if (normalized.isEmpty()) return null

    return when {
        normalized.endsWith("sp") -> normalized.removeSuffix("sp").trim().toFloatOrNull()?.sp
        normalized.endsWith("px") -> normalized.removeSuffix("px").trim().toFloatOrNull()?.let {
            with(density) { it.toSp() }
        }

        normalized.endsWith("em") -> normalized.removeSuffix("em").trim().toFloatOrNull()?.em
        normalized.endsWith("rem") -> normalized.removeSuffix("rem").trim().toFloatOrNull()?.let {
            if (baseFontSize.isSpecified && baseFontSize.type == TextUnitType.Sp) {
                (baseFontSize.value * it).sp
            } else {
                16.sp * it
            }
        }

        normalized.endsWith("%") -> normalized.removeSuffix("%").trim().toFloatOrNull()?.let {
            if (!baseFontSize.isSpecified) return@let null
            when (baseFontSize.type) {
                TextUnitType.Sp -> (baseFontSize.value * it / 100f).sp
                TextUnitType.Em -> (baseFontSize.value * it / 100f).em
                else -> null
            }
        }

        else -> normalized.toFloatOrNull()?.let {
            with(density) { it.toSp() }
        }
    }
}

private fun parseLineHeight(
    lineHeight: String,
    density: Density,
    baseFontSize: TextUnit,
): TextUnit? {
    val normalized = lineHeight.trim().lowercase()
    if (normalized.isEmpty()) return null

    if (normalized.matches(Regex("[0-9]*\\.?[0-9]+"))) {
        if (!baseFontSize.isSpecified) return null
        return when (baseFontSize.type) {
            TextUnitType.Sp -> (baseFontSize.value * normalized.toFloat()).sp
            TextUnitType.Em -> (baseFontSize.value * normalized.toFloat()).em
            else -> null
        }
    }

    return parseFontSize(
        fontSize = normalized,
        density = density,
        baseFontSize = baseFontSize,
    )
}

internal fun parseLegacyFontSize(
    fontSize: String,
    density: Density,
    baseFontSize: TextUnit,
): TextUnit? {
    val normalized = fontSize.trim()
    val legacyScale = when (normalized) {
        "1" -> 0.625f
        "2" -> 0.8125f
        "3" -> 1f
        "4" -> 1.125f
        "5" -> 1.5f
        "6" -> 2f
        "7" -> 3f
        else -> null
    }
    if (legacyScale != null) {
        return parseFontSize(
            fontSize = "${legacyScale * 100}%",
            density = density,
            baseFontSize = if (baseFontSize.isSpecified) baseFontSize else 16.sp,
        )
    }

    if ((normalized.startsWith("+") || normalized.startsWith("-")) && baseFontSize.isSpecified) {
        val delta = normalized.toIntOrNull() ?: return null
        val adjustedLevel = (3 + delta).coerceIn(1, 7)
        return parseLegacyFontSize(
            fontSize = adjustedLevel.toString(),
            density = density,
            baseFontSize = baseFontSize,
        )
    }

    return parseFontSize(
        fontSize = normalized,
        density = density,
        baseFontSize = baseFontSize,
    )
}

private fun parseFontFamily(fontFamily: String): FontFamily? {
    val normalized = fontFamily
        .split(",")
        .map { it.trim().trim('"', '\'').lowercase() }
        .firstOrNull()
        ?: return null

    return when {
        normalized.contains("mono") || normalized.contains("courier") -> FontFamily.Monospace
        normalized.contains("serif") || normalized.contains("georgia") || normalized.contains("times") -> FontFamily.Serif
        normalized.contains("sans") || normalized.contains("arial") || normalized.contains("helvetica") -> FontFamily.SansSerif
        normalized.contains("cursive") -> FontFamily.Cursive
        else -> null
    }
}

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

private fun parseFontWeight(weightString: String): FontWeight? {
    return when (weightString.lowercase()) {
        "normal" -> FontWeight.Normal
        "bold" -> FontWeight.Bold
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

private fun parseFontStyle(fontStyle: String): FontStyle? {
    return when (fontStyle.lowercase()) {
        "italic", "oblique" -> FontStyle.Italic
        "normal" -> FontStyle.Normal
        else -> null
    }
}

private fun parseTextDecoration(textDecoration: String): TextDecoration? {
    val parts = textDecoration.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return null

    val decorations = buildList {
        if ("underline" in parts) add(TextDecoration.Underline)
        if ("line-through" in parts) add(TextDecoration.LineThrough)
    }

    return when (decorations.size) {
        0 -> null
        1 -> decorations.first()
        else -> TextDecoration.combine(decorations)
    }
}

private fun parseTextAlign(textAlign: String): TextAlign? {
    return when (textAlign.trim().lowercase()) {
        "left", "start" -> TextAlign.Start
        "right", "end" -> TextAlign.End
        "center" -> TextAlign.Center
        "justify" -> TextAlign.Justify
        else -> null
    }
}

