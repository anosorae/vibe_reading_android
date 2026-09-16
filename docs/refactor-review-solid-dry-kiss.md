# 代码评审：SOLID / DRY / KISS（全项目）

评审对象：`vibe_reading_android` 全项目（评审时 `app/src/main` 17.8k 行 Kotlin、`app/src/test` 6.2k 行、`assets/web/index.html` 1.5k 行）。
评审分支：`refactor/solid-dry-kiss`。本文件同时是**评审报告**与**实施记录**。

## 0. 结论

架构骨架是健康的：分层（Repository → ViewModel → Composable）没有越界，段落解析、排版常量、页面几何、语义色板、导航路由都有明确的单一数据源，没有 `TODO`/`FIXME`/死代码。

债务集中在三处，且都是同一类问题的不同表现：

1. **重复**——机械样板成规模存在（65 个 `catch`、70 处 `AppLog.put`、43 处裸 `"zh"`/`"en"` 字面量对 11 处常量引用、LLM 面板约 500 行双份实现）。
2. **体量**——UI 层占主代码 71.8%，`ReaderScreen.kt` 一个 `@Composable` 1305 行。
3. **隐性契约**——排版有 Compose 与卷页位图两套手写渲染，靠 20+ 条「必须与…一致」注释维持不变量。

判断：这不是设计错误，而是「重复」与「体量」。因此处理顺序是 **DRY → 接口/依赖 → 安全网 → 共享几何 → 分层 → 拆文件**，风险递增。

## 1. 做得好的地方（重构时不要误伤）

- `ReaderMetrics`（排版常量）、`ReaderPageGeometry.of()`（内容区公式）、`ReaderPalette.of`（语义色板）、`AppNavigation.Routes`（路由）、`SettingsRepository` 的私有 Keys object（DataStore key）都是**单点且无旁路**：搜索不到第二处手写公式或裸字符串。
- `ReadingContent.fromChapter()` + `ReadingContentParser.parseBilingualParagraphs` 是段落数据的唯一事实源，`BookWindow`/`ReaderScroll` 都从它取。
- 测试有正面样板：`SimFlipStateTest`、`ReaderGesturePolicyTest` 直接驱动生产状态机（调 `readerPagerScrollEnabled`），没有复制逻辑。
- 无 `TODO`/`FIXME`/`HACK`、无注释掉的死代码。

## 2. 违反单一职责（SRP）

| 位置 | 体量 | 问题 |
|---|---|---|
| `ui/reader/ReaderScreen.kt:63` | **1305 行，文件 1367 行，整个文件只有 1 个 `@Composable`** | 一个函数承担状态收集、几何计算、分页窗口驱动、两套手势状态机、卷页动画调度（4 个局部函数）、6 个浮层挂载、进度持久化；闭包嵌套 10 层 |
| `ui/settings/SettingsScreen.kt:59` | 446 行 | 同类问题，小一档 |
| `ui/bookshelf/BookshelfScreen.kt:64` | 459 行 | 同上 |
| `ui/reader/components/LlmSettingsSheet.kt:46` | 383 行单函数 | 同上 |
| `ui/reader/ReaderViewModel.kt:73` | 9 个依赖、`ReaderUiState` **34 字段**、9 个 StateFlow | 位置/翻译/词典/解释/设置/LLM 六类状态挤在一个 UiState；`init` 前必须声明 LLM 编辑字段的顺序耦合 |
| `data/repository/SettingsRepository.kt` | 269 行、16 公开成员、**5 个正交配置域** | 阅读设置 + 主题 + 书架偏好 + 伴读开关 + 旧 LLM key 迁移 |
| `ui/reader/TranslationCoordinator.kt` | 300 行 | 状态机 + 持久化 + 业务校验 + 前台服务生命周期 + 自己 new `LlmApiService` |
| `domain/model/ReadingSettings.kt:44` | — | `LlmSettings` 声明在 `ReadingSettings.kt` 里（文件名与内容不符）|
| `ui/reader/TranslationCoordinatorTest.kt:33` | — | `FakeTranslationService`（生产接口的实现）定义在测试文件里，被另一个测试文件隐式复用 |

## 3. 违反开闭原则（OCP）

唯一根源是**双渲染路径**：`BookPager.renderPageBitmap`（位图）与 `PageRenderer`（Compose）是同一份版面的两套手写实现，一致性靠注释（`BookPager.kt:550/552/566/619/623/634/660`、`BilingualParagraph.kt:44/45/68/91/136/141`）。具体重复：标题块高度 3 处、内容区扣除 5 处、气泡圆角 2 处、插图适配 2 套魔法数。

其次是 `flipMode` 分支散布：`ReaderScreen` 18 处 `isPagerMode` 守卫 + 手势块 `inSim`/非 `inSim` 两套几乎相同的三分区点按（`:927` vs `:1043`）；`FLIP_PAGER` 靠 `else` 兜底。

## 4. 违反里氏/接口隔离/依赖倒置

- `LlmApiService.explainWord` 不在 `TranslationService` 接口里 → `ReaderViewModel` 必须同时持有两个引用。
- `LlmApiService` 无构造参数，`OkHttpClient`/`Gson` 硬编码在字段初始化器 → 全项目 new 了 3 个实例（3 套连接池）。
- `TranslationCoordinatorProvider.create()` 自己 `LlmApiService()`，绕过组合根，形成第二份 wiring。
- `ChapterRepository` 14 个方法纯 1:1 转发；`domain` 层仅 8.3% 代码量，业务规则散在 Coordinator 与 CompanionApi 各写一遍。

## 5. 违反 DRY（最可机械化的一类）

已修复（见第 7 节）：`AppLog.put/putNotSave` 双份；`ReaderViewModel` 的 7 个 `updateLlmXxx` + 4 个 `toggleXxx`；三处 Popup 定位；背景五档列表两份；`TextPaginator` 重复局部函数；前台服务样板两份；设备信息块两份；进度归一化 3 套语义；书架进度公式两份；LLM 面板约 500 行；`LlmApiService` 三处 request 拼装与六处 catch；LLM 默认值 6 份。

仍存在（未处理，属可接受或需产品决策）：`CompanionJson` 与 `ChapterDao`/`BookDao` 之间的 DTO 三份手写映射（entity ↔ domain ↔ JSON）；`Chapter` 被手写映射 6 次、`Book`/`BookShelfItem` 5 次（加字段要改 4 处，编译器不提醒）；`AppDatabase` 迁移链的 9 个同构单列 `ALTER` 与 `4_5`/`5_6` 双子（**刻意保留**，见第 8 节）；`mode: String` 在 14 处传播、裸 `"zh"`/`"en"` 43 处；`assets/web/index.html` 与 Kotlin 双份五档背景色（跨语言）。

## 6. 违反 KISS

`if (run == runId)` 守卫 10 处；终态 `copy` 块 4 份（各清不同字段子集）；`TextPaginator.layoutUntil` 173 行；`PageCurl` 四个几何函数 350+ 行且直接读写 10+ 成员变量；长参数列表（`LlmSettingsSheet` 26 参数含 19 回调、`ReaderPager`/`ScrollReader` 各 15）；`mode: String` 无类型约束。

## 7. 已实施（M1–M5，每步都过了构建 + 单测 + 装机）

| 提交 | 内容 | 验证 |
|---|---|---|
| `3c251b7` **M1** | 纯机械 DRY：测试夹具 `TestFixtures.kt`（替换 11 处 `TextMeasurer`、6 处 `PageStyle`、5 处 Room 建库、2 处匿名 DataStore）；`FakeTranslationService` 独立；`AppLog` 去重；`ReaderViewModel` 的 `updateLlmXxx`/`toggleXxx` 合并；三处 Popup 定位合一（顺带修掉弹窗宽于窗口时 `coerceIn` 抛异常）；`ReaderBgPresets.all/isDark`；`TextPaginator` 重复函数；SQL/实体魔法数字改用 `Chapter.STATUS_*`；`ReadingPosition.clampOffset` 单点；`BookShelfItem.progressOf` 单点；前台服务与设备信息样板合一 | 268 单测全绿；装机无崩溃；`check_log_convention` 18 → 17 |
| `3a39ce6` **M2** | LLM 面板共享实现 `ui/components/LlmPanels.kt`（`LlmSettingsSheet` 428→158 行、`SettingsScreen` 809→464 行）；`LlmApiService` 抽 `buildRequest`/`guarded`/`complete`；`WordExplainService` 接口（依赖倒置）；`LlmApiService` 注入 `OkHttpClient`/`Gson` 并收到 `VibeReadingApp` 组合根（消除 3 套连接池）；`LlmDefaults` 默认值 6→1 | 268 单测全绿（含迁移链，schema 未变）；装机无崩溃 |
| `a308e5b` **M3** | **几何一致性测试网** `PageGeometryConsistencyTest`（7 用例：PageRenderer 落点、标题块高、页高/段距、气泡矩形、插图尺寸、跨页续段、计划契约）。断言「两侧一致」而非固定数值 | 274 全绿；**有效性已验**：故意改坏位图气泡/排版器段距/PageRenderer 边距/计划器段距，四次都由对应用例精确变红 |
| `666600a` **M4** | **共享版面计划** `PageLayoutPlanner`：Compose 与位图都消费同一份几何，位图删掉全部手工 `cursorY` 累加；气泡圆角/插图比例魔法数进 `ReaderMetrics` | 275 全绿；装机无崩溃；M3 网抓到 2 处真实回归（见下） |
| `8e01459` **M5** | `SettingsRepository` 拆为 5 个域 Store + 组合根；`TranslationPreflight` 纯函数（+8 单测）；10 处守卫收成 `updateIfCurrent()`、4 份终态收成 `finish()` | 283 全绿；装机无崩溃；无行尾噪音 |
| `08fea7f` **M5 补** | `DataStore.safeData`：7 处 `.catch { emit(emptyPreferences()) }` 原先静默回退默认值，现按 AGENTS.md 落日志——「设置莫名回到默认」终于可查 | 283 全绿；装机无崩溃；`check_log_convention` 疑似点 **18 → 16** |

### M4 过程中被安全网抓到的两处真实回归（说明安全网有效）

1. 把 `bilingualPadSidePx` 当成 `ReaderMetrics.bilingualPadPx(density)/2`——后者用 `kotlin.math.round`（**ties-to-even**，10.5→10），前者必须是 `roundToPx`（10.5→11）。位图双语段每侧少 1px、相邻段间距差 2px，被 `RenderPageBitmapTitleOffsetTest` 抓到。
2. 「计划总高 == 排版器 `usedHeightPx`」这条断言本身是错的：两者口径刻意不同（测量期浮点 vs 渲染期 `roundToPx`），差距随段数累积。改为「计划总高不得溢出内容区」。

### 一个值得记住的口径结论

渲染几何用 **`roundToPx`**（与 `Modifier.padding` 一致），排版测量用 **浮点 `DP * density`**。两者刻意差 <1px：测量期少留只会让段落更早落页，反了才会出现「排版说放得下、渲染溢出」的底行被裁。这条已写入 `PageLayoutPlan.kt` 的 KDoc 与 `AGENTS.md`。

## 8. 明确不做的（附理由，非遗漏）

- **`AppDatabase` 迁移链的样板提取**：13 个迁移中 9 个同构单列 `ALTER`、`4_5`/`5_6` 是双子。迁移 SQL 是**历史 schema 快照**，改错对已装机用户不可挽回，而 helper 只省约 30 行。收益远小于风险，保持逐条显式可审计。
- **`DictEntry` 与 `WordExplanation` 合并**：`pos` 的 nullability 差异是**语义正确**的（ECDICT 可能缺音标/词性，LLM prompt 强制所有字段返回）；`word`（精确查得的词形）与 `lemma`（规范化词条）也不同义。已写入 `CONTEXT.md` 术语表。
- **插图适配的两套算法统一**：分页是「整图缩进给定盒子」，滚动是「按声明比例的自然高度」，是不同问题；只把魔法数（4:3 兜底、宽高比夹取 0.2~5）命名进 `ReaderMetrics`。

## 9. 未完成：M6（拆巨型文件与参数打包）

计划内容与依据见下。**本轮未实施**，因为它是唯一没有等价安全网的改动面：

1. `ReaderScreen.kt`（1305 行单函数）按职责拆分——`ReaderGestures`（三分区点按/拖动，两套分支合并为一套参数化逻辑）、`ReaderCurlController`（4 个卷页动画局部函数）、`ReaderOverlays`（6 个浮层挂载），主函数降到 300 行以内。
2. 参数打包：`ReaderPager`/`ScrollReader`/`PageInfoOverlays`/`PageRenderer` 统一收进 `ReaderLayoutSpec`，各降到 6-7 参数；`LlmSettingsSheet` 的 19 个回调收成 1 个 UiState + 一组动作。
3. `BookshelfScreen`/`SettingsScreen` 按同法按 Section 拆分。

**为什么需要格外小心**：`ReaderScreen` 内有 15 个 `LaunchedEffect` 与 20+ 个 `remember` 局部状态，搬移会改变重组与闭包捕获语义；手势与浮层行为没有单测覆盖（M3 的网只覆盖排版几何），因此只能逐段搬移 + 每段装机手验。这与 M4 不同——M4 有判定标准（测试网保持全绿），M6 没有。

**建议的起步顺序**（低风险在前）：
1. 先把 `launchCurlAnim`/`startSimFlip`/`simFlipAnimStart`/`curlBitmaps` 搬进 `ReaderCurlController`（自包含，只依赖 `pagerState`/`simFlip`/`window`）；
2. 再把 6 个浮层的挂载搬进 `ReaderOverlays`（纯搬运，不改参数）；
3. 手势块最后动，且动之前先按 `ReaderGesturePolicyTest` 的口径把三分区判定抽成可测的纯函数。

## 10. 本轮触达的验收方式

每个里程碑：`./gradlew.bat :app:assembleDebug` → 按 `output-metadata.json` 选 ABI → `adb install` → 启动 → 确认无 `FATAL EXCEPTION` → `:app:testDebugUnitTest` → `python tools/check_log_convention.py` → 行尾噪音检查（`git diff --numstat --ignore-cr-at-eol` 比对）。
