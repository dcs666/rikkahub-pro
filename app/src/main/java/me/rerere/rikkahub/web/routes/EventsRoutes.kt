package me.rerere.rikkahub.web.routes

import io.ktor.server.routing.Route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.dto.ConversationListInvalidateEvent
import me.rerere.rikkahub.web.dto.FolderListEvent
import me.rerere.rikkahub.web.dto.toDto
import kotlin.time.Duration.Companion.seconds

/**
 * Multiplexed server-sent events stream.
 *
 * A single `/api/events` connection carries several event types, distinguished by the
 * SSE `event:` field, so new event kinds can be added without opening a new connection:
 *  - `settings`                     -> full Settings snapshot
 *  - `conversation_list_invalidate` -> the conversation list for an assistant changed
 *  - `folders`                      -> the folder list for an assistant changed
 *  - `task_completed`               -> a background task (CI monitor / timer) finished
 *
 * Per-conversation streaming (generation updates) keeps its own dedicated connection
 * at `/api/conversations/{id}/stream`.
 */
fun Route.eventsRoutes(
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
    eventBus: AppEventBus? = null,
) {
    sse("/events") {
        heartbeat {
            period = 15.seconds
        }

        // Full settings snapshot; StateFlow emits the current value immediately on connect.
        val settingsEvents = settingsStore.settingsFlow.map { settings ->
            EventPayload(event = "settings", json = JsonInstant.encodeToString(settings))
        }

        // Conversation list invalidation, scoped to the currently selected assistant.
        val conversationListEvents = settingsStore.settingsFlow
            .map { it.assistantId }
            .distinctUntilChanged()
            .flatMapLatest { assistantId ->
                combine(
                    conversationRepo.getConversationsOfAssistant(assistantId),
                    chatService.getConversationJobs()
                ) { conversations, generationJobs ->
                    // Key per conversation folds in generation state (so start/stop invalidates the
                    // sidebar even when content isn't persisted) and folderId (so moving a
                    // conversation between folders invalidates other clients' folder views).
                    conversations.map { conversation ->
                        buildString {
                            append(conversation.id)
                            append('|')
                            append(conversation.updateAt.toEpochMilli())
                            append('|')
                            append(generationJobs[conversation.id] != null)
                            append('|')
                            append(conversation.folderId?.toString().orEmpty())
                        }
                    }
                }
                    .distinctUntilChanged()
                    .map { assistantId }
            }
            .map { assistantId ->
                EventPayload(
                    event = "conversation_list_invalidate",
                    json = JsonInstant.encodeToString(
                        ConversationListInvalidateEvent(
                            assistantId = assistantId.toString(),
                            timestamp = System.currentTimeMillis()
                        )
                    )
                )
            }

        // Folder list for the currently selected assistant (Room flow emits on any change).
        val folderEvents = settingsStore.settingsFlow
            .map { it.assistantId }
            .distinctUntilChanged()
            .flatMapLatest { assistantId ->
                folderRepo.getFoldersOfAssistant(assistantId).map { folders ->
                    EventPayload(
                        event = "folders",
                        json = JsonInstant.encodeToString(
                            FolderListEvent(
                                assistantId = assistantId.toString(),
                                folders = folders.map { it.toDto() }
                            )
                        )
                    )
                }
            }

        // [FIX] 后台任务完成事件（CI 监控 / 定时器）桥接到 SSE：web 端无需轮询
        // /api/tasks 即可实时收到任务结果（AppEventBus 为 SharedFlow，无 replay，
        // 只投递连接建立后发生的事件，符合"实时通知"语义）。
        // eventBus 可空：未注入时仅缺 task_completed 事件，/events 其余事件不受影响。
        val taskEvents = eventBus?.events
            ?.filterIsInstance<AppEvent.BackgroundTaskCompleted>()
            ?.map { event ->
                EventPayload(
                    event = "task_completed",
                    json = buildJsonObject {
                        put("taskId", JsonPrimitive(event.taskId))
                        put("taskType", JsonPrimitive(event.taskType))
                        put("success", JsonPrimitive(event.success))
                        put("conversationId", JsonPrimitive(event.conversationId))
                        put("resultSummary", JsonPrimitive(event.resultSummary))
                        put("timestamp", JsonPrimitive(System.currentTimeMillis()))
                    }.toString()
                )
            }

        val merged = if (taskEvents != null) {
            merge(settingsEvents, conversationListEvents, folderEvents, taskEvents)
        } else {
            merge(settingsEvents, conversationListEvents, folderEvents)
        }
        merged.collect { payload ->
            send(data = payload.json, event = payload.event)
        }
    }
}

private data class EventPayload(
    val event: String,
    val json: String,
)
