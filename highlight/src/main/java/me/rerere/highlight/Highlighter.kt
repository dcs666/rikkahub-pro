package me.rerere.highlight

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import me.rerere.highlight.core.HighlightEngine
import me.rerere.highlight.languages.builtinLanguages

private const val MAX_CODE_LENGTH = 4096

private const val TAG = "CodeHighlighter"

val LocalCodeHighlighter = staticCompositionLocalOf { CodeHighlighter() }

/**
 * A pure Kotlin syntax highlighter.
 *
 * Grammars are ported from highlight.js 11.11.1 and run on [HighlightEngine], a port of its mode
 * stack parser. An unsupported language is returned unhighlighted.
 */
class CodeHighlighter {
    private val engine = HighlightEngine(builtinLanguages())

    fun highlight(code: String, language: String): List<HighlightToken> {
        if (code.isEmpty()) return emptyList()

        // [FIX] 引擎内部有死循环兜底（MAX_ITERATIONS check 抛 IllegalStateException），
        // 但调用方（Compose remember 块）无捕获 → 崩溃整个 UI。兜底为纯文本。
        return try {
            engine.highlight(code, language)
                ?: listOf(HighlightToken.Plain(code))
        } catch (e: Exception) {
            Log.w(TAG, "highlight: fallback to plain text for $language", e)
            listOf(HighlightToken.Plain(code))
        }
    }

    fun supports(language: String): Boolean = engine.supports(language)
}

@Composable
fun CodeHighlightText(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    colors: HighlightTextColorPalette = HighlightTextColorPalette.Default,
    fontSize: TextUnit = 12.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    fontStyle: FontStyle = FontStyle.Normal,
    fontWeight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val highlighter = LocalCodeHighlighter.current
    val annotatedString = remember(code, language, colors, highlighter) {
        if (code.length > MAX_CODE_LENGTH) {
            AnnotatedString(code)
        } else {
            buildAnnotatedString {
                highlighter.highlight(code, language).forEach { token ->
                    buildHighlightText(token, colors)
                }
            }
        }
    }

    Text(
        modifier = modifier,
        text = annotatedString,
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}
