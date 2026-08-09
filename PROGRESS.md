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
- v1.6-turbo：已发布（2026-08-04，含 R3.2 块级流式渲染 + 会话池/SSE 修复）

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

### R3.2 块级结构化流式渲染（2026-08-04）
- 流式分支从"仅代码块拆分"升级为"按行块分类"：代码块/标题/引用/列表/段落，样式与最终渲染对齐（HeaderStyle.fromLevel / 引用左线+斜体+浅背景 / "• " 与 "N. " 前缀 / monospace 容器）
- 分类规则与 intellij 解析器对齐（# 后必须空格、> 空格可选、列表符号后必须空格）→ 结束时无块类型跳变
- 追加式扫描：每帧 O(n) 行扫描，已渲染块参数不变 → Compose 智能跳过重组；JS 模拟 18 帧流式序列验证块序列完全稳定
- 行内格式（加粗/行内代码/链接）仍 strip 擦除兜底；跨行行内标记不做解析（防闪现又消失）
- 未闭合代码块尾段按代码块渲染（生成中代码可见），语言标记随围栏行整体丢弃（替代 R3.1 的单 token 启发式，更简单准确）

### 已确认无误（记录）
- R2.1 折叠调优：阈值 5000、整区可点展开、!loading 排除生成中消息、USER 不折叠——验证通过
- MarkdownParseCache LRU 上限 30，流式每帧缓存有界无泄漏
- AppEventBus 为 DI 单例，SSE 桥接可收到事件；timeout 参数名/钳制与工具一致；工具级超时对 shell 命令真正生效（runInterruptible→杀进程）；并行结果按序、取消语义与串行一致；锁顺序无死锁

### 已知取舍（记录不修）
- 并行工具对同一文件路径的写竞态（workspace_write_file + edit 同轮同路径可能 lost update）——OpenAI parallel calling 语义固有，模型通常不会同轮改同一文件
- 非 shell 工具超时后底层工作可能短暂继续（workspace_shell 走 runInterruptible 会真正杀进程）
- 流式语言标记仅限 ``` 围栏；~~~ 围栏与 4+ 反引号围栏不识别（既有范围）

## 第 123-124 轮全面审查（2026-08-04，workspace 模块 + data/service 层 + web 层）
### 本轮审查修复（6 个提交）
- `d990687` fix(workspace): extractTar 特殊头（LONG_NAME/LONG_LINK/PAX/GLOBAL_PAX）处理移到 name.isBlank() 检查之前 —— GNU tar 全局头 name 为空，原顺序直接跳过数据区，GLOBAL_PAX 支持（e6a347f 引入）实际从未生效；parsePax 加固：length<=0 死循环、损坏头 substring 崩溃（JS 6 组边界全过）
- `df7bb7c` fix(settings): SettingsStore.update(fn) 读-改-写加 Mutex 串行化 —— 并发调用基于同一快照导致 lost update（MCP syncTools 并发丢工具列表的根因）
- `88a12d1` fix(sync): zip-slip 路径穿越 —— S3/WebDAV 备份恢复的 upload/ 分支直接 File(uploadFolder, fileName) 无穿越防护（FONTS 有 contains('/')、skills 有 SkillPaths canonical 防护），恶意/损坏备份可写 app 私有目录任意位置。修复为 canonical + startsWith 检查（JS 模拟 8 场景验证）
- `9d73247` fix(web): webhook HMAC 验证恒失败 —— expected 带 "sha256=" 前缀而 provided removePrefix 去前缀，MessageDigest.isEqual 对长度不同数组恒 false，配置 Token 后 webhook 全 401（秒级通知通道失效只剩轮询）
- `859a16e` fix(mcp): getAllAvailableTools 用 getCurrentAssistant() 过滤 → 改为调用方传入对话所属 assistant —— 多助手场景工具错配（暴露未授权 server 或缺失应有工具）
- `c897647` fix(chat): ConversationSession.setJob 旧 job 完成回调无条件清 _generationJob.value，晚于新 job 设置时误清新引用 → isGenerating 假 false / stopGeneration 失效 / 空闲检查误清理。改为 identity 比较

### 已确认无误（本轮深审）
- PromptInjectionTransformer（findSafeInsertIndex 防护、AT_DEPTH 深到浅、正则错误安全降级）
- GitHubActionsClient（rate limit 专用异常、4MB 日志上限、错误行提取）
- McpSessionRegistry（stale 检查、重连去重、NonCancellable 清理、connectionKey 精确判定）—— 记录：两 server 并发 syncTools 的 settingsStore.update lost update（已随 df7bb7c 修复）
- FilesManager（UUID 文件名、磁盘↔DB 双向同步、content:// 先落本地）
- ChatboxImporter（流式 JsonReader 不整载、SYSTEM 提示合并、未知 part 保留原文）
- ConversationRepository / MessageFtsManager（事务外 decode、FTS 参数化、blob 超限兜底）
- AppDatabase（migration 链 1→25 完整、WAL、jieba+FTS5）
- ChatService（fork 文件独立副本、内存-库一致性、压缩并行无上限——记录不修）
- ScreenTimeTool / CalendarTool / WebDavClient / S3Client（SigV4 签名正确）/ McpOAuth（PKCE+state 防 CSRF+刷新锁）
- WebApiModule（JWT 动态验证、常量时间比较、webhook 独立认证、zip-slip 双重防护）
- transformers / ExportSerializer / LocalTools / AppEventBus / BackgroundTaskManager 调度（动态唤醒+退避）
- MarkdownNew = HTML 内容回退渲染（活代码）

### 记录不修（低风险）
- compressConversation 的 async 并行无并发上限（chunk 数 = ceil(消息/256)，长对话并行请求多）
- tar size 字段 base-256 编码（>8GB 归档才出现）
- 下载无完整性校验（截断由 extractTar EOF 防御兜底）
- SimpleHtmlBlock 的 img src 任意 scheme（Coil 加载，用户主动渲染的 AI 输出）
- 多 <think> 块只保留第一个进 Reasoning（replace 删全部但 find 取第一个）

## 功能改进批次（2026-08-04，a797c10）
1. **搜索结果分页**：FTS LIMIT 50 硬编码 → limit/offset 参数化；SearchVM 每页 30 条滚动加载（过期页丢弃：查询/排序变化时忽略），SearchPage 接近底部自动加载下一页
2. **对话压缩限并发**：compressConversation 全并行 → Semaphore(2)，避免长对话同时打十几个请求触发 API 速率限制
3. **R3.3 流式行内格式**：闭合的行内代码/加粗/斜体生成中即可见（与最终渲染样式一致：等宽/Bold/Italic，复用旧 strip 同一组正则 → 识别规则零偏差）；未闭合标记按字面量显示（intellij 同样按字面量渲染 → 流式→完整零跳变）；链接仍剥除（半截 URL 渲染成链接会跳变）；JS 帧模拟验证：闭合瞬间出现样式且后续帧稳定、code 优先不重叠、嵌套正确、孤立标记字面量
4. **编辑消息版本切换 UI**：editMessage 追加版本但 UI 无切换入口（selectMessageNode 仅 web API）→ 多版本消息显示 v{n}/{m} 徽章，点击循环切换（ChatMessage → ChatList → ChatPage → ChatVM.selectMessageNode 链路）

## 功能改进批次修复（2026-08-04，0b3424f）
- **Build APK #104 失败**（a797c10 触发）：SearchVM 分页状态声明（isLoadingMore/hasMore/loadedCount/activeQuery/PAGE_SIZE）在首次编辑时被 workspace_edit_file 的宽容匹配**静默吞掉**——old_text 与实际文件不一致（误以为文件已有这些声明），宽容匹配跳过不存在的行后整块替换，代码引用了未声明变量 → unresolved reference
- 修复：恢复全部声明；逐一核对 10 个改动文件完整性（Markdown/ChatMessage/ChatList/ChatPage/ChatVM/ChatService/FTS/SearchVM/SearchPage/MessageFtsManager）
- **教训**：workspace_edit_file 的 old_text 必须与文件实际内容逐字一致；宽容匹配会在内容不匹配时静默替换，导致声明/逻辑丢失。编辑后用 grep 核对新增符号（本会话已核对）

## v1.7-turbo 发布（2026-08-04，2.4.6/175）
- **CI 连续修复 3 轮（df7bb7c → 0b4f002）**：
  1. **根因**：df7bb7c 把 `updateMutex = Mutex()` 插进 settingsFlowRaw 流链中间 → decode 管线挂到 Mutex() 上（非 Flow）→ 205 个编译错误，所有 Settings 消费方（~25 文件）级联失败。CI 从 df7bb7c 起红，之前监控未确认误以为绿
  2. **暴露的真实错误**：Settings 修好后 13 个错误全在功能改进批次：
     - 并行 edit 同文件竞态（read-modify-write 非原子）丢参数/import（ChatList onSelectVersion ×2、SearchPage 3 import）
     - `SpanStyleRange` 不存在 → `AnnotatedString.Range`
     - 原 bug 被掩盖：ChatService:553 `getAllAvailableTools()` 缺 assistant 参数
  3. **教训**：① edit 后必须 grep 核对新增符号；② 同文件编辑必须严格串行；③ CI 失败要拉完整错误列表（grep 全量 e: 行），别只看头部
- 发布：v1.7-turbo（含 8 修复 + 4 功能改进 + 3 轮 CI 修复）

## 全面逐行审查批次（2026-08-04，v1.7-turbo 发布期间）
- **新修复**：
  1. **JavascriptTool QuickJS 内存泄漏**（bc72f78）：每次 eval 创建 QuickJSContext 不销毁 → 原生内存泄漏，长会话 OOM。try/finally destroy()
  2. **i18n 硬编码中文**（d26bb49）：折叠按钮"展开全文"抽到 strings.xml（en/zh/zh-TW/ja/ko/ru 6 语言）
- **确认无问题**：web 模块全量（WebApiModule JWT 动态密码/常量时间比较、WebServerService FGS 容错、WebServerManager 端口预检、NsdServiceRegistrar、FilesRoutes 双保险路径防护、TaskRoutes webhook HMAC、ConversationRoutes UUID 校验）、通知管理器（节流/前后台）、ChatInputState 编辑合并、hooks（debounce/throttle/生命周期）、transformers（时间提醒/think 标签/正则/模板）、MCP OAuth（PKCE/DCR/串行刷新）、DAO 全参数化、SkillPaths 防护、migrations 兜底、CrashHandler、终端会话、VM 无泄漏
- **记录不修（低风险/取舍）**：
  - CoroutineUtils.toMutableStateFlow 流异常时 Runtime.halt(1) 硬杀进程（fail-fast 设计，settings 损坏场景）
  - MCP OAuth token 明文存 Preferences（app 私有目录，与既有模式一致）
  - webhook 未配 token 时不校验签名（局域网暴露场景低危）
  - WebServerManager.restart 未使用（stop 异步 + start early-return 竞态，无调用方）
  - FilesRoutes canonicalPath.startsWith 缺分隔符边界（已被 ".." 检查前置拦截）

## 持续逐行审查批次 2（2026-08-04，v1.7-turbo 发布后）
- **新修复**：
  1. **web-ui workbench 预览 XSS 加固**（9a15eab/5757477/7f4431b）：iframe sandbox 去掉 allow-same-origin（srcDoc iframe 继承父 origin，allow-scripts+allow-same-origin 组合可读写父页面 localStorage 里的 JWT）；mermaid securityLevel loose→strict（禁点击回调脚本）
  2. **ChatList.kt 括号修复**（6af5d66）：并行 edit 竞态残留的多余闭括号
- **确认无问题**：ai 模块全量（Provider 流式/KeyRoulette LRU/SSE/工具调用聚合/无界缓冲）、common（LruCache/QuickJSFetch 未用）、document、search（19 服务）、speech（TTS/ASR）、highlight（纯 Kotlin 引擎）、前端 api.ts（JWT 生命周期/401 处理/SSE）
- **记录不修**：QuickJSFetch 死代码（无调用方）、JSON 深层渲染无限制（acyclic）、McpOAuth deep link 无 state 时被忽略（随机 state 防护）
- **教训 3**：JSX 属性间不能写 // 注释（esbuild 报错）；同文件 edit 串行是硬约束（本轮 4 次竞态：ChatList 括号、sandbox 注释、文件尾部三重重复）

## 持续逐行审查批次 3（2026-08-04，审查收官）
- **确认无问题**：ChatSizeChecker（768 节点/30万token 阈值）、ChatDrawerVM（Paging+分隔符）、PromptVM/QuickMessagesVM（引用完整性清理）、SkillsVM（GitHub 导入 URL 正则校验 + zip 路径穿越防护 + 原子保存回滚）、WorkspaceVM、ProviderConnectionTester、前端编辑 draft 逻辑（附件保留/追加）、设置页全量
- **记录不修**：SkillsVM GitHub 导入无文件数/下载大小限制（技能仓库通常小）；workspace runBlocking（DocumentsProvider 同步 binder 必需）
- **收官状态**：621 Kotlin + 109 前端文件全部覆盖（深审核心逻辑 + 全库危险模式扫描）；TODO/FIXME=0；GlobalScope/Thread.sleep/System.exit=0

## 持续审查批次 4（2026-08-04）
- **新修复**：file_paths.xml 收窄 filesDir 暴露（db26bff）：原 path="." 把整个 filesDir（skills/fonts/workspaces）暴露给获得分享 URI 的外部 app → 只留 upload/images/tool_outputs；导出/相机/workspace 分享均在 cacheDir
- **确认**：Manifest 权限/exported 状态合理（WebServerService exported=false、FileProvider=false、DocumentsProvider 标准配置）；ShareSheet 的 apiKey 明文分享为有意设计（测试锁定往返）；测试套件 17 个文件覆盖 TextReplacers/PromptInjection/MCP key/backoff/rate-limit
- **记录不修**：usesCleartextTraffic=true（自托管 provider 需要）、McpOAuth deep link exported（state 防护）

## v1.7.1-turbo 发布（2026-08-04，2.4.7/176）
- **包含修复**（v1.7-turbo 之后）：QuickJS 原生内存泄漏（bc72f78）、i18n 折叠按钮 6 语言（d26bb49）、冗余 UI 移除（290973f+6af5d66）、web-ui 预览 iframe sandbox + mermaid strict（e9b7749）、file_paths 收窄 filesDir 暴露（db26bff）、全部审查记录
- CI 全绿验证：09888a0（Unit Tests + Build APK success）

## v1.7.2-turbo 候选修复批次（2026-08-04，11 个提交 c135a3a..4f135b3）
- c135a3a 超长消息保存失败静默吞掉 → 用户可见错误（>2MB blob 限制）
- ffc5a3a 压缩期间新消息被整体覆盖 → 合并最新状态保留追加节点
- 5be15b3 删光搜索服务后 coerceIn(0,-1) 设置保存崩溃
- 77fef1b 工具结果 base64 图片漏转 → require 失败消息丢失（抽取 parts 级转换复用）
- 1588d97 损坏 base64 图片 decode null → NPE 崩溃（降级保留原 part）
- fe2109d mermaid 导出失败误报成功 + 移除调试 URI 日志（CI 已验证 ✅）
- 3652fd6 RouteActivity CRLF 规范化
- f2a84d9 技能 zip 导入 zip bomb 防护（单文件 32MB/总量 256MB）
- 48dd5c3 BitmapComposer 取消安全（view 泄漏 + 永久挂起）+ import
- 4f135b3 文档注入 prompt 截断（200K 字符，防 API 413/超时）

## v1.7.2-turbo 发布（2026-08-04，2.4.8/177）
- **包含 18 个修复**（v1.7.1 之后，7230279 全绿验证）：
  - 数据丢失：超长保存静默失败（c135a3a）/ 压缩覆盖新消息（ffc5a3a）/ 工具 base64 漏转（77fef1b）
  - 崩溃：coerceIn(0,-1) 设置保存（5be15b3）/ decode null NPE（1588d97）/ 技能 zip bomb（f2a84d9）/ 导出取消挂起（48dd5c3）/ 编译修复 ×3（3a82c42, 836440d, 1629002）
  - 资源防护：文档注入截断（4f135b3）/ 记忆上限（06baee4）/ QuickJS 内存+超时（dd60e0a）/ fetch 5MB（ce187e4）/ Bing body 2MB（7230279）
  - 其他：mermaid 误报（fe2109d）/ CRLF（3652fd6）/ 日志清理
- CI 全绿：7230279（Unit Tests + Build APK success）
- **v1.7.2-turbo 发布完成**：2026-08-04 15:35（2.4.8/177，3 个 profileable APK，Release Turbo success，约 24 分钟构建）

## 全库深挖收官（2026-08-04，v1.7.2 发布后）
- 6 批深挖（未发现新 bug，全部干净）：
  1. utils 22 个工具类（UpdateChecker/SemVer/SimpleCache/SoundPool/通知/CursorWindow/时间格式化等）
  2. 设置页 21 个（端口校验/权限/主题导入/任务/MCP 导入/文件删除确认等）
  3. 功能页面（ImgGen/Translator/终端 proot/日志/收藏/历史/分享/Debug/工作区）
  4. speech 模块 4303 行全审（TTS 8 provider + ASR 5 provider：超时/SSE/WebSocket/录音释放/临时文件清理全到位）
  5. web-ui hooks 6 个 + chat-input + 组件（epoch 竞态防护/订阅清理/发送状态机高质量）
  6. material3 DynamicSchemeExt + locale-tui（yaml.safe_load 安全）

## 深挖第二轮（2026-08-04，v1.7.2 后批 7-29，发现 3 个新修复）
- 66483bf JsonExpression 求值器 StackOverflowError：余额表达式超长/深嵌套（粘贴触发）→ 512 字符上限 + SOE 兜底
- 7c50c22 parseErrorDetail 递归深度上限 32：恶意/异常 API 深嵌套错误 JSON → SOE 崩溃
- 防御：collectAllParts 嵌套深度上限 16（工具结果递归展开）
- 深审确认干净：ai Provider 全家（FileEncoder OOM 防护/KeyRoulette LRU/ResponseAPI 流式/Claude/Google/Vertex JWT）、common 缓存/SSE、workspace 安装器（tar/pax/symlink 防护）、document 解析器、highlight 状态机（无 ReDoS）、web 路由全家（AIIcon/Folder/Settings/RouteUtils）、DTO、依赖审计（全最新版）、BackgroundTaskManager 状态机、MCP OAuth（PKCE/刷新锁）、Navigation、ChatDrawerVM

## 深挖第三轮（2026-08-04 批 30-40，新增 4 个修复，累计 24）
- ad7da54 highlight 引擎兜底：MAX_ITERATIONS 守卫触发时降级纯文本（原崩溃 UI）
- a9449cc toolCallId 文件名清洗：模型可控 ID 含 ../ → 逃逸写 app 私有目录
- 1ed113a 记忆双端限制：条数 200（最旧淘汰）+ 注入 60 条/100K 字符（防 prompt 爆炸）
- 深审干净：ChatVM/ChatService（防并发生成+Cancellation 过滤）/FilesManager/S3Sync+WebDav 导入（zip-slip canonical 防护在案）/SkillPaths/HighlightEngine（零宽匹配防死循环+迭代守卫）/CalendarTool（LIKE 参数化）/BackgroundTaskTool（poll 下限 10s）/MemoryTools/GenerationHandler

## 深挖第四轮（批 41-50，全部干净，0 新 bug）
- 审过：PreferencesStore 迁移 V1-V3/SettingsJsonMigrator（runCatching 兜底）、设置 ProviderDetail（删除确认）、OCR（LRU 缓存+getOrElse）、ASR 音频焦点（GRANTED 检查+abandon）、ModelDsl TokenMatcher（内置正则）、WebDav/S3 同步（zip-slip 同款防护）、WebApiModule JWT（动态密码/subject 校验/403 区分）、前端 files/export-markdown、AI 嵌入
- 剩余轻微 UX 项（非 bug，记录不修）：ASR 焦点拒绝无提示、ModelList 加载失败静默、WebDav URL 未编码（path 内部生成）

## v1.7.3-turbo 发布（2026-08-04，2.4.9/178）
- 包含 6 个修复（v1.7.2 后深挖轮）：JsonExpression SOE（66483bf）/ parseErrorDetail 深度（7c50c22）/ collectAllParts 深度（198ec66）/ highlight 兜底（ad7da54）/ toolCallId 文件名清洗（a9449cc）/ 记忆条数 200+注入封顶（1ed113a）
- 双绿验证：5813aef（Unit Tests + Build APK success）

## 深挖第六轮（批 61-90，发现 1 个修复，累计 26）
- f2bf4e4 Room AutoMigration 链缺口（6→7/11→12/13→16 缺失 → 存量用户升级崩溃）补齐
- 审过干净：MCP 官方 SDK transport（本地 transport 为死代码）、ChatPage 图片链路（HEIF 转换+回退）、assistant 详情/导入（SillyTavern 解析容错）、SearchVM（debounce）、web-ui conversation/code-block（subscriber 清理）、highlight 语言定义、ai 核心类型（Modality 设计）、AppDatabase 其余迁移、工作区编辑器、导出导入（JSON 校验）、Koin 4 模块、Manifest（exported 正确）、Gradle（minify=false 有注释）、前端 types/locales、SafeMode（崩溃恢复）、WebViewPage（JS 默认关闭）、Provider.getBalance TODO（被类型守卫挡住非 bug）

## 深挖第七轮（批 91-140，50 批，0 新 bug）
- 审过：消息组件（分组/分支/工具 UI）、ChatSizeChecker（阈值）、PropertyEditor（JSON 容错）、Navigator（自研导航）、ai 类型、highlight 31 语言、FTS（limit coerce+cursor.use+枚举 orderBy 无注入）、OCR 缓存 64、ChatExport（FileProvider）、通知渠道 5 个、StatsVM（GROUP BY≤371 行）、web-ui hooks 全 6 个（cleanup 完整）、SkillsVM（zip bomb 防护复核）、终端（onDispose finish）、i18n 完整性验证（@string 引用 0 缺失）、ModelRegistry（模态解析正确）、ChatService 压缩 Semaphore(2)、S3Sync 复核、RikkaHubApp 初始化链（全 runCatching）、CrashHandler、后台维护 5 函数、TTSAutoPlay/ConversationList/WorkspaceRepository

## 深挖第八轮（批 141-190，50 轮，发现 1 个修复，累计 27）
- 15e37df translateText 输入截断 200K（与文档注入一致，防 API 413/超时）
- 审过干净：AudioPlayer 全量（listener 清理/invokeOnCancellation/WAV 头）、TaskEntity 4 配置、WebServerService、ChatboxImporter（流式+稳定 UUID）、翻译双分支、ImgGenPage、FolderRepository、CustomTheme（toInt 截断安全）、workbench（iframe sandbox=allow-scripts 无 same-origin ✔）、McpOAuthCallback（appScope 防丢事件）、AskUser/ScreenTime（时间解析容错+范围校验）/Clipboard/Calendar（审批+权限）工具、SettingSearchDetailPage

## 深挖第九轮（批 191-240，50 轮，发现 1 个修复，累计 28）
- 5f53f30 GitHub API httpGet 响应限量 4MB（与日志限量一致，防异常响应 OOM）
- 审过干净：ChatDrawer（snapshotFlow 滚动保存）、MeshGradient/Background（opacity coerce）、BalanceOption、SearchPage snippet 解析（index 推进无死循环）、web-ui clipboard（execCommand 回退+选区恢复）、DTO 全量、speech 模型、GitHubActionsClient（30s 超时/rate limit 识别/4MB 日志）、web-ui i18n 键 0 差异、Settings 模型默认值、ChatList（滚动/选中）、HighlightCache（LRU 100+synchronized）、ImageGenSize、LanguageSelector（when+else 兜底）、ConversationSystemPromptButton

## 深挖第十轮（批 241-290，50 轮，0 新 bug）
- 审过：PresetTheme/findThemeById（兜底）、消息组件剩余（CopySheet/NerdLine/Translation/EditedFiles 导出 FileProvider）、ToolUI/BuiltinToolUIs（记忆删除）、ChatInputState（编辑态）、web-ui api.ts（localStorage token+expiresAt 校验）、ProviderConfigure/ConnectionTester（runCatching）、AppEventBus（buffer 16+tryEmit 丢弃策略，7 文件消费方无孤儿）、PreferencesStore update（dummy 防护）、NotificationUtil、SettingPage（赞助弹窗）


## 深挖第十一轮（批 291-300，10 轮，5 新修复）
- 批 291 ShareHandlerVM/Page：分享文本经 base64 → 输入框无长度限制 → 与用户消息截断合并修复
- 批 292 Favorite/History：**修复 30** 历史页滑动删除立即物理删附件 → 撤销恢复后附件永久丢失；deleteConversation 加 deleteFiles 参数，撤销窗口结束才清理文件（2b3574d）
- 批 293 ImgGenVM/TranslatorVM：**修复 31** 图片文件名含未清洗 modelName（用户自定义 provider 远端响应可含 /.. → 路径逃逸）；sanitizeModelName [A-Za-z0-9_-]+48（8425679）
- 批 294 AssistantDetailVM 头像/背景：干净（createChatFilesByContents 复制私有目录，删除安全）
- 批 295 PropertyEditor/Extensions：**修复 32** CustomBodies JSON 解析深嵌套（万层）→ parseToJsonElement SOE（Error 非 Exception）→ UI 崩溃；catch Throwable（a038e62）
- 批 296 LocalTool/Memory 页：干净（权限守卫 + 数据层记忆限制兜底）
- 批 297 Mcp/Request/Prompt 页：干净（正则 compileRegexCached + 异常兜底完善）
- 批 298 MemoryPage/Importer：**修复 33** 导入 JSON readText() 全量读 + PNG tEXt/Base64 解码无上限 → OOM；5MB 上限（2c8487c）
- 批 299 DetailPage/BackgroundPicker：干净（URL 背景安全、删除只删本地副本）
- 批 300 AssistantPage/VM：干净（删除 assistant 有确认弹窗，连带删记忆+对话有提示）
- 另：**修复 29** 用户消息 200K 截断（0e6432f，ChatService.preprocessUserInputParts 单点）——
  分享超大文本/粘贴超长 → 消息存库膨胀 + 上下文爆表 + API 413，与翻译/文档 200K 一致


## 深挖第十二轮（批 301-322，22 轮，2 新修复）
- 批 301 FloatingWindow/WebViewContentCache：**修复 34** 悬浮窗无 SYSTEM_ALERT_WINDOW 权限 → WindowManager.addView SecurityException → TTS 朗读即崩溃；Manifest 无声明且未检查 → canDrawOverlays 守卫静默降级（25bfbdc）；WebViewContentCache id=sha256 校验防路径遍历 ✔
- 批 302 ai/completion：干净（BFS 上限 500/80 + gitignore 缓存 + fuzzyScore 线性）
- 批 303 richtext Mermaid/SimpleHtmlBlock：XSS 安全确认（escapeHtml 完整转义 + Jsoup 白名单 + jsInterface 无注入点）
- 批 304 MarkdownNew/ZoomableAsyncImage：干净（LinkAnnotation 无代码执行、coil file:// 仅显示）
- 批 305 MathBlock/LatexText/DiffView：干净（jlatexmath 本地渲染无 WebView）
- 批 306 MarkdownBlock：干净（MarkdownParseCache LRU 30 有界）
- 批 307 HighlightCodeBlock/JsonTree/QRCode：干净（JsonTree 仅日志页、输入自产）
- 批 308 hooks TTS/ASR/SharedPreferences：干净（ASR 焦点拒绝=已知记录项）
- 批 309 PlayStore/EmojiBurst/DataTable：干净
- 批 310 hooks 剩余 + NavContext：干净（useDebounce 无调用方、useThrottle 调用安全）
- 批 311 UpdateChecker/StringUtils：干净（官方端点可信、escapeHtml 完整转义；模板二次替换轻微记录）
- 批 312 SoundEffectPlayer/SimpleCache：干净（pendingPlay 泄漏有限、缓存过期清理）
- 批 313 CrashHandler/PlayStoreUtil/DatabaseUtil：干净（commit 同步写崩溃标记）
- 批 314 ContextUtil/AIIconMatcher：干净（openUrl runCatching 兜底）
- 批 315 ImageUtils：干净（inSampleSize + RGB_565 采样解码）
- 批 316 TimeUtil/ChatUtil：干净
- 批 317 FilesPicker：文件附件无大小限制=用户主动选择，记录不修
- 批 318 ModelList/SearchPicker：干净
- 批 319 permission/WebViewLocalAssets：干净（assets 路径遍历拦截）
- 批 320 BitmapComposer/ShareSheet：干净（分享含 key=用户主动；decodeProviderSetting runCatching 兜底）
- 批 321 activity/theme/杂项：干净（均已覆盖）
- 批 322 TextArea 等收尾：**修复 35** TextArea 文件导入无上限 → 输入框渲染卡死 + system prompt 膨胀 413；200K 截断（b6a3599）


## 深挖第十三轮（批 323-334，12 轮，2 新修复）
- 批 323 PreferencesStore/CoroutineUtils：干净（Mutex 串行化 + dummy 防护 + halt(1) 防损坏设计）
- 批 324 data/model + db/entity：干净（纯数据类）
- 批 325 data/files/favorite/export/api：**修复 36** ExportSerializer.readUri 无大小限制 → 导入 JSON OOM；5MB 上限（1130ba8）
- 批 326 TaskValidation/TaskDao：干净（REPO_PATTERN + 条件 UPDATE 防终态覆盖）
- 批 327 DAO 批量：干净（全参数化无 SQL 注入）
- 批 328 transformers：**修复 37** 模型输出 base64 图片无限制（超大 base64 decode OOM + 超大尺寸无采样 bitmap OOM）；base64 20MB 上限 + inSampleSize 4K 采样 RGB_565（0b2fdd8）
- 批 329 PromptInjectionTransformer：干净（用户自配注入 + findSafeInsertIndex 防 USER→ASSISTANT(tools) 间隙）
- 批 330 transformers 剩余 + prompts：干净（静态 prompt、占位符替换轻微记录）
- 批 331 tools：干净（shell 超时 600s + 读文件 8MB 限量）
- 批 332 tools/local + AppDatabase：干净（迁移链最终确认 version 25 完整）
- 批 333 GenerationPrompts/WebServerManager：干净（记忆注入 60/100K 确认；restart 竞态在未调用路径记录）
- 批 334 NsdServiceRegistrar/web 剩余：干净（jmdns runCatching 兜底）


## 深挖第十四轮（批 335-345，11 轮，4 新修复）
- 批 335 ai/util：**修复 38** mergeJsonObjects/removeElements 递归无深度上限（CustomBody 请求构造 + GoogleProvider 模型响应深嵌套 → SOE 崩溃）；depth 32 防御截断（6fdb22d）
- 批 336 ai 核心：干净（InstantSerializer/TokenUsage.merge/ReasoningLevel）
- 批 337 common 模块：干净（await/SSE/AcceptLang）
- 批 338 WorkspaceFileSystem：**修复 39** delete/move 只拒绝字面量 "."，"./" 变体可解析到工作区根 → recursive 删除整个工作区（模型可控 path）；canonical 对比根目录拦截（9677cd4）
- 批 339 PersistentShellSession：干净（shellQuote 注入防护 + sentinel 超时销毁 + destroy SIGTERM→SIGKILL）
- 批 340 ProotShellRunner/RootfsPatcher：干净（会话池 LRU 有界 + 空闲才淘汰 + 每 workspace 锁 + fallback 一次性 proot）
- 批 341 WorkspaceManager：**修复 40** writeTextRootfs 无大小限制（workspace_write_file 工具模型可控 text → 超大落盘）；maxWriteBytes 2MB 上限（a4bfa04）
- 批 342 search 模块：确认（readLimitedBody 2MB 全服务在案）
- 批 343 speech TextChunker：**修复 41** 超长无标点段不硬切 → 单 chunk 超 TTS provider 上限失败/413；按 maxChunkLength 硬切（2561283）
- 批 344 TtsSynthesizer/document：干净（音频缓冲=用户配置 provider，记录不修；4 解析器已审）
- 批 345 模块覆盖确认：web-ui/material3/locale-tui/highlight 全部已覆盖（highlight 47 文件）


## 深挖第十五轮（批 346-352，7 批，2 新修复）
- 批 346 web/Entry.kt + RootfsInstaller 确认：CORS anyHost+Authorization 允许 = 本地服务设计项（token 同源保护，记录）；RootfsInstaller zip-slip 防护在案
- 批 347 gradle 配置 + baselineprofile：干净（纯构建/测试）
- 批 348 data/event + ui/activity：**修复 42** RouteActivity.ShareHandler 只 getStringExtra(EXTRA_STREAM)，系统分享（相册/文件）的 Parcelable Uri 类型不匹配 → null → 分享图片/文件丢失；兼容 Parcelable 读取（40da9cf）；AppEventBus tryEmit 丢弃设计 ✔；ShortcutHandlerActivity 字符串存储兼容 ✔
- 批 349 SafeModeActivity + McpOAuthCallbackActivity：干净
- 批 350 AppEvent + modifier：**修复 43** onClick(enabled=false) 未透传给 clickable → 禁用态按钮仍可点击（重复提交/竞态）；补 enabled 透传（ff55890）
- 批 351 ui/theme + ui/context：干净（ChatFont canonical 逃逸检查在案；15 主题纯 UI）
- 覆盖确认：399 文件全量（ui/pages 96 + components 92 + data/ai 46 + db 29 + theme 15 + hooks 14 + routes 9 + sync 8 + model 8 + repository 7 + datastore 7 + context 6 + task 5 + files 5 + activity 3 + modifier 2 + favorite 2 + export 2 + event 2 + api 2）


## 深挖第十六轮（批 353-360，8 批，收尾全仓库非 Kotlin 面）
- 批 353 AndroidManifest 全量：干净（WorkspaceDocumentsProvider 有 MANAGE_DOCUMENTS 系统级权限保护；RouteActivity SEND/PROCESS_TEXT 预期；McpOAuth deep link state 校验在案；记录：usesCleartextTraffic=true 本地服务设计 + largeHeap）
- 批 354 res/xml：file_paths 已收窄（cache 全目录 + files 仅 upload/images/tool_outputs）；记录：data_extraction_rules 默认全备份（DataStore 含 API key 会上云备份，用户可关 allowBackup）
- 批 355 .github/workflows 全量（5 个）：干净（release.yml 最小权限 contents:write + timeout 40min；记录：workflow_dispatch 默认 tag v1.1.5-turbo 陈旧）
- 批 356 gradle 全量：干净（gradle 9.5 + toolchain 21；记录：sqlite-android -SNAPSHOT 依赖 + mavenLocal + jitpack）
- 批 357 测试代码 8954 行：干净（无真实凭据泄漏，敏感词仅命中测试 fixture 假数据）
- 批 358 skills/docs/package/skills-lock：干净（computedHash 哈希锁定校验在案）
- 批 359 res 资源：干净（strings 纯文案，drawable 矢量）
- 批 360 全仓库文件清单终确认：64 java = MuPDF fitz JNI 第三方绑定（vendored）；43 tokens = highlight 测试 fixture；19 pro = proguard 规则（-dontobfuscate 已知设计 + JWT/serializable keep 合理）；web-ui 前端 113 个 ts/tsx 已全审
- **结论：全仓库代码面（Kotlin 399 + Java 64 + TS/TSX 113 + XML 38 + 构建配置 + 测试）全部深挖完毕，剩余均为记录项（设计选择/隐私选项）**


## 深挖第十七轮（批 361-366，记录项清理，4 新修复）
- **修复 44** WebDav buildUrl 路径段不编码：文件名含空格/中文/#/?/& → URL 非法 → 同步失败；逐段 percent-encode（UTF-8 + %20）（ea17fb4）
- **修复 45** TtsSynthesizer 音频流全量收集无上限：异常 provider 响应 → OOM；8MB 上限抛异常优雅失败（b57d2a5）
- **修复 46** ASR start() 静默失败：焦点被拒（其他应用占用）/ 未配置 provider key 时无任何反馈（录音无声但按钮显示激活）；写入 ASRStatus.Error + errorMessage（UI 已有 toast 机制）（bdae36e）
- **修复 47** WebServerManager.restart() 竞态：stop() 异步 → restart 立即 start() 时旧 server 未停（start 直接 return）→ 只停不启；同步停止后再启动（be3a48d，当前无调用方加固）
- 其他：release.yml dispatch 默认 tag v1.1.5-turbo 陈旧 → 空默认回退 github.ref_name（3cc2262）；模板二次替换确认无风险（Pebble 单次 evaluate）；模型列表为本地数据无加载面


## 深挖第十八轮（批 367-372，测试补强 + 边界复查）
- **修复 48**（边界补全 #42）：多图分享（相册多选）Uri 在 clipData 而非 EXTRA_STREAM → 仍丢图；兜底取 clipData 第一项（4374afa）
- 编译修复 8df2310：ASR controller smart cast（局部变量）+ WebServerManager suspend unregister 同步调用（restart 只停 server，NSD 保持）
- 回归测试 c5252a0：#38 JSON 深度防护（1000 层嵌套 removeElements/mergeCustomBody 不 SOE）
- 回归测试 cf10a2f：TaskConfig 序列化（Timer autoAi round-trip + 缺省 false + CIMonitor + 旧格式兼容）
- 定时器治理：旧版 v1.7.7 指令定时器（7cfe590d/6bce01f8）已取消，唯一活跃 c40845d0（无限巡检 auto_ai=true 新版指令）；行为准则固化进记忆（定时器触发 = 查 CI + 找活干，不许只"待命"）
- **累计修复 48 个真实 bug**；v1.7.8 候选：cf10a2f（44-47 修复 + 编译修复 + 测试 + clipData + ci tag）

## v1.7.4-turbo 发布（2026-08-04，2.5.0/179）
- 包含 3 个修复（v1.7.3 后）：04bbb45 搜索响应 2MB 限量（19 服务）/ 15e37df 翻译输入 200K 截断 / 5f53f30 GitHub API 4MB 限量
- 双绿验证：9e78b88（Unit Tests + Build APK success）

## v1.7.5-turbo 发布（2026-08-04，2.5.1/180，3d71c62 + tag）
- 包含 8 个修复：fde8a25 定时器注入重试 / 0e6432f 用户消息 200K / 2b3574d 撤销恢复附件 / 8425679 图片文件名清洗 / a038e62 JSON 深嵌套 SOE / 2c8487c 导入 5MB / 25bfbdc 悬浮窗权限 / b6a3599 TextArea 200K
- 双绿依据 669c9d1；Release run 30900213796 success（Release #25）；3 个 profileable APK

## v1.7.6-turbo 发布（2026-08-04，2.5.2/181，b491d4c + tag）
- 包含 3 个修复：1130ba8 导出导入 5MB（OOM）/ 0b2fdd8 模型 base64 图片 20MB+4K 采样 / 1ad406a Bitmap import 修复
- 双绿依据 1ad406a；Release run 30902103140 success（Release #26）；3 个 profileable APK

## 自动进度同步（判定驱动机制）

> 时间：2026-08-09 12:28 (UTC+8) | 最新提交：6854ad5（fix #52 throttleLatest）+ 140c5c9（feat #51 截断提示），已 push
> 版本：2.5.4/183（v1.7.8-turbo，Release #28 31293664450 success）｜3 个 profileable APK 已上传
> 判定：[INCOMPLETE] CI 运行中（6854ad5 Unit Tests + Build APK，monitor c6dd088a）——本轮执行：遗留工作区代码收尾提交（见下）
> 修复总数：52（51: finish_reason=length/max_tokens 截断无 UI 提示 → 追加 ⚠️ 提示块；52: 官方 sample 窗口内最后值被静默丢弃 → 流式尾部 chunk 丢失，throttleLatest 替代）
> 活跃：71f9f7a1（5min×∞，auto_ai=true）｜ci_monitor c6dd088a（6854ad5，运行中）
> 其他：本轮收尾了上轮遗留的 2 处工作区代码（截断提示功能 + 流式尾部丢失修复，均审查通过后提交）；Message.kt 格式事故（签名与 require 挤一行）已还原；v1.7.8 发布完成（arm64-v8a 38.7MB / universal 48.8MB / x86_64 39.4MB）


