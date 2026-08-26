# CONTEXT.md — Vibe Reading 领域术语表

本文件记录 VibeReading 的领域词汇、用户可见行为和跨模块边界。实现细节与架构约束见 **`AGENTS.md`**；分页窗口的历史决策见 **`docs/ADR-001-window-layout-model.md`**。修改阅读逻辑前必须同时阅读这三处文档。

## 术语

| 术语 | 定义 | 反义/相关 |
|---|---|---|
| **书籍 (Book)** | 一次 TXT 或 EPUB 导入形成的阅读单元，含若干章节和书架元数据 | 章节 |
| **书籍格式 (Book Format)** | 书籍的来源格式（`txt`/`epub`），只决定导入解析方式和封面来源，不影响阅读器内部行为与阅读体验 | 内容还原度 |
| **书籍原文语言 (Book Source Language)** | 书籍原始文字的语言（`zh`/`en`）；导入时按首章抽样 CJK 占比判定、可按书修正，决定翻译方向与段落插槽；独立于显示模式 | 阅读模式 |
| **EPUB 书籍 (EPUB Book)** | 以 EPUB 容器导入的书籍；导入时一次性转换为与 TXT 相同契约的章节纯文本入库，不保留 `.epub` 原文件；加密（DRM）EPUB 不支持，导入时报明确错误 | 按需解压原文件 |
| **章节 (Chapter)** | 书籍的最小内容单元，有原文、译文、翻译状态和失败原因 | 书籍 |
| **原文偏移 (Source Offset)** | 章节原文字符串中的 UTF-16 字符偏移，使用半开区间坐标；是跨重排、跨阅读模式恢复位置的稳定锚点 | 运行时页码 |
| **阅读位置 (Reading Position)** | `chapterId + sourceOffset`；唯一持久化的阅读进度。章节不存在时回退到首章 offset=0 | 页码 |
| **运行时页码 (Derived Page Index)** | 当前字体、字号、页边距、屏幕尺寸、阅读模式和内容排版下，由 `sourceOffset` 动态反查得到的章内页索引；不持久化 | 原文偏移 |
| **原文段落 (Source Paragraph)** | 章节原文中的一段文字，带 `startOffset/endOffset` 精确范围；展示文本可清理空白，但不能改变范围 | 双语段落 |
| **双语段落 (Bilingual Paragraph)** | 同一段落原文与译文的跨语言配对，方向由书籍原文语言决定；英文侧段尾可显示中文气泡 | 单语段落 |
| **中文气泡 (Chinese Bubble)** | 正文为英文时段尾的半透明色块（18×6dp），点击后弹窗显示该段中文侧文本（中文书=中文原文，英文书=中文译文）；视觉叠加层，不参与排版测量 | 弹窗 |
| **插图 (Illustration)** | 独立成段的正文图片内容单元；双语两侧共用同一张图，无原文气泡，不参与翻译、选词和两端对齐 | 行内图片、富文本 |
| **插图链接 (Image Link)** | 插图在章节原文中的唯一文本表示：类 markdown 图片链接，携带包内资源键和导入期解码的像素尺寸；分页、渲染、全屏预览均由它派生，不存在独立的图片尺寸数据源 | 独立图片表、运行时探测 |
| **书籍封面 (Book Cover)** | 书架展示的封面图：EPUB 取内置封面落盘存储，TXT 或封面缺失时回退程序化渐变占位 | 格式徽标（已否决） |
| **统一章节内容 (Reading Content)** | 分页和滚动共同消费的章节标题、卷名、原文段落、译文和原文范围结构；由 `ReadingContent` 表示 | 分叉渲染数据 |
| **统一阅读项 (Reading Item)** | 章节标题项或带原文范围的段落项；滚动列表按项定位，分页器按项排版为页 | 整章嵌套内容 |
| **阅读模式 (Reading Mode)** | `zh`（中文侧正文）或 `en`（英文侧正文，段尾中文气泡）；默认=书籍原文语言，按书持久化 | 书籍原文语言 |
| **翻页类型 (Page Flip Mode)** | `scroll` 上下连续滚动、`pager` 平移、`cover` 覆盖、`no_anim` 无动画、`simulation` 仿真卷页 | — |
| **整页排版 (Page Pagination)** | 按当前页面几何把统一章节内容流排成页；标题和段落来自同一内容模型，页只是运行时布局结果 | 连续滚动 |
| **章窗口 (Chapter Window)** | 分页模式下当前章及前后邻章的排版窗口；索引是窗口内扁平页列表，不是全书全局页号 | 全书一次性排版 |
| **偏移到页映射 (Offset-to-Page Mapping)** | `ChapterPaginator.pageForOffset()` / `BookWindow.indexOf(chapterId, sourceOffset)` 根据当前排版把稳定原文位置映射到运行时页 | 固定页码恢复 |
| **底部对齐 (Bottom Justify)** | 非末页把剩余高度分配到各行使末行沉底；末页豁免，避免短页行距被拉大 | 顶部对齐 |
| **首行缩进 (First-line Indent)** | 段落首行缩进（em，默认 2em 约等于两全角空格） | 顶格 |
| **两端对齐 (Justify)** | 段落左右两端对齐（默认开启，接近 Legado `textFullJustify`） | 左对齐 |
| **页边距 (Page Margin)** | 内容区与屏幕边缘的用户留白；分页、滚动和五种翻页类型共用 | — |
| **自定义字体 (Custom Font)** | 通过 SAF 导入 TTF/OTF，持久化 `content://` URI 并取得持久权限 | 系统字体 |
| **边到边 (Edge-to-Edge)** | 内容延伸到系统栏区域；排版、渲染、滚动 padding 和仿真手势统一扣除状态栏/导航栏 | 系统栏 |
| **夜间模式 (Night Mode)** | 阅读背景在当前预设与深夜背景之间的快捷切换，独立于全局主题和背景设置；不归属 `ReadingSettings`，存于 `SettingsRepository`（DataStore）并映射到 `ReaderUiState.nightMode` | 背景预设 |
| **背景色 (Background Preset)** | 阅读器背景预设：暖白、米色、青绿、灰米、深夜；不等同于全局主题 | 夜间模式 |
| **主题模式 (Theme Mode)** | 全局主题：跟随系统/浅色/深色 × 原木/青简；阅读页面配色独立控制 | 阅读背景 |
| **章节滑块 (Chapter Slider)** | 阅读器底部控件，显示章节位置并支持拖动跨章跳转；跳转到目标章节原文起点 | 上一章/下一章 |
| **单手模式 (One-hand Mode)** | 分页类模式开启后左右两侧均翻下一页；滚动模式没有页概念，不生效 | 三段点按 |
| **章节状态 (Chapter Status)** | `0=待翻译 1=翻译中 2=已翻译 -1=失败 3=过长`；Room 持久化的粗粒度状态 | 翻译阶段 |
| **翻译阶段 (Translation Phase)** | 一次翻译任务的运行时阶段：准备、等待首 token、思考、流式输出、失败、取消；不写入 `Chapter.status` | 章节状态 |
| **翻译任务代际 (Translation Run)** | 一次翻译任务的自增 `translationRunId`；开始翻译写入 chapters 表，完成/失败/取消必须带同一 runId 才落库，旧任务不能污染新任务 | 内存代际（非持久化） |
| **已译章节数 (Translated Count)** | 一本书的 DONE 状态章节数，由 chapters 表实时派生（书架展示），不持久化缓存 | 缓存冗余列（已移除） |
| **思考过程 (Thinking / Reasoning)** | SSE 中的 `reasoning_content`、`reasoning` 或 `thinking` 增量；只在开启思考模式时展示，不混入正式译文 | 正式回复 |
| **正式回复 (Final Content)** | SSE `delta.content` 按顺序拼接形成的正式译文；`Done.text` 才能持久化 | 思考过程 |
| **流式翻译状态栏 (Streaming Status Bar)** | 翻译面板顶部固定显示阶段和字符数；思考内容与正式回复在下方独立滚动 | 流式内容区 |
| **输出长度截断 (Length Truncation)** | `finish_reason="length"` 表示模型未完整生成；即使收到 `[DONE]` 也必须失败，不能保存为已翻译 | 正常停止 |
| **选词 (Word Selection)** | 长按正文触发：`TextLayoutResult.getOffsetForPosition` 命中字符 → `BreakIterator` 分词 → 高亮选区 + 工具栏（查词/复制）。瞬时 UI 状态，翻页/滚动/切章/开浮层时清除 | 选区高亮 |
| **选择手柄 (Selection Handle)** | 选区两端显示的竖线+圆点拖拽控件（iOS 风格）：START 在手选区首字符左边缘，END 在末字符右边缘。拖拽手柄扩展或收缩选区范围，松开弹出工具栏 | 选区扩展 |
| **选区扩展 (Selection Expansion)** | 拖拽选择手柄将单词级选区扩展为多词短语级选区；选区始终限制在单一段落内，不跨段 | 单次选词 |
| **手柄反转 (Handle Reverse)** | 拖拽手柄越过对面手柄时自动交换角色：START 变为 END、END 变为 START，原拖拽手势不中断，用户拖拽的始终是活动端 | 固定手柄 |
| **查词 (Dict Lookup)** | 工具栏「查词」→ 离线查询内嵌 ECDICT 库 → 弹窗显示音标/词性/中文释义；未收录时提示（中文词提示"仅支持英文查词"） | 选词 |
| **词典库 (Dict Database)** | 内嵌 ECDICT 精简版 SQLite（约 50 万常用词条，只含 word/phonetic/translation/pos 四列）；构建时 gzip 预压缩为 `assets/dict/ecdict.dict`，首次查词解压到内部存储 | 在线词典 |
| **单词解释 (Word Explanation)** | 选词工具栏「解释」按钮调用 LLM（非流式）返回 `WordExplanation`（音标/词性/释义/例句等 JSON）；与离线「查词」并列，依赖已配置的 API Key | 查词 |
| **LLM 配置档案 (LLM Profile)** | `llm_profiles` 表中的翻译服务配置（名称/API Key/API Base/模型/温度/Top P/上下文增强/思考模式等），多档案共存，`isActive` 标记当前生效档案；`LlmSettings` 是其翻译/连接测试使用的运行时子集 | 单一全局配置 |
| **应用日志 (App Log)** | 运行时事件日志：内存环形缓冲（`AppLog`，上限 100 条）+ 异步文件日志（`LogUtils`，`<externalCacheDir>/logs/`）；错误路径统一调用 `AppLog.put` 落日志，用户经「设置 → 调试 → 日志」查看 | 崩溃日志 |
| **崩溃日志 (Crash Log)** | 全局未捕获异常由 `CrashHandler` 落盘到 `<externalCacheDir>/crash/crash-<time>.log`（含设备信息头 + 完整堆栈），并设置 `CrashMark` 让下次启动弹窗提示查看 | 应用日志 |

## 共享实现概念

| 概念 | 单一数据源 |
|---|---|
| 阅读位置 | `ReadingPosition` + `Book.lastReadChapterId/lastReadOffset` |
| 原文和翻译段落范围 | `ReadingContentParser`、UI 层 `ReadingContent.fromChapter()`；两者必须遵守相同的空行/逐行分段规则 |
| 统一章节标题和正文视觉 | `ReadingContentRenderer`、`PageStyle`、`ReaderMetrics` |
| 双语段落和中文气泡 | `BilingualParagraph` |
| 正文插图与封面文件 | 插图链接语法 + `BookImageStore`（私有目录解析、内存位图缓存、删书清理） |
| 阅读器亮暗语义色 | `ReaderPalette.of(isDark)` |
| 页面几何 | `ReaderPageGeometry.of(...)` |
| 章节状态颜色 | `chapterStatusColor(status)` |
| 排版共享常量 | `ReaderMetrics` |
| 翻译状态机 | `TranslationCoordinator`（注入 `TranslationService`） |
| 翻译网络服务 | `TranslationService`（`LlmApiService` 实现） |
| 选词状态与分词 | `TextSelectionState` + `SelectableParagraphText` + `findWordBoundary` |
| 选择手柄 | `SelectionHandles`（双端拖拽手柄，覆盖层渲染）+ `TextSelectionState`（双端选区） |
| 词典数据访问 | `DictDatabase`（只读 SQLite，asset 解压后打开） |
| 单词解释 | `LlmApiService.explainWord()` → `WordExplanation`（`ReaderViewModel.explainWord` 入口） |
| LLM 配置档案 | `LlmProfileEntity`/`LlmProfileDao`/`LlmProfileRepository`；`LlmSettings` 为运行时子集 |
| 应用与崩溃日志 | `AppLog`（内存）/ `LogUtils`+`AsyncFileHandler`（文件）/ `CrashHandler`+`CrashMark`（崩溃） |

## 关键边界（决策摘要）

- **进度只保存章节 + 原文 offset**：页码会随字体、字号、边距、屏幕、翻译内容和排版模式变化，不能作为持久化数据。切换五种翻页类型、中文/英文模式或阅读样式后，必须按同一 `ReadingPosition` 重新定位。
- **原文语言与显示模式分离**：`Book.sourceLanguage`（`zh`/`en`）是书的不变属性，决定翻译方向与段落插槽（原文侧/译文侧 → 英文侧/中文侧）；`languageMode` 只是显示模式，默认=原文语言，切换按书持久化。改变原文语言是破坏性操作：清空本书全部章节译文并重置 PENDING，显示模式重置为新原文语言，确认框明示后果。
- **原文语言判定**：导入时按章节顺序取首个「抽样量 ≥60 字符」的章节（跳过「卷首」等空章节/纯封面页），抽其前 20 段样本文本，CJK 字符占比 ≥30% 判 `zh`、否则 `en`；TXT/EPUB 同路径。日韩等非中英书不支持，按占比归入中文分支并接受方向误判。误判在书架长按菜单修正。
- **翻译门控**：英文书两种显示模式都预译当前+下一章（气泡与中文模式共用同一份中文译文）；中文书保持仅 en 模式翻译。`prevChapter` 上下文始终取上一章译文（目标语言版本）。未译回退对称：中文书 en 模式→中文原文、英文书 zh 模式→英文原文，均自动翻译自愈；英文书 en 模式下译文到达不触发重排。
- **初始化恢复只执行一次**：ViewModel 先取得 Book 进度快照，再等待首个有效章节列表；恢复不应调用会把默认位置写回数据库的普通导航路径。后续章节 Flow 更新只能刷新当前章节，不能重复恢复。
- **位置写入统一且有序**：分页当前页和滚动可见项都转换为 `ReadingPosition`，通过统一进度写入入口保存；快速翻页、跨章和退出不能让旧异步写覆盖新位置。页面 `ON_STOP`、销毁和返回导航前执行 `flushProgress()`。
- **五种翻页类型只改变移动方式**：`scroll` 使用统一内容项的 LazyColumn；其他四种使用同一 `BookWindow` 页列表，分别应用平移、覆盖、无动画或仿真卷页。标题、段落、双语结构、样式和原文范围不能在容器之间另起一套。
- **统一内容模型**：章节标题、卷名、段落、译文和原文范围由 `ReadingContent` 提供。禁止在滚动模式、分页模式或翻译 prompt 中各自用 `split("\n\n")` 重新拆分并建立不同索引。
- **分页窗口**：当前章 ±1 章全量排版常驻；打开书籍/切章首帧仅同步排版中心章，邻居章后台排版后扩展窗口并保持当前视觉页，目录/章节滑块远跳重建窗口 O(1)，但恢复依据始终是 `chapterId + sourceOffset`。滚动内容全书解析惰性化：分页模式不构建，首次进入滚动模式时构建并缓存。
- **双语原子性**：双语对（英文侧+中文侧）默认不可拆；单对超高时按英文行切分，所有片段仍绑定同一个中文侧范围，只有首片段显示 `pairHead` 中文气泡。
- **视觉叠加不参与排版**：中文气泡、Popup、状态提示等不能进入文本测量或改变分页；末段不渲染段距，排版器和真实页面/卷页位图必须保持同一高度口径。
- **设置入口**：字体、字号、行距、段距、翻页类型、背景和高级排版参数的唯一入口是阅读器设置面板。高级选项包括页边距、字间距、首行缩进、两端对齐。全局设置页（`SettingsScreen`）只放主题、翻译配置、翻译参数、调试（日志）和关于，不重复阅读排版项。
- **边到边一致性**：排版几何、PageRenderer、滚动 content padding、卷页覆盖层和仿真手势必须使用同一系统栏扣除公式；内容区宽高按整像素对齐。
- **目录入口**：阅读器目录唯一入口在底部栏中央，顶栏不放目录按钮。
- **主题独立性**：全局 `ThemeSettings` 的 dark/light 与配色只控制应用主题；阅读器页面背景、正文和气泡颜色由阅读设置及 `ReaderPalette` 控制。
- **翻译流可靠性**：持续拼接所有 `content` chunk 直到 `[DONE]`；思考字段单独生成 `Thinking`；网络、解析、取消和长度截断不得静默保存为成功译文。
- **翻译写入的 stale 防护**：翻译开始、完成、失败、取消都以 `translationRunId` 为数据库级匹配条件（`ChapterDao.*TranslationRun`）；切换阅读章节不取消合法后台任务，新任务替换旧任务时旧任务的迟到写入被拒绝。
- **选词是瞬时交互**：选区/工具栏/词典弹窗不持久化；翻页、滚动、切换章节或模式、打开浮层时清除。长按后的下一次点击只清除选区（或关闭词典弹窗）不翻页，再点才正常翻页。长按命中后 consume 本次手势剩余事件，防止外层把抬起当作点按翻页。
- **选词支持双端手柄扩展**：选中词后出现左右两个选择手柄，拖拽手柄扩展选区到多词短语。选区限制在单一段落内，不跨段。拖拽手柄越过对面手柄时自动反转角色。拖拽结束松开时在选区几何中心上方弹出工具栏。手柄为屏幕级覆盖层（ReaderScreen 层级），自然拦截触摸优先于翻页手势。
- **词典库构建与打包**：`tools/build_dict_db.py` 从 ECDICT 基础版 CSV 裁剪（词频存在或词长≤14）构建四列 SQLite，统一小写存储（BINARY 主键 + 查询小写归一覆盖大小写，无 NOCASE 索引）；gzip 预压缩（自定义头携带解压尺寸）为 `assets/dict/ecdict.dict`，APK `noCompress` 原样打包（约 18.7MB）；运行时首次查词解压到 `databases/ecdict.db` 后只读打开，之后按 gz 头声明的尺寸判断是否需要更新。
- **选词高亮是视觉叠加**：选区背景 SpanStyle 不参与文本测量，不改变分页/滚动排版结果。
- **错误落日志**：所有 `catch` 与 `Result.exceptionOrNull()` 路径除写 UI 状态外，应调用 `AppLog.put(msg, throwable)` 落日志；不在各组件散落 `android.util.Log` 或 `printStackTrace`。崩溃由 `CrashHandler` 全局捕获落盘，下次启动经 `CrashMark` 弹窗提示查看。
- **日志入口**：用户经「设置 → 调试 → 日志」进入 `LogViewerScreen`（运行日志/崩溃日志双 Tab），日志查看器不参与阅读排版。
- **EPUB 导入即转换**：导入时一次性解包并转换为「`\n\n` 分段纯文本章节」入库，与 TXT 共用同一内容契约；章节切分 TOC 优先、spine 兜底，TOC 父节点映射为卷；不保留 `.epub` 原文件，不做阅读期随机访问解压。内容全空的结构章（纯封面页产生的空卷首）导入时剔除；「卷首/书末」占位标题随书籍原文语言本地化（英文书显示 Front Matter / Back Matter）。
- **插图是独立段落**：插图以链接形式独占一个段落嵌入原文，参与 `[N]` 编号但翻译 prompt 跳过其内容（编号出现空洞），译文缺失标记由现有兜底逻辑处理；插图块排版不可拆、不参与选词，渲染失败显示占位框。
- **插图尺寸口径**：分页模式整图适配单页内容区（超高整体缩小，不跨页拆条带）；滚动模式按内容宽等比缩放取自然高度。点击插图进入全屏预览（双指缩放/拖动），预览是视觉叠加层不参与排版。
- **删书清理图片**：删除书籍时同步删除其私有目录下的插图和封面文件。

## 交互规则

- 分页类模式（平移/覆盖/无动画/仿真）左右 1/3 点按翻运行时页，中间 1/3 开关工具栏；窗口边界自动跨章。
- 滚动模式上下连续滚动，章节和段落项共享原文 offset；左右 1/3 不翻页，中间 1/3 开关工具栏。
- 仿真模式支持拖拽跟手、超过阈值完成翻页、未超过阈值回弹，以及点按自动卷页。
- 章节滑块和目录跳转到目标章节起点；下一章从 offset=0，上一章按 Legado 语义定位到目标章节末尾。
- 点击正文插图打开全屏预览，支持双指缩放和拖动，关闭返回正文；插图块上的长按不触发选词。
- 夜间模式只翻转阅读配色，不修改用户选择的背景预设。
