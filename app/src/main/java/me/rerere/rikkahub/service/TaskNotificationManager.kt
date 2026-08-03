package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.TASK_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.sendNotification
import kotlin.uuid.Uuid

private const val TAG = "TaskNotificationMgr"
private const val TASK_NOTIFICATION_BASE_ID = 90000

/**
 * 消费 [AppEvent.BackgroundTaskCompleted] 事件：
 * 1. 发送 Android 通知（尊重 notifyOnSuccess 设置）
 * 2. 如果任务关联了对话且配置了自动分析，则注入消息并触发 AI 生成
 */
class TaskNotificationManager(
    private val app: Application,
    private val appScope: AppScope,
    private val eventBus: AppEventBus,
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
) {
    private var notificationCounter = 0

    init {
        appScope.launch(Dispatchers.Default) {
            eventBus.events
                .filterIsInstance<AppEvent.BackgroundTaskCompleted>()
                .collect { event ->
                    handleTaskCompleted(event)
                }
        }
    }

    private suspend fun handleTaskCompleted(event: AppEvent.BackgroundTaskCompleted) {
        Log.i(TAG, "Task completed: ${event.taskId} success=${event.success}")

        val settings = settingsStore.settingsFlow.first()

        // 1. 发送通知（尊重设置）
        val shouldNotify = event.success && settings.taskNotifyOnSuccess || !event.success
        if (shouldNotify) {
            sendTaskNotification(event)
        }

        // 2. 如果关联了对话，注入消息
        if (event.conversationId.isNotBlank()) {
            injectIntoConversation(event, settings.taskAutoAnalyze)
        }
    }

    private fun sendTaskNotification(event: AppEvent.BackgroundTaskCompleted) {
        val notificationId = TASK_NOTIFICATION_BASE_ID + (notificationCounter++ % 100)

        val notifTitle = when {
            event.taskType == "ci_monitor" && event.success -> "✅ CI Passed"
            event.taskType == "ci_monitor" && !event.success -> "❌ CI Failed"
            event.taskType == "timer" -> "⏰ Timer"
            else -> if (event.success) "✅ Task Done" else "❌ Task Failed"
        }

        // 点击通知打开 app（与 ChatNotificationManager 模式一致）
        val intent = Intent(app, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (event.conversationId.isNotBlank()) {
                // [FIX] RouteActivity.onNewIntent 读取的键是 "conversationId"（不是 "conversation_id"）
                putExtra("conversationId", event.conversationId)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            app, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        app.sendNotification(
            channelId = TASK_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId,
        ) {
            title = notifTitle
            content = event.resultSummary.take(200)
            contentIntent = pendingIntent
            autoCancel = true
            useBigTextStyle = true
        }
    }

    /**
     * 将任务结果注入到关联的对话中，并可选触发 AI 自动分析。
     */
    private suspend fun injectIntoConversation(event: AppEvent.BackgroundTaskCompleted, autoAnalyze: Boolean) {
        try {
            val conversationId = Uuid.parse(event.conversationId)

            // 构建注入消息
            val injectedMessage = buildString {
                append("[Background Task Result]\n")
                append("Task: ${event.taskType}\n")
                append("Status: ${if (event.success) "SUCCESS" else "FAILED"}\n\n")
                append(event.resultSummary)

                // 如果 CI 失败且开启了自动分析，请求 AI 分析
                if (!event.success && event.taskType == "ci_monitor" && autoAnalyze) {
                    append("\n\n---\n")
                    append("Please analyze the CI failure above and suggest fixes.")
                }
            }

            // CI 失败 + 自动分析 → 触发 AI 回复
            val shouldAutoGenerate = !event.success && event.taskType == "ci_monitor" && autoAnalyze

            chatService.sendMessage(
                conversationId = conversationId,
                content = listOf(UIMessagePart.Text(injectedMessage)),
                answer = shouldAutoGenerate,
            )

            Log.i(TAG, "Injected task result into conversation $conversationId, autoGenerate=$shouldAutoGenerate")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject into conversation", e)
        }
    }
}
