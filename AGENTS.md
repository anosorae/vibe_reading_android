# AGENTS.md — VibeReading (Android)

VibeReading 是一个双语 TXT 阅读器：导入小说后，逐章调用 LLM（DeepSeek / OpenAI 兼容 chat completions，SSE 流式）生成英文译文，读者在「中文/英文」模式下阅读。阅读进度使用 **章节 ID + 原文字符 offset**，不保存运行时页码。领域术语和用户可见边界见 **`CONTEXT.md`**；分页窗口的历史决策见 **`docs/ADR-001-window-layout-model.md`**。

## 构建与验证

- 环境为 Windows，仓库使用 `gradlew.bat`，没有 `gradlew` shell 脚本。
- **JAVA_HOME 必须指向 JDK 17**，例如 `C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot`；Android Studio 自带 JBR/JDK 25 不适配当前 Kotlin/Gradle 配置。
- 构建 debug APK：`./gradlew.bat :app:assembleDebug`。
- 单元测试：`./gradlew.bat :app:testDebugUnitTest`。
- 日志约定检查（找出「catch/runCatching 吞异常且没落日志」的疑似点，须人工甄别合法回退用法）：`python tools/check_log_convention.py`。改动新增 catch 分支后跑一次，确认没有遗漏 AppLog 落日志。
- 词典资产已入库（`app/src/main/assets/dict/ecdict.dict`），日常构建无需重建；如需重建：`python tools/build_dict_db.py`（从 ECDICT 基础版 CSV 裁剪约 50 万词条 → 四列 SQLite → gzip 预压缩，约 18.7MB；`--keep-all` 保留全量 76 万）。asset 扩展名 `.dict` 是刻意的：AGP 会把 `.gz` 资产自动解压，`.dict` + `noCompress` 才能原样打包。
- 每次代码改动后都必须完成：构建 APK → 按 `app/build/outputs/apk/debug/output-metadata.json` 选择设备 ABI → 安装 → 启动。x86_64 模拟器通常使用 `app-x86_64-debug.apk`，没有匹配设备时使用 `app-universal-debug.apk`。
- Android 验证优先使用 android-emulator MCP：`android_preflight` → `android_discover_project` → `android_build_and_run` 或 `build_app` + `install_app` + `launch_app`。除非用户明确要求，不主动截图或执行额外 UI 自动化。
- 单测使用 Robolectric 4.14/NATIVE 真实换行测量；断言结构化结果（offset 范围、切段拼接、双语原子性、页高和位置映射），不要固定易变的像素值。
- 项目为单模块 `:app`，包名 `com.vibereading.app`，minSdk 26，target/compileSdk 35，Kotlin 2.1.0，Compose BOM 2024.12.01，Gradle 8.11.1。
- Room schema 通过 KSP 输出到 `app/schemas`；当前数据库版本为 8，三个实体 `BookEntity`/`ChapterEntity`/`LlmProfileEntity`，迁移链 `MIGRATION_2_3` … `MIGRATION_7_8` 全部手写注册（v6→v7 新增 `llm_profiles` 表，v7→v8 增 `temperature`/`topP` 列）。实体模型使用 `lastReadOffset`；`chapters.translationRunId` 提供翻译任务的数据库级 stale 防护；书架「已译章节数」由 chapters 表 DONE 状态实时派生，`books.translatedChapters` 冗余列已移除。

## 目录结构

- `app/src/main/java/com/vibereading/app/`
  - `data/` — Room 本地库：`local/entity`（`BookEntity`/`ChapterEntity`/`LlmProfileEntity`）、`local/dao`（`BookDao`/`ChapterDao`/`LlmProfileDao`，含 `AppDatabase` 迁移链）、`remote/`（`TranslationService` 接口 + `LlmApiService` SSE 实现）、`repository/`（`BookRepository`/`ChapterRepository`/`SettingsRepository`/`LlmProfileRepository`）、`dict/`（`DictDatabase` 内嵌词典只读访问）
  - `domain/model/` — 纯 Kotlin 领域模型：`Book`、`BookShelfItem`、`Chapter`、`ReadingPosition`、`ReadingSettings`（含 `LlmSettings`，两者同文件）、`LlmProfile`、`ThemeSettings`、`DictEntry`、`WordExplanation`
  - `domain/parser/` — 纯 Kotlin 解析器，包括 `TxtParser`、`ReadingContentParser`；负责保留原文段落的 UTF-16 起止 offset
  - `ui/` — Compose：`bookshelf`（书架和封面）、`reader`（阅读器及共享组件）、`settings`（全局设置，含调试/日志入口）、`log`（日志查看器）、`navigation`、`theme`
    - `reader/ReaderScreen.kt` — 阅读器容器、五种翻页交互、生命周期 flush、滚动/分页接线（页面协调）
    - `reader/ReaderScroll.kt` — 滚动模式内容项（`ScrollItem`/`buildScrollChunks`/`indexInChunks`）与 `ScrollReader` 列表
    - `reader/ReaderChrome.kt` — 顶栏/底栏/翻译状态面板/章节标签等 chrome 组件
    - `reader/ReaderViewModel.kt` — 初始化恢复、阅读位置状态、串行进度写入、翻译协调器接线、词典查词入口
    - `reader/TranslationCoordinator.kt` — 翻译状态机：单任务运行 + `translationRunId` 数据库级 stale 防护
    - `reader/components/ReadingContentRenderer.kt` — 分页与滚动共享的章节标题/正文/双语内容渲染
    - `reader/components/BilingualParagraph.kt` — 英文译文、原文气泡和 Popup
    - `reader/components/TextSelection.kt` — 长按选词：`TextSelectionState`、`SelectableParagraphText`、`findWordBoundary`（BreakIterator 分词）
    - `reader/components/SelectionToolbar.kt` — 选词工具栏（查词/复制/解释）
    - `reader/components/DictPopup.kt` — 词典查询结果弹窗
    - `reader/components/ExplainPopup.kt` — LLM 单词解释结果弹窗（`WordExplanation`）
    - `reader/components/ReaderSettingsSheet.kt` — 阅读器设置面板（字体/字号/行距/段距/翻页/背景/高级排版）
    - `reader/components/LlmSettingsSheet.kt` — 翻译配置面板（多 LLM 配置档案 CRUD + 连接测试）
    - `reader/components/CatalogBottomSheet.kt` — 章节目录底部抽屉
    - `reader/components/PageInfoOverlays.kt` — 页码/进度浮层
    - `reader/content/ReadingContent.kt` — 统一章节内容结构（`ReadingContent.fromChapter()`），分页与滚动的共同数据源
    - `reader/ReaderPalette.kt` — 亮/暗语义色板
    - `reader/ReaderGeometry.kt` — 页面几何和系统栏扣除公式
    - `reader/ChapterStatusUi.kt` — 章节状态到颜色映射
    - `reader/pagination/TextPaginator.kt` — `PageStyle`、`FlowItem`、`PageUnit`、`TextPage`、`ChapterPaginator`；按当前样式排版并支持 offset→页映射
    - `reader/pagination/BookWindow.kt` — 当前章 ±1 的排版窗口和扁平页索引
    - `reader/pagination/BookPager.kt` — HorizontalPager、PageRenderer、覆盖/卷页位图
    - `reader/pagination/PageCurl.kt` — Legado 仿真卷页几何移植
    - `reader/pagination/ReaderMetrics.kt` — 排版、标题、双语 padding、气泡尺寸共享常量
  - `log/` — 三层日志：`AppLog`（内存环形缓冲，最新在前上限 100）、`LogUtils`+`AsyncFileHandler`（`java.util.logging` 异步写 `<externalCacheDir>/logs/`）、`CrashHandler`（全局未捕获异常落盘 `<externalCacheDir>/crash/`，内含 `CrashMark` 标志位）、`CrashLogFiles`（崩溃文件列表/读取/删除）、`LogContext`（进程级 Context + 单线程后台执行器）
  - `MainActivity.kt` — 唯一 Activity，`enableEdgeToEdge` + `VibeReadingTheme { AppNavigation() }`
  - `ui/log/LogViewerScreen.kt` — 日志查看器：运行日志/崩溃日志双 Tab，清除与复制
  - `VibeReadingApp.kt` — Application，持有 Room 单例；`onCreate` 中先装 `CrashHandler` 再 `AppLog.init`/`LogUtils.init`/`logDeviceInfo`
- `app/src/test/java/` — LLM/SSE、迁移、DAO、设置仓库、解析器、阅读位置、分页窗口、仿真/手势、位图渲染、选词分词、词典查询与翻译状态机单测
- `docs/` — ADR 文档
- `reference_code/legado-E/` — Legado 开源阅读器参考源码，**只读，禁止修改**。
- `tools/build_dict_db.py` — 词典库构建脚本（CSV → 四列 SQLite → gzip 资产）
- `app/proguard-rules.pro` — R8 保留规则（release `minifyEnabled`）；keep Gson 模型 `data.remote.**` 与 `WordExplanation`、Room 实体 `data.local.entity.**`，dontwarn OkHttp/okio。新增 Gson 反序列化的数据类必须在此加 keep 规则。

## 架构与分层规则

- 无 DI 框架：`VibeReadingApp` 暴露数据库，仓库在 `AppNavigation.kt` 手工构造，经 `viewModel(factory = ...)` 注入。新增依赖沿用此模式。
- ViewModel 使用单个 `MutableStateFlow<UiState>` 作为可变状态源，并通过 `_uiState.update { ... }` 更新；Composable 直接收集只读 `StateFlow`。
- 数据流为 `Repository → ViewModel → Composable`。UI 不直接访问 Room/DAO。
- 阅读位置统一使用 `ReadingPosition(chapterId, offset)`。`Book.lastReadChapterId` 和 `Book.lastReadOffset` 是持久化恢复数据；页码只能是当前 `BookWindow`/`ChapterPaginator` 的派生状态。
- 初始恢复必须是一次性、原子、无副作用的：先读取 Book 位置快照，再等待章节列表；不要通过会写库的普通导航函数恢复默认位置。后续章节 Flow 只刷新当前章节。
- 位置变化统一经过 ViewModel 的进度入口；分页从 `window.offsetOfPage()` 取 offset，滚动从可见 `ScrollItem` 取 offset。写入必须串行，退出前调用 `flushProgress()`。
- 翻译走 `TranslationService` 接口（`LlmApiService` 实现，方法：`translateStream`/`testConnection`/`truncateMiddle`）的 `translateStream()`，返回 `Flow<TranslationEvent>`：`Started/Thinking/Chunk/Progress/Done/Error`。`Thinking` 只接收 reasoning 字段，`Chunk` 只接收正式 content，`Done` 只持久化完整正式译文。LLM 配置存于 `llm_profiles` 表（多档案，`LlmProfileRepository` 管理，`isActive` 标记当前生效档案）；`LlmSettings` 是翻译/连接测试使用的运行时子集。
- 翻译状态机集中在 `TranslationCoordinator`（注入 `TranslationService`）：开始翻译时写入 `chapters.translationRunId`，完成/失败/取消必须带同一 runId 才落库（`ChapterDao.*TranslationRun`），旧任务无法污染新任务。

## 阅读内容与五种翻页模式

- `ReadingContentParser` 是原文段落和 `[N]` 翻译标记的领域级单一数据源；不得在其他位置通过 `split("\n\n")` 另建段落索引。展示文本可以 trim，但 `startOffset/endOffset` 必须仍指向原始章节字符串。
- `ReadingContent.fromChapter()` 生成分页和滚动共同使用的内容项，包含章节标题、卷名、原文段落、译文和原文范围。
- 五种翻页类型只负责移动方式，不负责重新定义内容：
  - `scroll`：统一内容项的 LazyColumn 连续滚动。
  - `pager`：HorizontalPager 平移。
  - `cover`：同一 Pager 页施加覆盖滑入。
  - `no_anim`：同一 Pager 页瞬时切换。
  - `simulation`：同一 Pager 页使用 PageCurl 仿真卷页。
- 分页模式的 `BookWindow` 是当前章 ±1 的全量排版窗口；打开书籍/切章首帧只同步排版中心章（`recenterSync(includeNeighbors = false)`），±1 章由 `paginateNeighbors` 后台排版后幂等扩展窗口并保持当前视觉页（`hasNeighbors` 避免重复排版）。窗口页索引是运行时扁平索引，不是持久化进度。窗口重建、样式变化、模式切换和译文更新必须按 `chapterId + offset` 恢复。
- 滚动内容 `scrollChunks` 惰性构建：分页模式不解析全书（打开书籍提速），首次进入滚动模式时构建并跨模式缓存，章节内容变化时重置；滚动模式构建期间显示加载指示。
- 分页 `PageUnit`/`TextPage` 必须携带来源段落范围；长中文段按行切分不能丢字符；英文双语对默认不可拆，超高时按英文行切分但所有片段仍绑定同一中文原文范围。
- 标题、正文、段距、双语气泡必须由 `ReadingContentRenderer`、`PageStyle`、`ReaderMetrics` 和 `BilingualParagraph` 共享；不要为滚动或分页新增独立标题字号、段距或段落拆分逻辑。

## 领域规则与关键 gotchas

- 章节状态只能使用 `Chapter.STATUS_*` 常量：`PENDING=0`、`IN_PROGRESS=1`、`DONE=2`、`FAILED=-1`、`TOO_LONG=3`，禁止魔法数字。
- 双语译文必须保留 `[1] [2] ...` 标记，并与原文段落一一对应。无效标记不能静默绑定到其他段落；修改 prompt/解析器时必须更新 offset 对齐测试。
- `ReadingPosition.offset` 是非负 UTF-16 code-unit 偏移，使用半开区间语义；offset 超过章节长度时按当前章节内容长度规范化。
- 运行时页码变化不是数据迁移事件。不要把 page index 写回 `BookEntity`、DAO 或 Room；Room 进度 SQL 只更新章节 ID、原文 offset 和时间。
- 原文气泡和 Popup 是视觉叠加层，不参与分页测量、不触发重排；`pairHead` 只控制首片段气泡。
- 排版内容区宽高必须按整像素对齐；PageRenderer、卷页位图和滚动 content padding 的系统栏扣除必须与 `ReaderPageGeometry` 一致。
- `PageRenderer` 使用无界高度的自定义 `Layout`，不要改回会以剩余高度截断末子元素的 `Column`。末段不绘制段距，必须和 `ChapterPaginator.buildPage()` 的 `realUsed` 一致。
- 仿真翻页的手势 key 必须包含系统栏尺寸；手势坐标需要按边到边内容公式校准，卷页位图字体使用真实 px 尺寸。
- 设置入口遵循 `CONTEXT.md`：阅读器内唯一入口；高级选项包含页边距、字间距、首行缩进、两端对齐；字体只有系统字体和 SAF 导入字体。
- 目录只从底部栏中央进入；顶栏不增加目录按钮。
- 全局主题由 `ThemeSettings` 驱动，阅读器背景/正文颜色由 `ReadingSettings`、`ReaderBgPresets`、`ReaderPalette` 独立控制。
- SSE 必须持续拼接所有 `content` chunk 直到 `[DONE]`；`finish_reason="length"`、网络异常、解析异常、取消都不能保存为成功译文；流式状态栏正文不得设置固定 `maxLines` 或省略号。
- 书架「已译章节数」只能从 `chapters` 表 DONE 状态派生（`BookDao.getBooksWithProgress()` 子查询），`books.translatedChapters` 缓存列已移除，禁止恢复。
- 翻译终态写库必须走 `ChapterRepository.startTranslation/completeTranslation/failTranslation/cancelTranslation`（内部带 `translationRunId` 匹配）；切换阅读章节不应取消合法后台任务，只有开启新任务才替换旧任务。
- 长按选词是瞬时 UI 交互（`TextSelectionState`）：翻页、滚动、切章/切模式、开浮层时清除；长按后的下一次点击只清选区（或关词典弹窗）不翻页。选词用 `SelectableParagraphText`（BreakIterator 分词 + 背景 SpanStyle 高亮，背景不参与测量不触发重排），普通点击不消费事件，外层翻页手势靠 `isConsumed` 跳过。
- 词典查询走 `DictDatabase.lookup`（IO 线程，`ReaderViewModel.lookupDictWord` 入口）；词条小写存储 + 查询小写归一，`WHERE word = ?` 命中 BINARY 主键。资产 `assets/dict/ecdict.dict` 是 gzip 预压缩 SQLite（AGP 会解压 `.gz`，故用 `.dict` + `noCompress`），首次查词解压到 `databases/ecdict.db`（按 gz 头携带的期望尺寸判断是否需要更新）。

## 复用与内聚

- 共享概念只能有一个定义：颜色用 `ReaderPalette`，几何用 `ReaderPageGeometry`，排版常量用 `ReaderMetrics`，章节状态颜色用 `chapterStatusColor`，内容样式用 `PageStyle`，内容结构用 `ReadingContent`，位置用 `ReadingPosition`，翻译状态机用 `TranslationCoordinator`，翻译网络服务用 `TranslationService`，选词状态与分词用 `TextSelectionState`/`findWordBoundary`，词典访问用 `DictDatabase`，日志用 `AppLog`（内存）/`LogUtils`（文件）/`CrashHandler`（崩溃）。
- 错误路径（`catch` / `Result.exceptionOrNull()`）除了写 UI 状态外，应调用 `AppLog.put(msg, throwable)` 落日志，便于用户在「设置 → 调试 → 日志」中定位 bug；不要散落 `android.util.Log` 或 `printStackTrace`。
- 修改跨组件概念前先搜索其单一数据源；不要在组件内复制常量或重新解析章节文本。
- 共享 Composable 优先复用 `ReadingChapterTitle`、`ReadingParagraphItem`、`BilingualParagraph`；新增视觉差异应通过参数表达，而不是复制组件。

## 代码约定

- 注释和 UI 字符串以中文为主，新增代码保持一致。
- 文案和术语必须与 `CONTEXT.md` 一致；新增概念先更新术语表。
- `ReadingSettings` 使用 `FLIP_*` 字符串常量和 `TITLE_MODE_*` 整数常量；`LlmSettings`（同文件）默认 API base 为 DeepSeek、模型 `deepseek-v4-flash`，API key 与配置档案存于 `llm_profiles` 表（DataStore 旧值由 `AppNavigation` 启动时 `ensureDefaultProfile()` 一次性迁移），思考模式文案使用通用模型措辞。`nightMode` 不属于 `ReadingSettings`，而是独立存于 `SettingsRepository`（DataStore key）并映射到 `ReaderUiState.nightMode`。
- 单元测试断言结构化结果，不 pin 跨版本易变的像素值；解析器测试必须覆盖原文范围、空行/逐行输入、标记对齐和无效标记。
- `reference_code/legado-E/` 只作对照，禁止改动。
