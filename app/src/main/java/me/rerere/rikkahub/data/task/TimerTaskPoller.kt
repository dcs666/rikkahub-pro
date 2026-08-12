package me.rerere.rikkahub.data.task

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.service.ChatService
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "TimerTaskPoller"

// [拆分] 定时器轮询域：pollTimerTask + 重试软状态 + 重复定时器调度
// （拆自 BackgroundTaskManager）。依赖 ChatService 懒加载（构造环防御）。

class TimerTaskPoller(
    private val json: Json,
    private val taskDao: TaskDao,
    private val chatService: () -> ChatService,
    private val onCompleteTask: suspend (
        task: TaskEntity,
        success: Boolean,
        resultJson: String,
        error: String,
        config: TaskConfig.CIMonitor?,
        aiAction: Boolean,
        steps: List<String>,
    ) -> Unit,
    private val onWakePoller: () -> Unit,
) {
    // 软状态：目标对话忙时的注入重试计数 / 重试冷却截止
    private val timerInjectionRetries = ConcurrentHashMap<String, Int>()
    private val timerRetryAt = ConcurrentHashMap<String, Long>()

    /** 任务被完成/取消/重置时清理软状态（防 Map 泄漏）。 */
    fun clearState(taskId: String) {
        timerInjectionRetries.remove(taskId)
        timerRetryAt.remove(taskId)
    }

    /** 下一次该任务可触发的绝对时间（主类唤醒调度用）。 */
    fun dueAt(task: TaskEntity): Long {
        val delayMs = runCatching {
            (json.decodeFromString(TaskConfig.serializer(), task.config) as? TaskConfig.Timer)
                ?.delayMs ?: 0L
        }.getOrDefault(0L)
        // 到期时刻 = 创建时刻 + 延迟；到期后返回过去时间 → 下一轮 poll 立即完成
        return task.createdAt + delayMs
    }

    suspend fun poll(task: TaskEntity) {
        // [FIX] 冷却期内的重试：目标对话忙时保持 PENDING 并进入 30s 冷却，
        // poller 每 2s 唤醒但这里直接放行冷却结束的任务。
        timerRetryAt[task.id]?.let { retryAt ->
            if (System.currentTimeMillis() < retryAt) return
            timerRetryAt.remove(task.id)
        }
        val config = try {
            (json.decodeFromString(TaskConfig.serializer(), task.config) as? TaskConfig.Timer)
                ?: run {
                    onCompleteTask(task, success = false, error = "Invalid timer config type", resultJson = "", config = null, aiAction = false, steps = emptyList())
                    return
                }
        } catch (e: Exception) {
            onCompleteTask(task, success = false, error = "Invalid timer config", resultJson = "", config = null, aiAction = false, steps = emptyList())
            return
        }
        // [⑨ M2] 工作流步骤：配置了 steps 用 steps（多步），否则 message 单步
        val workflowSteps = if (config.steps.isNotEmpty()) config.steps else listOf(config.message)

        val elapsed = System.currentTimeMillis() - task.createdAt
        if (elapsed >= config.delayMs) {
            // [FIX] 注入防丢失：定时 AI 动作的目标对话正在生成时（长回复/深挖轮次），
            // 原实现 completeTask 后 TaskNotificationManager 的 awaitGenerationIdle(2min)
            // 超时即跳过注入 → 触发永久丢失。改为延迟重试：
            // 保持 PENDING + 30s 冷却，poller 重试触发，最多 10 次（约 5 分钟）。
            if (config.autoAi && task.conversationId.isNotBlank() &&
                runCatching { chatService().isGenerating(Uuid.parse(task.conversationId)) }.getOrDefault(false)
            ) {
                val retries = timerInjectionRetries.merge(task.id, 1, Int::plus) ?: 1
                if (retries >= MAX_TIMER_INJECTION_RETRIES) {
                    timerInjectionRetries.remove(task.id)
                    onCompleteTask(
                        task,
                        success = true,
                        resultJson = buildJsonObject {
                            put(
                                "message",
                                JsonPrimitive(
                                    "Timer fired but the target conversation stayed busy >5min; injection skipped."
                                )
                            )
                        }.toString(),
                        error = "",
                        config = null,
                        aiAction = config.autoAi,
                        steps = workflowSteps,
                    )
                    Log.w(TAG, "Timer ${task.id}: gave up injection after $retries retries (conversation busy)")
                    return
                }
                timerRetryAt[task.id] = System.currentTimeMillis() + TIMER_INJECTION_RETRY_DELAY_MS
                Log.i(TAG, "Timer ${task.id}: target conversation busy, retry $retries/$MAX_TIMER_INJECTION_RETRIES in ${TIMER_INJECTION_RETRY_DELAY_MS / 1000}s")
                return
            }
            timerInjectionRetries.remove(task.id)
            timerRetryAt.remove(task.id)
            // 到期：完成本次触发
            onCompleteTask(
                task,
                success = true,
                resultJson = buildJsonObject {
                    put("message", JsonPrimitive(config.message))
                }.toString(),
                error = "",
                config = null,
                // [⑨] 定时 AI 动作：event.aiAction 透传，消费端据此触发 AI 生成；
                // steps 工作流序列随事件传递（消费端按序注入执行）
                aiAction = config.autoAi,
                steps = workflowSteps,
            )

            // [⑥ 重复定时器] 安排下一次触发：
            // - repeatIntervalMs > 0 且（无限 或 还有剩余次数）
            // - 新任务的 delayMs = repeatIntervalMs（后续间隔），repeatCount 递减
            // - 新任务继承 conversationId/message/autoAi/repeatIntervalMs
            val hasNext = config.repeatIntervalMs > 0 &&
                (config.repeatCount == 0 || config.repeatCount > 1)
            if (hasNext) {
                val nextCount = if (config.repeatCount > 0) config.repeatCount - 1 else 0
                taskDao.insert(TaskEntity(
                    id = Uuid.random().toString(),
                    type = TaskType.TIMER,
                    status = TaskStatus.PENDING,
                    config = json.encodeToString(TaskConfig.serializer(), config.copy(
                        delayMs = config.repeatIntervalMs,
                        repeatCount = nextCount,
                    )),
                    result = "",
                    conversationId = task.conversationId,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ))
                onWakePoller()
                Log.i(TAG, "Repeating timer scheduled next fire (remaining=$nextCount)")
            }
        } else if (task.status == TaskStatus.PENDING) {
            taskDao.markRunningIfPending(task.id)
        }
    }

    private companion object {
        const val TIMER_INJECTION_RETRY_DELAY_MS = 30_000L // 对话忙时定时注入重试冷却
        const val MAX_TIMER_INJECTION_RETRIES = 10        // 重试上限（约 5 分钟窗口）
    }
}
