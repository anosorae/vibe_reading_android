# ADR-002 — EPUB 导入策略与正文插图链接模型

- 状态：已接受
- 日期：2026-08-25
- 触发：新增 EPUB 格式支持，参考 `reference_code/legado-E` 的 EPUB 实现

## 背景

VibeReading 此前只支持 TXT：导入时全文解析进 Room（`ChapterEntity.content` 存 `\n\n` 分段纯文本），不保留原文件；翻译、分页、滚动、选词全部以「纯文本段落 + `[N]` 标记」为前提。

Legado 的 EPUB 做法：vendored epublib fork + `ParcelFileDescriptor` 随机访问 zip 懒加载；TOC 优先、spine 兜底切章（一个 xhtml 多章靠 fragmentId 切片）；Jsoup 提取正文时剥掉全部标签但保留 `<img>` 并把 src 重写为包内绝对路径；图片按需从 zip 解压到磁盘缓存。它不还原 CSS，产出本质是「分段纯文本 + 内嵌图片」。

两者的架构差异是本 ADR 的核心权衡点：我们已有导入即入库的内容管线，是否照搬 legado 的按需解压体系。

## 决策

### D1 导入即转换，放弃 legado 式按需解压

导入时一次性把 EPUB 解包转换为与 `TxtParser` 相同契约的「`\n\n` 分段纯文本章节」写进 chapters 表，不保留 `.epub` 原文件，不做阅读期 zip 随机访问和磁盘缓存层。理由：

- 翻译管线、`ReadingContentParser`、分页器、滚动模式、词典查词零改动复用；
- legado 懒加载解决的是「不转换就必须阅读期访问原文件」的问题，我们的架构里这个问题不存在；
- 单本书体量小（几 MB～几十 MB），Room 已承载 TXT 全文，无新压力。

依赖只加 Jsoup（HTML→文本）；zip 用 JDK 自带 `java.util.zip`。不 vendor epublib——它的价值在懒加载随机访问，我们用不上。DRM 书检测 `META-INF/encryption.xml` 存在即报明确错误，不支持解密。

### D2 章节切分照搬 legado 骨架

TOC（EPUB2 `toc.ncx` / EPUB3 `nav.xhtml`）优先对齐 spine；TOC 缺失或解析失败回退 spine（标题取 xhtml `<title>` 兜底）。一个 xhtml 含多个 TOC 锚点时按 fragmentId 在 HTML 字符串上切片。TOC 树的父节点映射为 `ChapterEntity.section`（卷）。spine 中位于首条 TOC 之前的页面（封面页/扉页）补为「卷首」章。

### D3 插图 = 独立段落的插图链接

正文中 `<img>` 一律强制独立成段（原文本段从图片处断开），表示为一个真实存在于 content 字符串中的类 markdown 链接：

```
![插图](vrimg://{key} {width}x{height})
```

- `{key}` 是包内资源键，运行时解析到 `filesDir/books/{bookId}/images/{key}`（导入期解压落盘，`.tmp`+rename 原子写入惯例）；
- 尺寸在导入期解码 bitmap bounds 后写入链接本身，使排版保持纯 Kotlin 同步计算，不存在第二个尺寸数据源；
- 双语两侧共用同一张图，无原文气泡；插图块不可拆、不参与选词；
- 格式范围 PNG/JPG/WebP/GIF 静态帧；SVG 与加载失败渲染占位框；
- 删除书籍时同步清理其私有目录下的插图与封面文件。

**考虑过的替代方案**（拒绝理由）：
- *CSS/富文本还原*——违背统一 `PageStyle` 排版体系（AGENTS.md 禁止另起排版），且富文本进不了译文 `[N]` 对齐管线；
- *独立 images 表存尺寸*——多一实体一 DAO，链接自足性丧失，分页需先查库异步化；
- *段内行内小图*——破坏两端对齐、BreakIterator 选词、offset 映射的纯文本前提。

### D4 翻译编号空洞

插图段参与章节的 `[N]` 编号，但构建翻译 prompt 时跳过其内容（编号出现空洞，如发送 `[1] [2] [4]`）。模型输出自然缺失 `[3]`，由 `parseBilingualParagraphs` 现有兜底接住（原文保留、空译文）。解析器零改动；不采用占位符回显（LLM 回显失败会污染译文）和重编号映射（违背单一数据源）。

### D5 分页与交互口径

`FlowItem`/`PageUnit`/`ScrollItem` 各新增 Image 变体，作为固定高度内容单元：

- **分页模式**：宽度铺满内容区后若超高，整体缩小适配单页内（不做跨页条带拆分，避免「单元高于页」死局；长图拆页留作后续增量）;
- **滚动模式**：等比缩放取自然高度，LazyColumn 天然承载；
- 点击插图打开全屏预览（`graphicsLayer` 双指缩放/拖动），预览与气泡同类，是视觉叠加层不参与排版测量。

### D6 元数据收敛

Room v9 迁移 `books` 表加 `format TEXT DEFAULT 'txt'` 与 `coverPath TEXT` 两列；EPUB 内置封面落盘供书架显示，TXT 保持渐变占位。不加作者列（OPF `dc:creator` 丢弃）、不做书架格式徽标——格式对用户不可见，阅读体验完全一致。

## 影响

- Room schema v8→v9：手写 `MIGRATION_8_9` 注册进迁移链，schema 导出到 `app/schemas`。
- 新增 `domain/parser/EpubParser.kt`（纯 Kotlin 核心，尺寸解码注入以便 JVM 单测）、`BookImageStore`（插图/封面文件读写、内存位图缓存、删书清理）。
- 改动点：`BookshelfScreen` MIME 扩展、`BookshelfViewModel.uploadBook` 按扩展名分流、`TextPaginator`/`ReaderScroll`/`ReadingContentRenderer` 增加 Image 分支、`BookCover` 支持本地封面。
- AGENTS.md 目录结构与 gotchas 在实现落地时同步更新。
