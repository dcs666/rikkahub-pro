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

## perf/rendering-and-streaming 分支审查（第 121 轮起）
### 已实现并通过 CI（run 73-75）
- `116a160` fix(ai): 工具级执行超时（workspace_shell = 命令 timeout + 15s 缓冲，其余 60s）+ 死代码清理 + task_completed 事件桥接 SSE
- `6077ce4` perf(workspace): 每 workspace 独立常驻 shell 会话池（LRU，MAX_SESSIONS=3，独立锁）
- `115ca09` perf(ai): 同轮多工具并行执行（Semaphore 上限 4，结果按序挂回，取消语义与串行一致）

### 本轮审查修复（2026-08-04）
- ProotShellRunner 会话淘汰：原 removeEldestEntry 在 sessionFor 锁内同步 destroy()，若被淘汰会话有命令在跑会阻塞全部 workspace 的 shell（全局头阻塞，最长 600s）。改为手动扫描、只淘汰空闲会话；全忙时允许池暂时超限（有界于并发命令数）
- eventsRoutes 条件注册 → 无条件注册：原 `eventBus?.let{}` 在 eventBus 为 null 时整个 /events 路由消失（settings/conversations/folders SSE 一起丢失）。现 eventBus 可空，仅缺 task_completed 事件
- R3.1 流式代码块语言标记剥离：```kotlin\ncode 流式渲染时首行会显示 "kotlin"（标记），生成结束切完整渲染后该行消失（跳变）。现按保守规则（紧贴围栏单 token + 有换行）剥离标记行；shebang/含空格行/单行代码不误删（JS 模拟 8 组边界全过）
- R3.1 注释纠偏：原文档称"未闭合尾段按普通文本兜底"，实现实为奇数索引段（含未闭合尾段）按代码渲染——后者才是"生成中代码可见"的预期行为，注释已更正

### 已确认无误（记录）
- R2.1 折叠调优：阈值 5000、整区可点展开、!loading 排除生成中消息、USER 不折叠——验证通过
- MarkdownParseCache LRU 上限 30，流式每帧缓存有界无泄漏
- AppEventBus 为 DI 单例，SSE 桥接可收到事件；timeout 参数名/钳制与工具一致；工具级超时对 shell 命令真正生效（runInterruptible→杀进程）；并行结果按序、取消语义与串行一致；锁顺序无死锁

### 已知取舍（记录不修）
- 并行工具对同一文件路径的写竞态（workspace_write_file + edit 同轮同路径可能 lost update）——OpenAI parallel calling 语义固有，模型通常不会同轮改同一文件
- 非 shell 工具超时后底层工作可能短暂继续（workspace_shell 走 runInterruptible 会真正杀进程）
- 流式语言标记仅限 ``` 围栏；~~~ 围栏与 4+ 反引号围栏不识别（既有范围）
