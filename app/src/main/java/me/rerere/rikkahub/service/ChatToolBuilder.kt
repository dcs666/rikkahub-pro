package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import java.time.LocalDate
import java.util.LinkedHashMap
import kotlin.uuid.Uuid

// [P2] 工具集缓存最多缓存的会话数（LRU 淘汰最久未用的会话）
private const val TOOL_CACHE_MAX_SESSIONS = 20
private const val TAG = "ChatToolBuilder"

/**
 * 工具构建域：按「配置指纹」构建/缓存每会话的 AI 工具集（MCP 包装 + workspace 工具 +
 * search/skill/conversation 工具）。拆自 ChatService（Strangler Fig）。
 *
 * 指纹覆盖所有影响工具集的配置项（含日期——search 工具描述内嵌今天日期），
 * 任何一项变化都会触发重建；LRU 上限 [TOOL_CACHE_MAX_SESSIONS] 防长期会话堆积。
 */
class ChatToolBuilder(
    private val context: Application,
    private val workspaceRepository: WorkspaceRepository,
    private val skillManager: SkillManager,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val conversationRepo: ConversationRepository,
    private val onAddError: (Throwable, Uuid?, String?, ChatErrorSolution?) -> Unit,
) {
    private val toolCache =
        object : LinkedHashMap<Uuid, Pair<String, List<Tool>>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Uuid, Pair<String, List<Tool>>>,
            ): Boolean = size > TOOL_CACHE_MAX_SESSIONS
        }

    fun clearCache(conversationId: Uuid) {
        toolCache.remove(conversationId)
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        // [FIX] DB READY 之外实时核对磁盘 rootfs（DB 可能残留 READY，例如 rootfs 被清理/
        // 备份恢复后未重启 App）：不一致时 isRootfsUsable 自动把 DB 降级为 DISABLED，
        // 避免注入工具后每次执行都返回 "Rootfs is not installed"。
        if (!workspaceRepository.isRootfsUsable(workspaceId)) {
            Log.w(
                TAG,
                "createWorkspaceToolsIfReady: rootfs not usable on disk, workspace=$workspaceId"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

    /**
     * [P2] 工具集构建（带会话级缓存）：
     * - 配置指纹覆盖：assistant 相关开关、localTools、skills、workspace 绑定、
     *   MCP 服务器名列表、搜索服务配置、日期（search 工具描述内嵌今天日期）。
     *   任一变化 → 重建；否则复用缓存的工具对象列表（每轮生成省一次全量构建）。
     * - 返回 null 表示 MCP 服务器名非法（已报错），调用方应中止本次生成。
     */
    suspend fun buildCachedTools(
        conversationId: Uuid,
        assistant: Assistant,
        conversation: Conversation,
        settings: Settings,
        allMcpTools: List<Triple<Uuid, String, McpTool>>,
    ): List<Tool>? {
        val fingerprint = buildString {
            append(assistant.id).append('|')
            append(assistant.localTools).append('|')
            append(assistant.enableWebSearch).append('|')
            append(assistant.enableRecentChatsReference).append('|')
            append(assistant.workspaceId).append('|')
            append(conversation.workspaceCwd).append('|')
            append(assistant.enabledSkills).append('|')
            // [F1] 技能目录指纹（子目录数 + 最新 mtime）：技能内容编辑后同会话
            // 内工具描述立即刷新（listSkills 结果已按同一指纹缓存）
            append(skillManager.skillsFingerprint()).append('|')
            // 搜索服务配置（execute 闭包运行时读 settings 快照，配置变化需重建）
            append(settings.searchServiceSelected).append('|')
            append(settings.searchServices.joinToString(",") { it.displayName }).append('|')
            // MCP 服务器（serverId:serverName 排序列表，服务器启停/重命名需重建）
            append(allMcpTools.joinToString(",") { "${it.first}:${it.second}" }).append('|')
            // 日期（search 工具 description 内嵌 LocalDate.now()）
            append(LocalDate.now())
        }
        synchronized(toolCache) {
            toolCache[conversationId]?.let { (cachedFingerprint, cachedTools) ->
                if (cachedFingerprint == fingerprint) {
                    return cachedTools
                }
            }
        }

        // MCP 服务器名校验（与旧逻辑一致）：非法名字 → 报错中止
        val invalidNames = allMcpTools
            .map { it.second }
            .distinct()
            // [FIX] 工具名规范允许 - 和 _（OpenAI/Anthropic 均按
            // ^[a-zA-Z0-9_-]+$ 校验）：只查字母数字会把 my-server 之类的
            // 合法 MCP 服务器名误判为 invalid → 工具整体不可用
            .filter { name ->
                name.isEmpty() || !name.all {
                    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
                }
            }
        if (invalidNames.isNotEmpty()) {
            onAddError(
                error = IllegalStateException(
                    context.getString(
                        R.string.error_mcp_invalid_server_name,
                        invalidNames.joinToString(", ")
                    )
                ),
                conversationId = conversationId,
                title = null,
                solution = null,
            )
            return null
        }

        val tools = buildList {
            if (assistant.enableWebSearch) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools, conversationId.toString()))
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepo, assistant.id))
            }
            addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }
            allMcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = {
                            mcpManager.callTool(serverId, tool.name, it.jsonObject)
                        },
                    )
                )
            }
        }
        synchronized(toolCache) {
            toolCache[conversationId] = fingerprint to tools
        }
        return tools
    }
}
