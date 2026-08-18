# CONTEXT.md — Vibe Reading 领域术语表

本文件记录 VibeReading 的领域词汇、用户可见行为和跨模块边界。实现细节与架构约束见 **`AGENTS.md`**；分页窗口的历史决策见 **`docs/ADR-001-window-layout-model.md`**。修改阅读逻辑前必须同时阅读这三处文档。

## 术语

| 术语 | 定义 | 反义/相关 |
|---|---|---|
| **书籍 (Book)** | 一次 TXT 导入形成的阅读单元，含若干章节和书架元数据 | 章节 |
| **章节 (Chapter)** | 书籍的最小内容单元，有原文、译文、翻译状态和失败原因 | 书籍 |
| **原文偏移 (Source Offset)** | 章节原文字符串中的 UTF-16 字符偏移，使用半开区间坐标；是跨重排、跨阅读模式恢复位置的稳定锚点 | 运行时页码 |
| **阅读位置 (Reading Position)** | `chapterId + sourceOffset`；唯一持久化的阅读进度。章节不存在时回退到首章 offset=0 | 页码 |
| **运行时页码 (Derived Page Index)** | 当前字体、字号、页边距、屏幕尺寸、阅读模式和内容排版下，由 `sourceOffset` 动态反查得到的章内页索引；不持久化 | 原文偏移 |
| **原文段落 (Source Paragraph)** | 章节原文中的一段文字，带 `startOffset/endOffset` 精确范围；展示文本可清理空白，但不能改变范围 | 双语段落 |
| **双语段落 (Bilingual Paragraph)** | 一个英文译文与对应中文原文段落的稳定配对；英文段尾可显示原文气泡 | 单语段落 |
| **原文气泡 (Source Bubble)** | 英文段尾的半透明色块（18×6dp），点击后弹窗显示对应中文原文；视觉叠加层，不参与排版测量 | 弹窗 |
| **统一章节内容 (Reading Content)** | 分页和滚动共同消费的章节标题、卷名、原文段落、译文和原文范围结构；由 `ReadingContent` 表示 | 分叉渲染数据 |
| **统一阅读项 (Reading Item)** | 章节标题项或带原文范围的段落项；滚动列表按项定位，分页器按项排版为页 | 整章嵌套内容 |
| **阅读模式 (Reading Mode)** | `zh`（中文原文）或 `en`（英文译文为主，点击气泡查看中文原文） | — |
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
| **夜间模式 (Night Mode)** | 阅读背景在当前预设与深夜背景之间的快捷切换，独立于全局主题和背景设置 | 背景预设 |
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
| **查词 (Dict Lookup)** | 工具栏「查词」→ 离线查询内嵌 ECDICT 库 → 弹窗显示音标/词性/中文释义；未收录时提示（中文词提示"仅支持英文查词"） | 选词 |
| **词典库 (Dict Database)** | 内嵌 ECDICT 精简版 SQLite（约 50 万常用词条，只含 word/phonetic/translation/pos 四列）；构建时 gzip 预压缩为 `assets/dict/ecdict.dict`，首次查词解压到内部存储 | 在线词典 |

## 共享实现概念

| 概念 | 单一数据源 |
|---|---|
| 阅读位置 | `ReadingPosition` + `Book.lastReadChapterId/lastReadOffset` |
| 原文和翻译段落范围 | `ReadingContentParser`、UI 层 `ReadingContent.fromChapter()`；两者必须遵守相同的空行/逐行分段规则 |
| 统一章节标题和正文视觉 | `ReadingContentRenderer`、`PageStyle`、`ReaderMetrics` |
| 双语段落和原文气泡 | `BilingualParagraph` |
| 阅读器亮暗语义色 | `ReaderPalette.of(isDark)` |
| 页面几何 | `ReaderPageGeometry.of(...)` |
| 章节状态颜色 | `chapterStatusColor(status)` |
| 排版共享常量 | `ReaderMetrics` |
| 翻译状态机 | `TranslationCoordinator`（注入 `TranslationService`） |
| 翻译网络服务 | `TranslationService`（`LlmApiService` 实现） |
| 选词状态与分词 | `TextSelectionState` + `SelectableParagraphText` + `findWordBoundary` |
| 词典数据访问 | `DictDatabase`（只读 SQLite，asset 解压后打开） |

## 关键边界（决策摘要）

- **进度只保存章节 + 原文 offset**：页码会随字体、字号、边距、屏幕、翻译内容和排版模式变化，不能作为持久化数据。切换五种翻页类型、中文/英文模式或阅读样式后，必须按同一 `ReadingPosition` 重新定位。
- **初始化恢复只执行一次**：ViewModel 先取得 Book 进度快照，再等待首个有效章节列表；恢复不应调用会把默认位置写回数据库的普通导航路径。后续章节 Flow 更新只能刷新当前章节，不能重复恢复。
- **位置写入统一且有序**：分页当前页和滚动可见项都转换为 `ReadingPosition`，通过统一进度写入入口保存；快速翻页、跨章和退出不能让旧异步写覆盖新位置。页面 `ON_STOP`、销毁和返回导航前执行 `flushProgress()`。
- **五种翻页类型只改变移动方式**：`scroll` 使用统一内容项的 LazyColumn；其他四种使用同一 `BookWindow` 页列表，分别应用平移、覆盖、无动画或仿真卷页。标题、段落、双语结构、样式和原文范围不能在容器之间另起一套。
- **统一内容模型**：章节标题、卷名、段落、译文和原文范围由 `ReadingContent` 提供。禁止在滚动模式、分页模式或翻译 prompt 中各自用 `split("\n\n")` 重新拆分并建立不同索引。
- **分页窗口**：当前章 ±1 章全量排版常驻，窗口页索引是扁平运行时索引；目录/章节滑块远跳重建窗口 O(1)，但恢复依据始终是 `chapterId + sourceOffset`。
- **双语原子性**：英文双语对默认不可拆；单对超高时按英文行切分，所有片段仍绑定同一个中文原文范围，只有首片段显示 `pairHead` 原文气泡。
- **视觉叠加不参与排版**：原文气泡、Popup、状态提示等不能进入文本测量或改变分页；末段不渲染段距，排版器和真实页面/卷页位图必须保持同一高度口径。
- **设置入口**：字体、字号、行距、段距、翻页类型、背景和高级排版参数的唯一入口是阅读器设置面板。高级选项包括页边距、字间距、首行缩进、两端对齐。
- **边到边一致性**：排版几何、PageRenderer、滚动 content padding、卷页覆盖层和仿真手势必须使用同一系统栏扣除公式；内容区宽高按整像素对齐。
- **目录入口**：阅读器目录唯一入口在底部栏中央，顶栏不放目录按钮。
- **主题独立性**：全局 `ThemeSettings` 的 dark/light 与配色只控制应用主题；阅读器页面背景、正文和气泡颜色由阅读设置及 `ReaderPalette` 控制。
- **翻译流可靠性**：持续拼接所有 `content` chunk 直到 `[DONE]`；思考字段单独生成 `Thinking`；网络、解析、取消和长度截断不得静默保存为成功译文。
- **翻译写入的 stale 防护**：翻译开始、完成、失败、取消都以 `translationRunId` 为数据库级匹配条件（`ChapterDao.*TranslationRun`）；切换阅读章节不取消合法后台任务，新任务替换旧任务时旧任务的迟到写入被拒绝。
- **选词是瞬时交互**：选区/工具栏/词典弹窗不持久化；翻页、滚动、切换章节或模式、打开浮层时清除。长按后的下一次点击只清除选区（或关闭词典弹窗）不翻页，再点才正常翻页。长按命中后 consume 本次手势剩余事件，防止外层把抬起当作点按翻页。
- **词典库构建与打包**：`tools/build_dict_db.py` 从 ECDICT 基础版 CSV 裁剪（词频存在或词长≤14）构建四列 SQLite，统一小写存储（BINARY 主键 + 查询小写归一覆盖大小写，无 NOCASE 索引）；gzip 预压缩（自定义头携带解压尺寸）为 `assets/dict/ecdict.dict`，APK `noCompress` 原样打包（约 18.7MB）；运行时首次查词解压到 `databases/ecdict.db` 后只读打开，之后按 gz 头声明的尺寸判断是否需要更新。
- **选词高亮是视觉叠加**：选区背景 SpanStyle 不参与文本测量，不改变分页/滚动排版结果。

## 交互规则

- 分页类模式（平移/覆盖/无动画/仿真）左右 1/3 点按翻运行时页，中间 1/3 开关工具栏；窗口边界自动跨章。
- 滚动模式上下连续滚动，章节和段落项共享原文 offset；左右 1/3 不翻页，中间 1/3 开关工具栏。
- 仿真模式支持拖拽跟手、超过阈值完成翻页、未超过阈值回弹，以及点按自动卷页。
- 章节滑块和目录跳转到目标章节起点；下一章从 offset=0，上一章按 Legado 语义定位到目标章节末尾。
- 夜间模式只翻转阅读配色，不修改用户选择的背景预设。
