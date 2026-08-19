# ADR-002 — 长按选词拖拽手柄扩展选区

- 状态：已接受
- 日期：2026-08-19
- 触发：参考 `reference_code/legado-E` 阅读器的双端拖拽手柄（`cursor_left`/`cursor_right`），为 VibeReading 阅读器增加多词连续选择能力

## 背景

现有选词机制仅支持**单次长按选中单个词**（`SelectableParagraphText` + `BreakIterator` 分词），无拖拽扩展能力。用户无法连续选择多个词进行查词/解释/复制。

Legado 参考实现（`ContentTextView`）使用 `TextPos(relativePagePos, lineIndex, columnIndex)` 三元组追踪选区两端，`ImageView` 覆盖层作为手柄，`View.OnTouchListener` 处理拖拽。但 VibeReading 基于 Compose 框架，文本布局由 `TextLayoutResult` 管理，需适配 Compose 的指针输入和坐标系统。

### 关键约束

1. **选区仅限同段**：跨段落需要处理多段 `AnnotatedString` 拼接和跨段坐标映射，复杂度高但收益低（查词/解释通常针对单句或短语），首版不做。
2. **分页/滚动两种翻页模式**：手柄渲染和坐标映射需同时适配两种模式。
3. **Compose 手势系统**：手柄需在高 z-order 拦截触摸，与翻页手势（`HorizontalPager` 的 `awaitEachGesture`）协调。
4. **五种翻页效果**：`pager`/`cover`/`no_anim`/`simulation` 共用同一内容层，手柄只需工作在 `PageRenderer` 级别。

## 决策

### D1 两阶段交互模型（对齐 Legado 但分离为独立手势阶段）

- **阶段一（长按选词）**：用户长按段落 → `SelectableParagraphText` 检测长按 → `BreakIterator` 找到词边界 → 设置 `selectionStart`/`selectionEnd` → consume 手势剩余事件 → 抬手。
- **阶段二（手柄拖拽）**：用户点击手柄（新手势）→ 拖拽调整选区 → 松开弹出工具栏。
- 不实现 Legado 的「长按后不抬手连续拖拽」模式，两阶段交互更符合 Compose 手势隔离原则，减少误触。

### D2 屏幕级覆盖层渲染手柄（对齐 Legado 的 Activity 层级 ImageView）

- 手柄不在 `PageUnit.Para` 或 `SelectableParagraphText` 内部渲染，而是在 `ReaderScreen` 层级作为 `Box` 覆盖层渲染。
- 手柄位置通过 `TextLayoutResult.getBoundingBox(offset)` + 段落的 `positionInWindow()` 计算**绝对窗口坐标**。
- 覆盖层自然处于最高 z-order，拦截触摸优先于翻页手势。
- 分页模式和滚动模式共用同一套 `SelectionHandles` 组件。

### D3 TextSelectionState 扩展为双端选区

- `selectionRange: IntRange?` → `selectionStart: Int`（inclusive）+ `selectionEnd: Int`（exclusive），UTF-16 code-unit 偏移，与 `ReadingPosition.offset` 语义一致。
- 新增 `layoutResult: TextLayoutResult?` 和 `paragraphWindowOffset: Offset`，由 `SelectableParagraphText` 长按选中后上报。
- 新增 `draggingHandle: HandleType?`（`START` / `END`），追踪当前拖拽端。
- 新增 `startDrag(handle)` / `dragTo(offset)` / `endDrag()` 方法，`dragTo` 内含自动反转逻辑（手柄拖过对面时交换角色，对齐 Legado `reverseStartCursor`/`reverseEndCursor`）。

### D4 坐标映射方案

- **手柄位置**：`handlePos = paragraphWindowOffset + getBoundingBox(offset).let { Start: left, End: right }`
- **拖拽 → offset**：`screenPos - paragraphWindowOffset → localPos → getOffsetForPosition(localPos) → 逐字符命中测试`（复用现有 `SelectableParagraphText` 的两端对齐补偿逻辑）
- 不引入 Legado 的 `TextPos` 三元组模型：Compose 的 `TextLayoutResult` 已提供足够坐标信息，无需建立独立的位置索引层。

### D5 手柄反转（对齐 Legado reverseStartCursor/reverseEndCursor）

- 拖拽 `START` 手柄超过 `selectionEnd` 时：`START` 变为 `END`，原 `END` 变为新的 `START`，手势不中断。
- 拖拽 `END` 手柄超过 `selectionStart` 时：对称反转。
- 反转后 `draggingHandle` 更新为新的手柄类型，视觉上用户拖拽的手柄始终是「活动端」。

### D6 触摸仲裁：手柄 vs 翻页手势

- 手柄的 `pointerInput` 在 `awaitEachGesture` 中及时 consume `awaitFirstDown` 事件。
- 翻页手势的 `awaitFirstDown(requireUnconsumed = false)` 收到事件后，检查 `down.isConsumed`：如已被手柄消费，不清除选区。
- 选区外的点击（非手柄区域）→ down 未被消费 → 翻页手势正常清除选区。

### D7 工具栏定位

- 拖拽结束松开手柄时，计算选区几何中心（首字符左边缘与末字符右边缘的中点），设置 `popupPosition` 为此位置。
- `SelectionToolbar` 定位在选区中心上方，与现有单词语义一致（已在 `SelectionToolbar` 的 `calculatePosition` 中处理上下空间不足的翻转）。

### D8 手柄视觉

- 竖线 + 圆点底部（iOS/macOS 风格）：2dp 宽竖线 + 4dp 半径圆点。
- 触控区域 24dp（与原文气泡触控区 44dp 不同，因手柄常位于页面边缘，24dp 配合手势系统已足够，且避免与翻页热区冲突）。
- 颜色跟随 `ReaderPalette` 新增 `handleColor` 字段，使用当前主题的强调色。

### D9 分页优先，滚动后置

- 首版实现分页模式（`pager`/`cover`/`no_anim`/`simulation`）的手柄选词。
- 滚动模式后续适配（共享 `SelectionHandles` 组件，`LazyColumn` 中 `onGloballyPositioned` 随滚动自动更新坐标）。

## 不在本轮范围

- 跨段落选区：选区限制在单段内，跨段需要多段落 `AnnotatedString` 拼接和独立的高亮管理。
- 长按后不抬手连续拖拽扩展：两阶段交互已满足需求，连续拖拽作为后续优化。
- 长按选词直接进入解释弹窗（跳过工具栏）：当前模式需用户确认操作。
- 选区全选功能：工具栏不增加「全选」按钮，用户可通过三击或菜单另行实现。

## 影响

- `TextSelection.kt`：`TextSelectionState` 重构为双端模型，新增 `HandleType` 枚举、`startDrag/dragTo/endDrag` 方法。
- `SelectionHandles.kt`（新建）：`SelectionHandles` 覆盖层 + `SelectionHandle` 单个手柄组件。
- `ReaderScreen.kt`：集成 `SelectionHandles` 覆盖层；修改翻页手势的选区清除逻辑。
- `ReaderPalette.kt`：新增 `handleColor` 字段。
- `SelectableParagraphText`：长按选中后上报 `TextLayoutResult` 和 `windowPosition` 到 `selectionState`。
- `ReaderScroll.kt`：后续追加手柄集成。