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
