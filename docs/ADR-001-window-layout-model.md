# ADR-001 — 章窗口模型 + 行级排版模型重构

- 状态：已接受
- 日期：2026-08-13
- 触发：参考 `reference_code/legado-E` 阅读页设计，优化 VibeReading 阅读器渲染页面

## 背景

原分页渲染管线（`pagination/TextPaginator.kt` 的 `BookPaginator`）存在三类问题（A 类正确性 + B 类参数差距 + 交互功能差距，后者本轮不做）：

1. **A1 测量无约束**：`measurer.measure(text, style)` 未传 `Constraints`，段落被按「单行无限宽」测量。后果：`heightCache` 全是单行高、zh 长段跨页的 `splitParagraph` 因 `lineCount==1` 永不切段（死代码）、一页塞入远超真实可显示的内容、渲染时 `Text` 按真实宽度换行导致**所见≠所排**。
2. **A2 参数不一致**：分页路径与滚动路径的行高/字号计算各自为政（zh 行高 `*1.6` vs `*2`，en 字号 `1.0` vs `0.9375`），同一设置两种观感。
3. **A3 卷页快照失真**：`renderPageBitmap` 用 raw `Paint` + 逐字软换行重排，忽略字体/行高/双语对，与真实页近似。
4. **B 类差距**：无首行缩进、无两端/底部对齐、无字间距、页边距硬编码、标题模式单一、仅 3 种系统字体（无自定义字体）。

## 决策

### D1 章窗口模型（对齐 Legado 的章级布局 + 预载窗口）

- **每章一个 `ChapterPaginator`**：该章全部内容同步排成 `TextPage` 列表常驻（章节受 `STATUS_TOO_LONG` 20k 字符上限约束，全量排版有界）。
- **窗口 = 当前章 ±1**：分页器索引空间 = 窗口内章节页面的**扁平列表**（`WindowPage(chapterId, pageInChapter)`）；翻页在窗口内跨章自然进行（含动画）。
- **窗口滑动**：落在右缘章末页 / 左缘章首页后滑动窗口（瞬时重排索引，视觉内容不变）；N±2 在后台预载，滑动即时。
- **远跳 O(1)**：目录/滑块跳章 = 重建窗口于目标章，不再逐页补排 O(章间距离)。
- **放弃「真全局页索引」**：单一全局索引空间要么铺全书要么对未排版章估算再纠正，两者都违背窗口模型的动机（内存有界 + 跳章 O(1)）。窗口内前缀和是诚实实现；进度持久化为「章 + 章内页」，底部栏本就是章节滑块，不依赖全局页号。

### D2 行级排版模型 + 保留 TextLayoutResult（对齐 Legado TextPageFactory/TextLine，但不逐字绘制）

- 排版以**真实页宽**测量（`Constraints(maxWidth = contentWidth)`），zh 段落跨页按 `TextLayoutResult` 行信息切段（`getLineStart/End/Bottom`）——修复 A1。
- `PageUnit.Para` 挂载 `mainLayout: TextLayoutResult?`（正文/英文）与 `cnLayout: TextLayoutResult?`（展开中文）：渲染层 `Text` 组合以同文本同样式渲染（同一测量引擎 → 天然一致）；卷页位图直接用存储布局 `layout.draw(canvas)` 绘制（修复 A3，逐像素一致）。
- 不做 Legado 的字符级 `TextColumn` Canvas 逐字绘制：那是 View 时代命中测试/选择的产物，Compose 的 `TextLayoutResult` 自带行/字符级信息与命中测试，为将来选择/词典留后路（Q3=c）。

### D3 双语对约束不变（CONTEXT.md 硬约束）

- en 模式双语对是**页级原子单元**：英 + 展开中文整体不可拆，放不下整对移下页（Q5=a）。
- 单对超高整页时退化为按行切分 en 文本（首片段 `pairHead=true` 可展开、携带中文；续片段不可展开），不丢内容（原实现 200 字截断丢内容）。
- 展开状态重排：`setExpanded` 从所在页起重排该章（高度已缓存，廉价）。

### D4 B 类参数口径（Q8=b：全部实现；高频进面板，低频默认值）

- **进阅读器设置面板**：页边距（左右/上下）、字间距、两端对齐开关、首行缩进量、自定义字体（SAF 导入 TTF/OTF，content:// URI 持久化 + `takePersistableUriPermission`）。
- **实现为默认值、不进面板**：底部对齐（默认开，排版期按页分配 slack 到每行）、标题模式（默认左对齐，居中/隐藏实现为选项）、行距/段距比例（语义保持「额外 sp / dp」，默认渲染比例与 Legado 微信读书预设同量级，不改持久化语义）。
- 中文断行（Legado `ZhLayout`）：`useZhLayout` 在 Legado 默认关；Compose 内置 ICU 断行已处理 CJK 标点禁则，本轮以测试验证，不移植 `ZhLayout`。
- 默认参数对齐 Legado 微信读书预设（`defaultData/readConfig.json`）：左右边距 22dp、字间距 0、两端对齐开、缩进两全角空格（2em）、标题=正文+4sp。

### D5 样式/模式变更位置保持（Q6）

- 换字号/字距/边距/切换中英模式导致窗口重建时，记住「当前章 + 原文字符 offset」，重建后恢复到该页（不再跳回第 0 页）。
- 进度持久化为「章 + 原文字符 offset」（Room v6：`books.lastReadChapterId` + `books.lastReadOffset`，UTF-16 半开区间；迁移链 v2→v3→v4→v5→v6，旧 `lastReadPage` 页码无可信字符映射故不转换、归零）。

### D6 滚动模式统一（Q10=a）

- 滚动模式（`FLIP_SCROLL`）共享同一 `PageStyle`（行高因子、字号、缩进、字距、两端对齐统一），修 A2；不参与行级模型与章窗口（LazyColumn + `Text` 结构不变，流式翻译渲染不动）。

### D7 验收

- 排版引擎单测：Robolectric 4.14 + `@GraphicsMode(NATIVE)`（真 `StaticLayout` 换行），断言结构化（切段边界字符串拼接不丢字、双语对整体迁移、每页高度 ≤ 页高、窗口滑动正确），不 pin 像素值。
- android-emulator MCP 截图作观感补充验证。

## 不在本轮范围

- C 类交互：文字选择/复制/词典、亮度、页眉页脚、书签。
- 全局页号显示（第X页/共Y页）——窗口模型下不可得。
- 内置字体打包、字体预览。
- `ZhLayout` 标点压缩等中文断行精细规则。

## 影响

- `pagination/TextPaginator.kt` 重写为共享类型 + `ChapterPaginator`；新增 `pagination/BookWindow.kt`；`BookPager.kt` 渲染与位图重写；`ReaderScreen.kt` 分页器接线重写。
- `ReadingSettings` + 7 字段；`Book`/`BookEntity` 阅读进度演进为 `lastReadChapterId` + `lastReadOffset`（原文字符 offset）；Room schema 演进至 v6（含 `translationRunId` 数据库级 stale 防护、`translatedChapters` 冗余列移除改实时派生）。
- 跨章**滑动**动画保留（窗口内连续），翻页类型切换与远跳为瞬时重排（已接受，见 D1）。
