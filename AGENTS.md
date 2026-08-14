# AGENTS.md — VibeReading (Android)

双语阅读器：导入 TXT 小说，逐章调用 LLM（DeepSeek / OpenAI 兼容 chat completions，SSE 流式）翻译为英文，读者在「中文/英文」双模式下阅读。领域术语与关键决策见 **`CONTEXT.md`（改阅读逻辑前必读）** 与 **`docs/ADR-001-window-layout-model.md`**。

## 构建

- 环境为 Windows，只有 `gradlew.bat`（无 `gradlew` shell 脚本）。
- **JAVA_HOME 需指向 JDK 17**（如 `C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot`）；Android Studio 自带 jbr 为 JDK 25，Kotlin 2.1.0 不识别（`JavaVersion.parse` 抛错）。
- `./gradlew.bat :app:assembleDebug` 构建 debug APK。
- 可用 android-emulator MCP 插件做构建/安装/截图/UI 自动化验证。
- **每次代码改动后必须编译 APK 并安装到模拟器验证**：`./gradlew.bat :app:assembleDebug` → 用 android-emulator MCP 插件 `install_app`（APK 路径 `app/build/outputs/apk/debug/app-debug.apk`）→ `launch_app`。不要只编译不安装。 **在没有用户明确指定做截图，UI 自动化验证等时不要自己主动做多余的操作** 。
- **单测**：`./gradlew.bat :app:testDebugUnitTest`（`app/src/test` 下 Robolectric 4.14 + `@GraphicsMode(NATIVE)` 提供真实换行测量；断言结构化——切段拼接/双语对原子/页高不溢出——不 pin 像素值）。
- 单模块 `:app`，包名 `com.vibereading.app`，minSdk 26，target/compileSdk 35，Kotlin 2.1.0 + Compose BOM 2024.12.01，Gradle 8.11.1。
- 已 git init（无远程）。

## 目录结构

- `app/src/main/java/com/vibereading/app/`
  - `data/` — Room 本地库（`local/entity`、`local/dao`）、`remote/LlmApiService.kt`（SSE 流式翻译）、`repository/`（Book/Chapter/Settings 三个仓库）
  - `domain/` — 纯 Kotlin 模型（`model/`）+ `parser/TxtParser.kt`
  - `ui/` — Compose：`bookshelf`（书架 + `BookCover.kt`）、`reader`（阅读器 + `components/` + `pagination/`）、`settings`、`navigation`、`theme`
    - `reader/` — `ReaderScreen.kt`（阅读器主界面）、`ReaderViewModel.kt`（状态机）；`ReaderPalette.kt`（语义色板）、`ReaderGeometry.kt`（页几何）、`ChapterStatusUi.kt`（状态→颜色）为跨组件共享的单一数据源
    - `reader/pagination/` — `TextPaginator.kt`（`PageStyle`/`FlowItem`/`PageUnit` 共享类型 + `ChapterPaginator` 单章全量排版）、`BookWindow.kt`（章窗口管理器）、`BookPager.kt`（ReaderPager + 卷页位图渲染 + 卷页覆盖层）、`PageCurl.kt`（Legado 移植卷页几何）、`ReaderMetrics.kt`（排版/渲染共享 dp 常量）
  - `VibeReadingApp.kt` — Application，持有 Room 单例
- `app/src/test/java/com/vibereading/app/ui/reader/pagination/` — `ChapterPaginatorTest` / `BookWindowTest`（排版引擎单测）
- `docs/` — ADR（`ADR-001-window-layout-model.md`：章窗口 + 行级排版模型决策记录）
- `reference_code/legado-E/` — Legado 开源阅读器源码，**只作参考，禁止改动**。
- Room schema 输出到 `app/schemas`（ksp 配置 `room.schemaLocation`；当前 exportSchema=false）。

## 架构与分层规则

- 无 DI 框架：`VibeReadingApp` 暴露 `database`，仓库在 `AppNavigation.kt` 里手工构造，经 `viewModel(factory = ...)` 注入。新增依赖沿用此模式。
- ViewModel 统一持有单个 `MutableStateFlow<UiState>` + 只读 `uiState: StateFlow`，用 `_uiState.update { ... }` 更新。
- 数据流：Repository → ViewModel（collect Flow）→ Composable 直接读 `vm.uiState`。
- 章节翻译走 `LlmApiService.translateStream()`，返回 `Flow<TranslationEvent>`（Status/Chunk/Progress/Done/Error），ReaderViewModel 里维护 `translateJob: Job?`。
- **分页渲染（章窗口模型）**：`ReaderScreen` 构造 `BookWindow`（key 含 `isPagerMode`/页几何/样式——**跨模式切换或改边距必须重建窗口**），窗口 = 当前章±1；`HorizontalPager` 索引空间 = `window.windowPages`（扁平章页列表）；跨章续翻在窗口边界重建、目录/滑块远跳重建窗口。样式/模式/边距变更后恢复到「章 + 章内页」。

## 领域规则（关键 gotchas）

- **章节状态**：`0=待翻译 1=翻译中 2=已翻译 -1=失败 3=过长`。一律用 `Chapter` companion 常量（`STATUS_*`），不要写魔法数字。
- **双语段落**：翻译时每个段落必须以 `[1] [2] ...` 标记开头，英文译文必须保留完全相同的标记与原文一一对应（`SYSTEM_PROMPT` 和 `buildUserPrompt` 在 LlmApiService.kt）。改动翻译 prompt / 段落匹配逻辑时，保证标记对齐不变。`parseBilingualParagraphs()` 是 cnText/enText 的**唯一数据源**（`BookWindow.buildChapterItems()` 用它构造 `FlowItem.Para`），不要再用 `chapter.content.split("\n\n")` 独立拆分中文——两套索引不对齐会导致 cnText 显示整章文本。
- **阅读模式**：`zh`（仅原文）/ `en`（英译为主，点击段落尾部原文气泡弹窗查看中文原文）。翻页类型五枚举：`scroll` 上下（连续跨章滚动）/ `pager` 平移 / `cover` 覆盖 / `no_anim` 无动画 / `simulation` 仿真（卷页动画，拖拽跟手 + 点按自动卷页）。
- **整页排版（章窗口 + 行级模型）**：`pagination/TextPaginator.kt` 的 `ChapterPaginator` 用 `TextMeasurer` 按**真实页宽**（`Constraints(maxWidth=contentWidth)`）逐章全量排版；zh 段落可跨页（`splitLayout` 按行切段，**续段只 trim 换行符不 trim 空格，否则丢字**）；en 双语对原子化不可拆（单对超高按行切分、首片段 `pairHead` 可显示原文气泡，续段无气泡）；**底部对齐** `buildPage` 按页分配 slack，**末页豁免**（`layoutAll` 末尾清零末页 `lineHeightExtraPx`）；`PageUnit` 挂载 `TextLayoutResult`（渲染层 `Text` 与卷页位图共用同一样式）。**原文气泡与弹窗是视觉叠加层（`Popup` composable），不参与排版测量，不触发重排**。改排版/弹窗逻辑时保证双语对对齐与 `pairHead` 标记不破坏。**排版内容区宽高用 `floor()` 取整**对齐 Compose 整像素布局（dp→px 可能带小数，不取整则底行被裁）。**末段段距消除**：渲染层与卷页位图需同步 `buildPage` 的 `realUsed = used - paragraphSpacingPx`，末段（`isLastPara`）不渲染 `paragraphSpacingPx`，否则高度溢出底行被裁。**`PageRenderer` 用自定义 `Layout`（无界高度测量）替代 `Column`**，允许内容微溢至 Box padding 区域，避免 Column 以剩余高度=0 截断末子元素。
- **仿真翻页（simulation）**：手势在 `ReaderScreen` 的 `pointerInput` 中实现——拖拽跟手（`SimFlipState.touchX/Y` 实时更新、抬手越阈值翻页否则回弹）+ 点按自动卷页（`startSimFlip` 360ms 插值）；卷页位图 `renderPageBitmap` 用 `android.text.StaticLayout` + **真实 px 字号**（`fontSize.toPx()`，勿把 sp 数值当 px——否则位图小字与真实页重叠）绘制，与真实页视觉一致。**边到边模式**：手势坐标 `downY/focusY` 需减去 `statusBarPx` 偏移，内容区高度 `contentH` 需扣除 `statusBarPx + navBarPx`；卷页覆盖层与 `PageRenderer` 均用 `.statusBarsPadding().navigationBarsPadding()` 扣除系统栏后再留用户边距；`pointerInput` key 需含 `statusBarPx, navBarPx`（系统栏变化时手势参数重建）。
- **设置入口边界**（CONTEXT.md 决策）：字体/字号/间距/翻页类型/背景的唯一入口是阅读器内设置面板；面板按 Legado 信息密度组织，**页边距/字间距/首行缩进/两端对齐在「高级选项」折叠组**；字体 = 「系统字体」+「导入字体…」（SAF，content:// URI 持久化 + takePersistableUriPermission），`fontFamily` 三系统字体选择已从 UI 移除。设置页为单页分组列表（主题设置 / 阅读设置 / 翻译设置 / 关于），主题选择（跟随系统/浅色/深色 × 原木/青简）在「主题设置」分组。
- **点按交互耦合**：分页类模式（pager/cover/no_anim/simulation）左右 1/3 点按翻**页**（跨章自动续翻），中间 1/3 开关菜单；`scroll` 模式三段点按翻**章**（点击段落尾部原文气泡弹窗查看中文优先）；仿真模式另有拖拽卷页手势（见上）。
- **边到边模式**：排版内容区 = `floor(screenHeight - statusBar - navBar - paddingV*2)`；渲染层 `PageRenderer`/`CurlOverlay` 用 `.statusBarsPadding().navigationBarsPadding()` + 用户边距；滚动模式 `LazyColumn` 的 `contentPadding` = 系统栏 + 用户边距；**排版/渲染/手势三处系统栏扣除必须一致**，否则内容区错位导致底行被裁或手势偏移。
- **进度持久化**：分页模式「章 + 章内页」（`books.lastReadPage`，Room v3 `MIGRATION_2_3`）；滚动模式 page 恒 0。
- 目录唯一入口在阅读器底部栏中央；顶栏不再有目录按钮。
- 夜间模式独立于背景色预设，翻转时不改背景设置本身。
- **主题系统**：全局主题由 `ThemeSettings`（`ThemeMode` SYSTEM/LIGHT/DARK × `AppAccent` VIBE/WEREAD，DataStore 持久化，旧 key "theme" 自动迁移为 accent）驱动，`VibeReadingTheme` 动态切换；阅读器页面背景/文字色由 `ReadingSettings` + `ReaderBgPresets` 独立控制，不依赖全局主题。
- **书架**：`BookshelfViewModel` 内做排序（最近阅读/书名/上传时间）与搜索过滤（Repository 只给 `getShelfItems()` 原始流）；布局（列表/网格）、排序经 `SettingsRepository` 持久化；封面由 `BookCover.kt` 按书名 hash 生成渐变默认封面。

## 代码复用与内聚（高内聚低耦合 / 一处修改，到处同步）

- **高内聚低耦合**：同一概念（配色、几何、常量、状态映射）只在一处定义，其余代码引用——改一处即全局生效，避免「改 A 忘了改 B」导致底行被裁/气泡错位/亮暗不一致。
- **单一数据源（Single Source of Truth）**：阅读器跨组件共享概念集中在以下文件，新增同类需求先查此处，禁止另起炉灶硬编码：
  - `ui/reader/ReaderPalette.kt` — `ReaderPalette` 语义色板（`ReaderPalette.of(isDark)` 集中亮/暗三元），正文/标题/气泡/弹窗文字色共用。
  - `ui/reader/ChapterStatusUi.kt` — `chapterStatusColor(status)` 章节状态→颜色映射（顶栏圆点/目录/徽章共用）。
  - `ui/reader/ReaderGeometry.kt` — `ReaderPageGeometry` 页几何（`of(...)` 集中「内容区 = 屏 − 系统栏 − 用户边距」公式），排版/渲染/手势三处共用。
  - `ui/reader/pagination/ReaderMetrics.kt` — 排版/渲染共享 dp 常量（标题顶距/卷名间距/双语 padding/气泡尺寸），排版器（px）、卷页位图（px）、渲染组件（dp）三处引用同一来源。
- **能抽象的参数尽量抽象**：`PageStyle.of(readingSettings, density)` 统一样式构造（分页与滚动共用口径）；`renderPageBitmap` 几何/配色收敛为 `geometry` + `palette` 对象，避免十几枚平铺参数。
- **复用而非复制**：滚动模式 `components/BilingualParagraph` 与分页模式 `PageBilingualParagraph` 已合并为单一 `BilingualParagraph`（可选参 `lineHeightExtraPx`/`pairHead`/`showSpacer` 区分场景）；删除死代码（如未被调用的 `ReaderStatusBadge`）。

## 代码约定

- 注释与 UI 字符串以中文为主，新增代码保持一致。
- 文案/术语与 `CONTEXT.md` 对齐（书籍/章节/双语段落/翻页类型/章窗口/底部对齐等），新增概念先更新术语表。
- `ReadingSettings` 用字符串常量标记翻页模式（`FLIP_*` companion 常量）、整数标记标题模式（`TITLE_MODE_*`）；`LlmSettings` 默认 apiBase 为 DeepSeek，apiKey 存于 DataStore。
- 单测断言结构化（行数/切段范围/原子性），不 pin 像素值（Robolectric NATIVE 跨版本换行可能微差）。
