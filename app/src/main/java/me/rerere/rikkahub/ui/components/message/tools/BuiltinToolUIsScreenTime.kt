package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.highlight.HighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.CalendarAdd01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.openUrl
import org.koin.compose.koinInject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// [拆分] 屏幕时间工具 UI 域（拆自 BuiltinToolUIs.kt，Strangler Fig）

object GetScreenTimeToolUI : ToolUIRenderer {
    private const val SUMMARY_MAX_APPS = 3

    override val toolName: String = "get_screen_time"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.SmartPhone01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_screen_time)

    private fun apps(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("apps")?.let { it as? JsonArray } ?: emptyList()

    private fun isNoPermission(context: ToolUIContext): Boolean =
        context.content.getStringContent("error") == "NO_PERMISSION"

    override fun hasSummary(context: ToolUIContext): Boolean =
        isNoPermission(context) || apps(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        if (isNoPermission(context)) {
            Text(
                text = stringResource(R.string.assistant_page_local_tools_screen_time_permission_required),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            return
        }
        val apps = apps(context)
        if (apps.isEmpty()) return
        val totalMinutes = context.content?.jsonObjectOrNull?.get("total_minutes")
            ?.jsonPrimitiveOrNull?.longOrNull ?: 0
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.shimmer(isLoading = context.loading),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.tool_ui_screen_time_total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatMinutes(totalMinutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            apps.take(SUMMARY_MAX_APPS).forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = app.getStringContent("app_name")
                            ?: app.getStringContent("package") ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMinutes(app.appMinutes()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val apps = apps(context)
        if (apps.isEmpty()) {
            DefaultToolPreview(context = context)
            return
        }
        ScreenTimePreview(content = context.content!!, apps = apps)
    }
}

@Composable
private fun ScreenTimePreview(content: JsonElement, apps: List<JsonElement>) {
    val totalMinutes = content.jsonObjectOrNull?.get("total_minutes")
        ?.jsonPrimitiveOrNull?.longOrNull ?: 0
    val maxAppMs = apps.maxOfOrNull { it.appMs() }?.takeIf { it > 0 } ?: 1L
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tool_ui_screen_time_total),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMinutes(totalMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                val begin = content.getStringContent("start")
                val finish = content.getStringContent("end")
                if (begin != null && finish != null) {
                    Text(
                        text = "${formatRangeTime(begin)} → ${formatRangeTime(finish)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
        items(apps) { app ->
            val name = app.getStringContent("app_name")
                ?: app.getStringContent("package") ?: return@items
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMinutes(app.appMinutes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                LinearProgressIndicator(
                    progress = { (app.appMs().toFloat() / maxAppMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 读取单个应用条目的前台时长 (毫秒) */
private fun JsonElement.appMs(): Long =
    jsonObjectOrNull?.get("total_ms")?.jsonPrimitiveOrNull?.longOrNull ?: 0

/** 读取单个应用条目的前台时长 (分钟) */
private fun JsonElement.appMinutes(): Long =
    jsonObjectOrNull?.get("total_minutes")?.jsonPrimitiveOrNull?.longOrNull ?: (appMs() / 60000)

private val SCREEN_TIME_RANGE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 将工具返回的 ISO 时间字符串格式化为 "MM-dd HH:mm", 解析失败时原样返回.
 *
 * 工具用 ZonedDateTime.toString() 输出, 区域 ID 时会带 "[Asia/Shanghai]" 后缀,
 * 故优先用 ZonedDateTime.parse, 再回退到 offset / 本地日期时间.
 */
private fun formatRangeTime(iso: String): String = runCatching {
    ZonedDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
}.recoverCatching {
    OffsetDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
}.recoverCatching {
    LocalDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
}.getOrDefault(iso)

/** 将分钟数格式化为 "Xh Ym" / "Xh" / "Ym" */
private fun formatMinutes(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

