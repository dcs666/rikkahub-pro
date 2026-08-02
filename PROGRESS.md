# RikkaHub Turbo - 异步任务队列进度

## 当前状态：✅ 120 轮深度审查完成，等待最终 CI 通过后发 v1.1.8-turbo

## 已完成

### 核心功能 (commit e5132e34 + ad75fb73)
- BackgroundTaskManager：单例，自适应轮询（5s active / 30s idle）
- GitHubActionsClient：OkHttp + User-Agent + callTimeout(60s) + 日志限 512KB
- TaskEntity + TaskDao：Room 持久化，DB v24→25，status/created_at/conversation_id 索引
- TaskNotificationManager：消费事件 → 通知（尊重 notifyOnSuccess）+ 注入对话 + CI 失败自动 AI 分析
- BackgroundTaskTool：AI 工具（create_ci_monitor / create_timer / list_tasks / cancel_task）
  - 完整 InputSchema（action enum, repo, branch, run_id, poll_interval_sec 等）
  - 自动注入 conversationId（AI 不需要知道 UUID）
  - 去重：同 repo+branch+runId 不重复创建
- TaskRoutes：REST API + GitHub webhook 端点（/api/tasks/*）
- AppEvent.BackgroundTaskCompleted：新事件类型
- 通知渠道：background_task（IMPORTANCE_HIGH）

### 扩展功能
- SettingTasksPage：完整任务管理 UI（列表、取消、状态图标、Toast 反馈）
- Settings 字段：taskGithubToken, taskAutoAnalyze, taskNotifyOnSuccess, taskPollIntervalSec
- Webhook：完成已有任务（不创建新任务），JSON parse 错误返回 400
- 指数退避：base → 2x → 3x → 5x，最大 5min
- /api/tasks/timer 端点（验证 delay > 0）
- TaskDto 含 description 字段（repo@branch）

### 代码审查修复（10 批 × 12 轮 = 120 轮）
- 竞态条件：completeTask 防重入 + markRunningIfPending 条件更新
- per-task try-catch：单个任务异常不中断整批
- 自适应轮询间隔（省电）
- JSON 注入修复：buildJsonObject + JsonPrimitive
- 图标修复：全部使用确认存在的图标
- Okio API：ByteString.utf8()
- cleanupJob 引用追踪
- sealed class serializer 统一解码
- Timer 支持小数（toDoubleOrNull）
- 去重逻辑
- Toast 反馈
- 准确的消息文案（"active" not "created"）

## 发布历史
- v1.1.7-turbo：已发布（含到 batch 2 的修复）
- v1.1.8-turbo：待发布（含全部 120 轮审查修复）
