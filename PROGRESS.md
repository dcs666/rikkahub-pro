# RikkaHub Turbo - 异步任务队列进度

## 当前状态：✅ CI 全绿，准备发 nightly

## 已完成

### 核心功能 (commit e5132e34 + ad75fb73)
- BackgroundTaskManager：单例，5s 轮询循环，管理任务生命周期
- GitHubActionsClient：OkHttp 调 GitHub Actions API（run 状态 + 失败 job 日志）
- TaskEntity + TaskDao：Room 持久化，DB v24→25（AutoMigration）
- TaskNotificationManager：消费事件 → 通知 + 注入对话 + CI 失败自动触发 AI 分析
- BackgroundTaskTool：AI 工具（create_ci_monitor / create_timer / list_tasks / cancel_task）
- TaskRoutes：REST API + GitHub webhook 端点
- AppEvent.BackgroundTaskCompleted：新事件类型
- 通知渠道：background_task（IMPORTANCE_HIGH）

### 扩展功能 (commit 3d0a4154)
- SettingTasksPage：完整任务管理 UI（列表、取消、状态图标）
- Settings 字段：taskGithubToken, taskAutoAnalyze, taskNotifyOnSuccess, taskPollIntervalSec
- Webhook 修复：完成已有任务（不再创建新任务）
- 指数退避：5x base → 2x → 3x → 5x，最大 5min
- /api/tasks/timer 端点

### 代码审查修复 (commit c9373dc7 + 4cfda12c)
- 竞态条件：completeTask 防重入（重新从 DB 读状态）
- per-task try-catch：单个任务异常不中断整批
- GitHubActionsClient：User-Agent header, callTimeout(60s), 日志限 512KB
- TaskEntity：status/created_at/conversation_id 索引
- 图标修复：XCircle→AlertCircle, Github01→Github, CheckmarkCircle02→Tick02, Loading03→Refresh01
- InputSchema：完整参数定义（action enum, repo, branch 等）
- Okio API：ByteString.utf8()（非 readUtf8()）
- cleanupJob 引用追踪
- sealed class serializer 统一解码
- webhook JSON parse try-catch（返回 400 非 500）

## 待做
- [ ] 发 nightly release
- [ ] 用户实测验证
- [ ] 更多任务类型：文件监控、自定义 shell 命令
- [ ] 任务重试策略优化（可配置）
