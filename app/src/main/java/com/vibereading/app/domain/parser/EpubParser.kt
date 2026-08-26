package com.vibereading.app.domain.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/** 结构占位标题：首个 TOC 条目前的 spine 页合并为「卷首」（ADR-002 D2）。 */
const val FRONT_MATTER_TITLE = "卷首"

/** 结构占位标题：末个 TOC 资源之后的 spine 页合并为「书末」（ADR-002 D2）。 */
const val BACK_MATTER_TITLE = "书末"

/**
 * 卷首/书末是解析器生成的结构占位标题，随书籍原文语言本地化（ADR-003）：
 * 英文原版书显示 Front Matter / Back Matter，中文书保持「卷首/书末」。
 */
fun localizeGeneratedTitle(title: String, sourceLanguage: String): String {
    if (sourceLanguage == SourceLanguageDetector.ZH) return title
    return when (title) {
        FRONT_MATTER_TITLE -> "Front Matter"
        BACK_MATTER_TITLE -> "Back Matter"
        else -> title
    }
}

/**
 * EPUB 解析（ADR-002 D1/D2）：导入期一次性把 EPUB 容器转换为与 [TxtParser] 相同契约的
 * 「`\n\n` 分段纯文本章节」结构。纯 Kotlin + Jsoup，可 JVM 单测。
 *
 * - 章节：TOC（EPUB2 toc.ncx / EPUB3 nav.xhtml）优先，TOC 缺失回退 spine（标题取 xhtml `<title>`）；
 *   同一 xhtml 的多个 TOC 锚点按锚点所在的 body 直接子元素切块；锚点找不到时并入前一条目
 *   （保留内容、丢弃该标题）；首个 TOC 条目之前的 spine 页面合并为「卷首」章，之后的并入
 *   「书末」章——都不静默丢内容；
 * - 正文：块级元素逐段取文本，`<img>` 一律强制独立成段（原文本段从图片处断开）；
 *   序列开头与章节标题相同的标题块剔除一次；
 * - 加密：检测到 META-INF/encryption.xml 直接抛 [EpubDrmException]，不做解密。
 */
object EpubParser {

    /** DRM 加密 EPUB：明确报错，不支持解密（ADR-002 D1）。 */
    class EpubDrmException(message: String) : Exception(message)

    class InvalidEpubException(message: String) : Exception(message)

    data class EpubMeta(val title: String?, val author: String?)

    /** 章节内的一个段落：文本或插图。插图独立成段，不与文字混排。 */
    sealed class Paragraph {
        data class Text(val value: String) : Paragraph()
        data class Image(
            val zipHref: String,      // 包内归一化 href（URL 解码、小写）
            val widthPx: Int,
            val heightPx: Int
        ) : Paragraph()
    }

    data class EpubChapter(
        val title: String,
        val section: String? = null,
        val paragraphs: List<Paragraph>
    )

    data class EpubBook(
        val meta: EpubMeta,
        val coverBytes: ByteArray?,
        val chapters: List<EpubChapter>,
        /** 包内图片资源：归一化 href → 字节。 */
        val images: Map<String, ByteArray>
    )

    private data class ManifestItem(val href: String, val mediaType: String, val properties: String)

    /**
     * 解析 EPUB 字节。[decodeSize] 解码图片像素尺寸（生产用 BitmapFactory bounds，
     * 单测注入桩）；返回 null 时用 [DEFAULT_IMG_W]×[DEFAULT_IMG_H] 占位。
     */
    fun parse(
        bytes: ByteArray,
        decodeSize: (ByteArray) -> Pair<Int, Int>? = { null }
    ): EpubBook {
        val entries = readZip(bytes)
        if (entries.containsKey(ENCRYPTION_XML)) {
            throw EpubDrmException("暂不支持加密（DRM）的 EPUB 书籍")
        }
        val container = entries[CONTAINER_XML]
            ?: throw InvalidEpubException("不是有效的 EPUB 文件（缺少 META-INF/container.xml）")
        val opfPath = parseContainer(container)
            ?: throw InvalidEpubException("EPUB 缺少 OPF 描述文件")
        val opfBytes = entries[normalize(opfPath)]
            ?: throw InvalidEpubException("EPUB 的 OPF 文件缺失: $opfPath")
        val opfBase = dirOf(normalize(opfPath))
        val opf = Jsoup.parse(String(opfBytes, StandardCharsets.UTF_8), "", Parser.xmlParser())

        val metaEl = opf.selectFirst("metadata")
        val title = metaEl?.children()
            ?.firstOrNull { it.tagName().endsWith("title") }?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val author = metaEl?.children()
            ?.firstOrNull { it.tagName().endsWith("creator") }?.text()?.trim()

        val manifest = HashMap<String, ManifestItem>()
        opf.select("manifest").select("> item").forEach { item ->
            val id = item.attr("id")
            val href = normalize(resolve(opfBase, item.attr("href")))
            if (id.isNotEmpty() && href.isNotEmpty()) {
                manifest[id] = ManifestItem(href, item.attr("media-type"), item.attr("properties"))
            }
        }

        val spineRefs = opf.select("spine").select("> itemref")
            .mapNotNull { ref ->
                manifest[ref.attr("idref")]?.takeIf { it.mediaType.contains("xhtml") || it.mediaType.contains("html") }
            }
        if (spineRefs.isEmpty()) throw InvalidEpubException("EPUB 没有可读的章节内容（spine 为空）")

        val images = HashMap<String, ByteArray>()
        manifest.values.forEach { item ->
            if (item.mediaType.startsWith("image/")) entries[item.href]?.let { images[item.href] = it }
        }
        // 兜底：收录 zip 内存在但未在 manifest 声明的图片（盗版/转制 EPUB 常见，如 178.com 转制书
        // 的插图页只写 <img src> 不声明 manifest 项）。manifest 声明优先，扩展名白名单判定。
        val imageExts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
        entries.forEach { (href, bytes) ->
            if (href !in images) {
                val ext = href.substringAfterLast('.', "").lowercase()
                if (ext in imageExts) images[href] = bytes
            }
        }

        val tocEntries = parseToc(entries, manifest.values.toList(), spineRefs)
        val rawChapters = if (tocEntries.any { it.href.isNotEmpty() }) {
            chaptersFromToc(spineRefs.map { it.href }, tocEntries, entries)
        } else {
            fallbackSpineChapters(spineRefs.map { it.href }, entries)
        }

        val chapters = rawChapters.map { raw ->
            EpubChapter(raw.title, raw.section, extractParagraphs(raw.elements, raw.title, images, decodeSize))
        }
        return EpubBook(EpubMeta(title, author), findCover(metaEl, manifest, images), chapters, images)
    }

    /**
     * 把解析结果物化为 ChapterDict 列表（与 TxtParser 相同的 `\n\n` 分段契约）。
     * [nameOf] 把包内图片 href 映射为落盘文件名；返回 null 时该插图被丢弃。
     * [sourceLanguage]（ADR-003）用于本地化「卷首/书末」结构占位标题；
     * 内容全空的章节（如纯封面页产生的空卷首）直接剔除，避免出现空白页。
     */
    fun toChapterDicts(
        bookId: Long,
        book: EpubBook,
        nameOf: (zipHref: String) -> String?,
        sourceLanguage: String = SourceLanguageDetector.ZH
    ): List<TxtParser.ChapterDict> {
        return book.chapters.mapNotNull { chapter ->
            val parts = ArrayList<String>()
            chapter.paragraphs.forEach { para ->
                when (para) {
                    is Paragraph.Text -> parts += para.value.trim()
                    is Paragraph.Image -> nameOf(para.zipHref)?.let { fileName ->
                        parts += IllustrationLink.build("$bookId/$fileName", para.widthPx, para.heightPx)
                    }
                }
            }
            val content = parts.filter { it.isNotEmpty() }.joinToString("\n\n")
            if (content.isEmpty()) return@mapNotNull null
            TxtParser.ChapterDict(
                title = localizeGeneratedTitle(chapter.title, sourceLanguage),
                section = chapter.section,
                content = content
            )
        }
    }

    // ── 内部结构 ──

    private data class TocEntry(
        val href: String,       // 归一化、不含 fragment；页内锚点条目为 ""
        val fragmentId: String?,
        val title: String,
        val section: String?
    )

    private data class RawChapter(
        val title: String,
        val section: String?,
        /** 该章覆盖的 body 直接子元素序列（可能来自多个文件的拼接）。 */
        val elements: List<Element>
    )

    private const val CONTAINER_XML = "meta-inf/container.xml"
    private const val ENCRYPTION_XML = "meta-inf/encryption.xml"

    // ── zip 与 href ──

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val result = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = normalize(entry.name)
                if (name.isNotEmpty() && !entry.isDirectory) {
                    result[name] = zis.readBytes()
                }
                zis.closeEntry()
            }
        }
        return result
    }

    /** 归一化：小写、URL 解码、剥前导 ./ 与 /。zip 条目名与 href 都过这里再互查。 */
    private fun normalize(href: String): String {
        var h = href.trim().replace('\\', '/')
        h = try { URLDecoder.decode(h, StandardCharsets.UTF_8.name()) } catch (_: Exception) { h }
        while (h.startsWith("./")) h = h.substring(2)
        while (h.startsWith("/")) h = h.substring(1)
        return h.lowercase()
    }

    /** 相对 href 基于 OPF/NCX 所在目录解析；手工处理 ../ 与 ./（URI.resolve 会丢掉基路径，不可用）。 */
    private fun resolve(baseDir: String, href: String): String {
        val raw = href.trim()
        if (raw.isEmpty()) return raw
        // 带 scheme 的外链（http:/mailto:）原样返回
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw)) return raw
        val stack = ArrayDeque(baseDir.split('/').filter { it.isNotEmpty() })
        raw.split('/').forEach { seg ->
            when (seg) {
                ".", "" -> {}
                ".." -> stack.removeLastOrNull()
                else -> stack.addLast(seg)
            }
        }
        return stack.joinToString("/")
    }

    private fun dirOf(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx >= 0) path.substring(0, idx) else ""
    }

    private fun splitFragment(href: String): Pair<String, String?> {
        val idx = href.indexOf('#')
        return if (idx >= 0) href.substring(0, idx) to href.substring(idx + 1).takeIf { it.isNotEmpty() }
        else href to null
    }

    private fun bodyChildren(entries: Map<String, ByteArray>, href: String): List<Element> =
        entries[href]?.let { Jsoup.parse(String(it, StandardCharsets.UTF_8)).body() }?.children().orEmpty()

    // ── container.xml / OPF ──

    private fun parseContainer(bytes: ByteArray): String? {
        val doc = Jsoup.parse(String(bytes, StandardCharsets.UTF_8), "", Parser.xmlParser())
        return doc.selectFirst("rootfile")?.attr("full-path")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun findCover(
        metaEl: Element?,
        manifest: Map<String, ManifestItem>,
        images: Map<String, ByteArray>
    ): ByteArray? {
        // 1) OPF <meta name="cover" content="itemId">
        val coverId = metaEl?.children()
            ?.firstOrNull { it.tagName() == "meta" && it.attr("name").equals("cover", true) }
            ?.attr("content")?.trim().orEmpty()
        manifest[coverId]?.let { item ->
            if (item.mediaType.startsWith("image/")) images[item.href]?.let { return it }
        }
        // 2) manifest 中 id/href 含 cover 的图片
        manifest.entries.firstOrNull { (id, item) ->
            item.mediaType.startsWith("image/") &&
                (id.contains("cover", true) || item.href.contains("cover", true))
        }?.let { images[it.value.href]?.let { b -> return b } }
        // 3) 包内任意名为 cover 的图片资源
        images.entries.firstOrNull { it.key.contains("cover", true) }?.value?.let { return it }
        return null
    }

    // ── TOC ──

    private fun parseToc(
        entries: Map<String, ByteArray>,
        manifestItems: List<ManifestItem>,
        spineRefs: List<ManifestItem>
    ): List<TocEntry> {
        val ncxItem = manifestItems.firstOrNull {
            it.mediaType == "application/x-dtbncx+xml" || it.href.endsWith(".ncx")
        }
        val navItem = manifestItems.firstOrNull {
            it.properties.split(Regex("\\s+")).contains("nav")
        }
        val fromNcx = ncxItem?.let { entries[it.href]?.let { b -> parseNcx(b, dirOf(it.href)) } }
        val toc = fromNcx ?: navItem?.let { entries[it.href]?.let { b -> parseNavXhtml(b, dirOf(it.href)) } }.orEmpty()
        if (toc.isNotEmpty()) return toc

        // spine 兜底：每个 spine 资源一章，标题取 xhtml <title>
        return spineRefs.mapIndexed { index, item ->
            TocEntry(item.href, null, docTitleOf(entries, item.href) ?: "第${index + 1}节", null)
        }
    }

    private fun docTitleOf(entries: Map<String, ByteArray>, href: String): String? {
        val bytes = entries[href] ?: return null
        val t = Jsoup.parse(String(bytes, StandardCharsets.UTF_8)).selectFirst("title")?.text()?.trim()
        return t?.takeIf { it.isNotEmpty() }
    }

    /** EPUB2 toc.ncx：navPoint 递归，子节点挂父节点标题为卷。 */
    private fun parseNcx(bytes: ByteArray, baseDir: String): List<TocEntry> {
        val doc = Jsoup.parse(String(bytes, StandardCharsets.UTF_8), "", Parser.xmlParser())
        val result = ArrayList<TocEntry>()

        fun walk(points: List<Element>, section: String?) {
            points.forEach { point ->
                val label = point.children().firstOrNull { it.tagName() == "navLabel" }
                    ?.text()?.trim().orEmpty()
                val src = point.children().firstOrNull { it.tagName() == "content" }
                    ?.attr("src")?.trim().orEmpty()
                if (src.isNotEmpty()) {
                    val (href, frag) = splitFragment(resolve(baseDir, src))
                    result += TocEntry(normalize(href), frag, label.ifEmpty { "未命名" }, section)
                }
                walk(point.children().filter { it.tagName() == "navPoint" }, section ?: label.ifEmpty { null })
            }
        }
        val navMap = doc.selectFirst("navMap")
        walk(navMap?.children()?.filter { it.tagName() == "navPoint" }.orEmpty(), null)
        return result
    }

    /** EPUB3 nav.xhtml：ol/li/a 嵌套，子 ol 挂父条目文本为卷。 */
    private fun parseNavXhtml(bytes: ByteArray, baseDir: String): List<TocEntry> {
        val doc = Jsoup.parse(String(bytes, StandardCharsets.UTF_8))
        val result = ArrayList<TocEntry>()

        fun walkList(list: Element, section: String?) {
            list.children().filter { it.tagName() == "li" }.forEach { li ->
                val anchor = li.children().firstOrNull { it.tagName() == "a" }
                val nested = li.children().filter { it.tagName() == "ol" }
                if (anchor != null) {
                    val href = anchor.attr("href").trim()
                    val title = anchor.text().trim()
                    if (!href.contains(":")) { // mailto/http 等外链跳过
                        if (href.startsWith("#")) {
                            result += TocEntry("", href.substring(1).takeIf { it.isNotEmpty() }, title.ifEmpty { "未命名" }, section)
                        } else {
                            val (base, frag) = splitFragment(resolve(baseDir, href))
                            result += TocEntry(normalize(base), frag, title.ifEmpty { "未命名" }, section)
                        }
                    }
                    nested.forEach { walkList(it, section ?: title.ifEmpty { null }) }
                } else {
                    nested.forEach { walkList(it, section) }
                }
            }
        }
        val lists = doc.select("nav").firstOrNull()?.children()?.filter { it.tagName() == "ol" }
            ?: doc.body()?.children()?.filter { it.tagName() == "ol" }.orEmpty()
        lists.forEach { walkList(it, null) }
        return result
    }

    // ── 章节切分 ──

    /**
     * TOC 对齐 spine：
     * - 首个被 TOC 引用的资源之前的 spine 页面合并为「卷首」章；
     * - TOC 条目按 spine 顺序成章；同一文件的多个锚点按锚点所在 body 直接子元素切块；
     * - 末个 TOC 资源之后的 spine 页面合并为「书末」章。
     */
    private fun chaptersFromToc(
        spineHrefs: List<String>,
        toc: List<TocEntry>,
        entries: Map<String, ByteArray>
    ): List<RawChapter> {
        val spineOrder = HashMap<String, Int>()
        spineHrefs.forEachIndexed { i, href -> spineOrder.putIfAbsent(href, i) }

        val anchored = toc.filter { it.href.isNotEmpty() && spineOrder.containsKey(it.href) }
        if (anchored.isEmpty()) return emptyList()
        val firstIdx = anchored.minOf { spineOrder.getValue(it.href) }
        val lastIdx = anchored.maxOf { spineOrder.getValue(it.href) }

        val chapters = ArrayList<RawChapter>()

        // 卷首：首个 TOC 资源之前的 spine 页面
        val front = ArrayList<Element>()
        for (i in 0 until firstIdx) front += bodyChildren(entries, spineHrefs[i])
        if (front.hasTextOrImage()) chapters += RawChapter(FRONT_MATTER_TITLE, null, front)

        // TOC 章：按 base href 分组（保持出现序），组内多锚点切块
        val grouped = LinkedHashMap<String, MutableList<TocEntry>>()
        anchored.forEach { entry -> grouped.getOrPut(entry.href) { mutableListOf() }.add(entry) }

        grouped.forEach { (href, group) ->
            val children = bodyChildren(entries, href)
            if (children.isEmpty()) {
                group.forEach { chapters += RawChapter(it.title, it.section, emptyList()) }
                return@forEach
            }
            val docBody = children.first().ownerDocument()?.body() ?: return@forEach
            // 锚点位置：每个条目在其文件的直接子元素序列中的起点；找不到记 -1（并入前一条目）
            val cuts = group.map { entry ->
                if (entry.fragmentId == null) 0
                else indexOfAnchor(children, docBody, entry.fragmentId)
            }
            group.forEachIndexed { gi, entry ->
                val start = cuts[gi]
                when {
                    // 锚点定位失败：并给前一条目（保留内容、丢标题）；组首失败则独占整文件
                    start < 0 -> if (gi == 0) chapters += RawChapter(entry.title, entry.section, children)
                    else -> {
                        val end = ((gi + 1) until group.size)
                            .map { cuts[it] }
                            .filter { it > start }
                            .minOrNull() ?: children.size
                        // 同一位置的重复锚点并给前者（不重复渲染同一段内容）
                        if (gi == 0 || cuts[gi - 1] != start) {
                            chapters += RawChapter(
                                entry.title,
                                entry.section,
                                children.subList(start, minOf(end, children.size))
                            )
                        }
                    }
                }
            }
        }

        // 书末：最后一个 TOC 资源之后的 spine 页面
        val back = ArrayList<Element>()
        for (i in (lastIdx + 1)..spineHrefs.lastIndex) back += bodyChildren(entries, spineHrefs[i])
        if (back.hasTextOrImage()) chapters += RawChapter(BACK_MATTER_TITLE, null, back)

        return chapters
    }

    /** TOC 全缺：整个 spine 每资源一章。 */
    private fun fallbackSpineChapters(spineHrefs: List<String>, entries: Map<String, ByteArray>): List<RawChapter> =
        spineHrefs.mapIndexed { index, href ->
            RawChapter(docTitleOf(entries, href) ?: "第${index + 1}节", null, bodyChildren(entries, href))
        }

    private fun List<Element>.hasTextOrImage(): Boolean =
        any { el -> el.select("img, image").isNotEmpty() || el.text().isNotBlank() }

    /** 在 body 直接子元素中找到包含锚点的那个的下标；找不到返回 -1。 */
    private fun indexOfAnchor(children: List<Element>, body: Element, fragmentId: String): Int {
        val anchor = body.getElementById(fragmentId)
            ?: body.select("[name=$fragmentId]").firstOrNull()
            ?: return -1
        var cur: Element = anchor
        while (cur.parent() != null && cur.parent() !== body) {
            cur = cur.parent() ?: return -1
        }
        return children.indexOfFirst { it === cur }
    }

    // ── 正文段落提取 ──

    private val BLOCK_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "li", "dd", "dt", "figcaption", "td", "th"
    )
    private val NOISE_TAGS = setOf("script", "style", "hr", "link", "meta", "head", "title")

    /**
     * 从 body 直接子元素序列提取段落：
     * - 块级元素取全部文本（行内嵌套自然展开），`<br>` 转 `\n`；
     * - 块内含图时按文档序把文本与图片切开，图片强制独立成段；
     * - div/article/section 等容器递归展开；script/style/hr 等噪音跳过；
     * - 序列开头与章节标题相同的标题块剔除一次。
     */
    private fun extractParagraphs(
        elements: List<Element>,
        chapterTitle: String,
        images: Map<String, ByteArray>,
        decodeSize: (ByteArray) -> Pair<Int, Int>?
    ): List<Paragraph> {
        val out = ArrayList<Paragraph>()

        fun pushText(raw: String) {
            val text = raw.replace('\r', ' ')
                .replace(Regex("[ \\t]*\\n[ \\t\\n]*"), "\n")
                .trim()
            if (text.isNotEmpty()) out += Paragraph.Text(text)
        }

        fun addImage(el: Element) {
            val src = el.attr("src").ifEmpty { el.attr("xlink:href") }.trim()
            if (src.isEmpty() || src.startsWith("data:") || src.contains(':')) return
            // 章节内 src 相对「该章节文件」解析；提取阶段已丢失每文件目录，
            // 用包内路径后缀匹配定位资源（EPUB 内同名不同目录的图片极罕见）
            val href = normalize(src)
            val key = images.keys.firstOrNull { it == href || it.endsWith("/$href") } ?: return
            val bytes = images.getValue(key)
            val (w, h) = decodeSize(bytes) ?: Pair(DEFAULT_IMG_W, DEFAULT_IMG_H)
            out += Paragraph.Image(zipHref = key, widthPx = w, heightPx = h)
        }

        /** 处理一个块级元素：含图时把文本与图片按文档序切开。 */
        fun handleBlock(block: Element) {
            val imgs = block.select("img, image")
            if (imgs.isEmpty()) {
                pushText(textWithBreaks(block))
                return
            }
            val clone = block.clone()
            clone.select("img, image").forEach { it.replaceWith(org.jsoup.nodes.TextNode(IMG_PLACEHOLDER)) }
            val segments = textWithBreaks(clone).split(IMG_PLACEHOLDER)
            imgs.forEachIndexed { ii, img ->
                pushText(segments.getOrNull(ii).orEmpty())
                addImage(img) // src 取自原树元素
            }
            pushText(segments.lastOrNull().orEmpty())
        }

        fun walkContainer(container: Element) {
            container.children().forEach { child ->
                when {
                    child.tagName() in BLOCK_TAGS -> handleBlock(child)
                    child.tagName() == "img" || child.tagName() == "image" -> addImage(child)
                    child.tagName() in NOISE_TAGS -> {}
                    else -> walkContainer(child)
                }
            }
            if (container.children().none { it.tagName() !in NOISE_TAGS }) {
                pushText(container.ownText())
            }
        }

        var headingStripped = false
        elements.forEach { el ->
            if (!headingStripped &&
                el.tagName() in HEADING_TAGS &&
                titlesMatch(el.text(), chapterTitle)
            ) {
                headingStripped = true
                return@forEach
            }
            when {
                el.tagName() in BLOCK_TAGS -> handleBlock(el)
                el.tagName() == "img" || el.tagName() == "image" -> addImage(el)
                el.tagName() in NOISE_TAGS -> {}
                else -> walkContainer(el)
            }
        }
        return out
    }

    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    /** 元素文本：`<br>` 转 \n，其余原样（空白规整交给 pushText）。 */
    private fun textWithBreaks(el: Element): String {
        val clone = el.clone()
        clone.select("br").forEach { it.replaceWith(org.jsoup.nodes.TextNode("\n")) }
        return clone.wholeText()
    }

    private const val IMG_PLACEHOLDER = "\u0000IMG\u0000"

    private fun titlesMatch(a: String, b: String): Boolean {
        val na = a.trim().replace(Regex("\\s+"), "")
        val nb = b.trim().replace(Regex("\\s+"), "")
        return na.isNotEmpty() && (na.equals(nb, true) || nb.endsWith(na) || na.endsWith(nb))
    }

    /** 尺寸未知的图片（如 SVG）占位尺寸，保证链接语法完整且显示比例合理。 */
    const val DEFAULT_IMG_W = 800
    const val DEFAULT_IMG_H = 600
}
