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

`if (run == runId)` 守卫 10 处；终态 `copy` 块 4 份（各清不同字段子集）；`TextPaginator.layoutUntil` 173 行；`PageCurl` 四个几何函数 350+ 行且直接读写 10+ 成员变量；长参数列表（`LlmSettingsSheet` 26 参数含 **16 回调**、`ReaderPager`/`ScrollReader` 各 15）；`mode: String` 无类型约束。

## 7. 已实施（M1–M6，均完成构建 + 单测 + 装机）

| 提交 | 内容 | 验证 |
|---|---|---|
| `3c251b7` **M1** | 纯机械 DRY：测试夹具 `TestFixtures.kt`（替换 11 处 `TextMeasurer`、6 处 `PageStyle`、5 处 Room 建库、2 处匿名 DataStore）；`FakeTranslationService` 独立；`AppLog` 去重；`ReaderViewModel` 的 `updateLlmXxx`/`toggleXxx` 合并；三处 Popup 定位合一（顺带修掉弹窗宽于窗口时 `coerceIn` 抛异常）；`ReaderBgPresets.all/isDark`；`TextPaginator` 重复函数；SQL/实体魔法数字改用 `Chapter.STATUS_*`；`ReadingPosition.clampOffset` 单点；`BookShelfItem.progressOf` 单点；前台服务与设备信息样板合一 | 268 单测全绿；装机无崩溃；`check_log_convention` 18 → 17 |
| `3a39ce6` **M2** | LLM 面板共享实现 `ui/components/LlmPanels.kt`（`LlmSettingsSheet` 428→158 行、`SettingsScreen` 809→464 行）；`LlmApiService` 抽 `buildRequest`/`guarded`/`complete`；`WordExplainService` 接口（依赖倒置）；`LlmApiService` 注入 `OkHttpClient`/`Gson` 并收到 `VibeReadingApp` 组合根（消除主要重复连接池）；`LlmDefaults` 默认值 6→1。**复核纠正**：M2 未把全局 `SettingsViewModel` 的连接测试完全接到组合根，它仍自行创建 `LlmApiService`，留到本轮修复。 | 268 单测全绿（含迁移链，schema 未变）；装机无崩溃 |
| `a308e5b` **M3** | **几何一致性测试网** `PageGeometryConsistencyTest`（**6 个 M3 用例**：PageRenderer 落点、标题块高、页高/段距、气泡矩形、插图尺寸、跨页续段）。断言「两侧一致」而非固定数值 | 274 全绿；**有效性已验**：故意改坏位图气泡/排版器段距/PageRenderer 边距，均由对应用例精确变红 |
| `666600a` **M4** | **共享版面计划** `PageLayoutPlanner`：Compose 与位图都消费同一份几何，位图删掉全部手工 `cursorY` 累加；气泡圆角/插图比例魔法数进 `ReaderMetrics`；M4 同时新增第 **7** 个“计划契约”用例 | 275 全绿；装机无崩溃；M3/M4 网抓到 2 处真实回归（见下） |
| `8e01459` **M5** | `SettingsRepository` 拆为 5 个域 Store + 组合根；`TranslationPreflight` 纯函数（+8 单测）；10 处守卫收成 `updateIfCurrent()`、4 份终态收成 `finish()` | 283 全绿；装机无崩溃；无行尾噪音 |
| `08fea7f` **M5 补** | `DataStore.safeData`：7 处 `.catch { emit(emptyPreferences()) }` 原先静默回退默认值，现按 AGENTS.md 落日志——「设置莫名回到默认」终于可查 | 283 全绿；装机无崩溃；`check_log_convention` 疑似点 **18 → 16** |

### M4 过程中被安全网抓到的两处真实回归（说明安全网有效）

1. 把 `bilingualPadSidePx` 当成 `ReaderMetrics.bilingualPadPx(density)/2`——后者用 `kotlin.math.round`（**ties-to-even**，10.5→10），前者必须是 `roundToPx`（10.5→11）。位图双语段每侧少 1px、相邻段间距差 2px，被 `RenderPageBitmapTitleOffsetTest` 抓到。
2. 「计划总高 == 排版器 `usedHeightPx`」这条断言本身是错的：两者口径刻意不同（测量期浮点 vs 渲染期 `roundToPx`），差距随段数累积。改为「计划总高不得溢出内容区」。

### 一个值得记住的口径结论

渲染几何用 **`roundToPx`**（与 `Modifier.padding` 一致），排版测量用 **浮点 `DP * density`**。两者刻意差 <1px：测量期少留只会让段落更早落页，反了才会出现「排版说放得下、渲染溢出」的底行被裁。这条已写入 `PageLayoutPlan.kt` 的 KDoc 与 `AGENTS.md`。

### 后续审计发现与本次修复目标

在继续 M6 拆分前对 M2–M5 和当前改动面做了复核，发现以下问题；它们属于本次修复范围，不能用“M1–M5 已验证”概括掉：

1. **M4 / bottomJustify**：分页器后来调整了 `lineHeightExtraPx`，但共享版面计划仍可能读取调整前的 `mainLayout` 高度，导致计划块高、Compose 正文和位图正文再次分叉。目标是让 `PageUnit` 携带 bottomJustify 后的最终有效布局，并补覆盖普通段落与双语气泡底边的回归用例。
2. **插图段距**：插图 Composable 内部使用固定 `10.dp`/布尔 spacer，绕过 `PageStyle.paragraphSpacingPx` 和版面计划；用户修改段距后，插图与后续段落的间距不一致。目标是由调用方传入实际段距，分页消费计划、滚动消费当前样式，并补插图后段距用例。
3. **连接测试 token**：`testConnection` 复用了用户的 `maxOutputTokens`，连接测试可能产生不必要的长输出和额度消耗。目标是把连接探针固定为 `maxTokens = 10`，并以请求体测试锁定。
4. **旧配置迁移日志**：`LlmLegacyKeysStore.migrateToProfile()` 的 `store.data.first()` 失败路径未落 `AppLog`，不在普通 `safeData` 回退覆盖范围内。目标是保留协程取消语义，其余异常记录后继续抛出，并补测试。
5. **Settings 注入**：M2 虽建立了 `VibeReadingApp.llmApiService` 组合根，但 `SettingsViewModel` 的连接测试仍自行创建服务，组合根没有完全收口。目标是经 `AppNavigation`/Factory 注入共享实例，消除最后一条旁路。
6. **章节并发翻译**：原全局单任务协调器会让“当前章翻译 + 提前翻译下一章”互相替换，也会让 App/Web 不同章节互相取消。**异章并行是明确的产品需求，不是本轮重构引入的回归**。目标是按 `bookId + chapterId` 管理任务：同章互斥幂等、异章并行；取消/重试定向到章节；删除书籍和修正原文语言前 `cancelBook`；翻译前台服务按活动任务集合管理。

## 8. 明确不做的（附理由，非遗漏）

- **`AppDatabase` 迁移链的样板提取**：13 个迁移中 9 个同构单列 `ALTER`、`4_5`/`5_6` 是双子。迁移 SQL 是**历史 schema 快照**，改错对已装机用户不可挽回，而 helper 只省约 30 行。收益远小于风险，保持逐条显式可审计。
- **`DictEntry` 与 `WordExplanation` 合并**：`pos` 的 nullability 差异是**语义正确**的（ECDICT 可能缺音标/词性，LLM prompt 强制所有字段返回）；`word`（精确查得的词形）与 `lemma`（规范化词条）也不同义。已写入 `CONTEXT.md` 术语表。
- **插图适配的两套算法统一**：分页是「整图缩进给定盒子」，滚动是「按声明比例的自然高度」，是不同问题；只把魔法数（4:3 兜底、宽高比夹取 0.2~5）命名进 `ReaderMetrics`。

## 9. M6 已完成：拆巨型文件与参数打包

M6 按“先补策略安全网，再逐段搬移”的顺序完成，未改变分页内容模型、位置口径和五种翻页语义。

1. **Reader 容器拆分**：`ReaderScreen.kt` 从 1360 行降到 **234 行**；卷页动画、分页会话、滚动会话、手势、内容体、浮层、系统栏/生命周期和顶层 action adapter 分别进入 `ReaderCurlController.kt`、`ReaderPagerSession.kt`、`ReaderScrollSession.kt`、`ReaderGestures.kt`、`ReaderContentSurface.kt`、`ReaderOverlays.kt`、`ReaderSystemUiEffects.kt`、`ReaderScreenSupport.kt`。`remember`/`LaunchedEffect` 的所有权与 key 保持在固定调用层，手势 MOVE 继续委托原 `PageCurl` 算法。
2. **参数打包**：新增 `ReaderLayoutSpec` 与 `ReaderContentInteractions`。`ReaderPager` 降为 **7** 个顶层参数，`PageRenderer` **4** 个，`ScrollReader` **6** 个，`PageInfoOverlays` **5** 个；`ScrollReader` 删除两个未使用参数。`LlmSettingsSheet` 从 26 个平铺参数（16 回调）收成 `UiState + Actions + accentColor`。
3. **其余巨型页面**：`BookshelfScreen.kt` 从 758 行降到 **198 行**，内容/卡片/菜单/对话框拆到 `BookshelfComponents.kt`、`BookshelfDialogs.kt`；`SettingsScreen.kt` 从 464 行降到 **166 行**，六个 Section 拆到 `SettingsSections.kt`。Activity Result launcher、权限申请和瞬时对话框状态仍由顶层页面持有。
4. **手势安全网**：三分区点按成为纯 `resolveReaderTapAction()`，覆盖左右/中间边界、滚动模式、单手模式、浮层/弹窗/选区优先级，以及仿真动画打断后不二次翻页。
5. **并发翻译语义**：协调器按 `bookId + chapterId` 管理任务，同章幂等、异章并行；Reader 只投影当前章的实时状态，切回旧章继续显示进度；Web 与 App 共用任务集合，重试/取消定向到章节，破坏性书籍操作先 `cancelBook`，前台服务按活动 run 集合持有。

**代价与修复（审查轮）**：两轴代码审查发现并已修掉 3 处真实问题：底部对齐把 slack 像素换算成 sp 时漏掉 `fontScale`（系统字体缩放 ≠1 时计划高度比渲染需要更高，已加 `fontScale` 透传 + 变异验证过的回归用例）；任务状态表只增不减（改为只留活动任务与失败提示，取消/完成后清理）；自动预译对 FAILED 下一章会形成无上限重试循环（预译只自动启动 PENDING，失败必须用户显式重试，同时让未写库的失败原因在状态面板可见）。

**最终验证**：单测全绿——**283 个用例 / 305 次执行**（`BubbleTapGestureTest`、`TextSelectionHitTest`、`CjkJustifyTest` 带多 SDK 变体，各跑 2 遍，故执行数比用例数多 22）；`:app:assembleDebug` 成功；按 `output-metadata.json` 选择 `app-x86_64-debug.apk` 安装到 `emulator-5554` 并启动 `com.vibereading.app` 成功，近期日志无 `FATAL EXCEPTION`；`git diff --check` 通过；`check_log_convention.py` 仍为历史基线 **16** 个需人工甄别点，本轮未新增。

## 10. 本轮触达的验收方式

每个里程碑：`./gradlew.bat :app:assembleDebug` → 按 `output-metadata.json` 选 ABI → `adb install` → 启动 → 确认无 `FATAL EXCEPTION` → `:app:testDebugUnitTest` → `python tools/check_log_convention.py` → 行尾噪音检查（`git diff --numstat --ignore-cr-at-eol` 比对）。
