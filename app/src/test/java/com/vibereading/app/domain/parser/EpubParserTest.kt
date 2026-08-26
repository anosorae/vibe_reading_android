package com.vibereading.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EpubParser 单测（ADR-002 D1/D2）：用 ZipOutputStream 程序化构造迷你 EPUB，
 * 覆盖 container/OPF/NCX 解析、TOC 优先 + spine 兜底、fragmentId 切桶、卷首/书末、
 * 图片独立成段与尺寸注入、DRM 报错、`\n\n` 段落契约。
 */
class EpubParserTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun str(s: String) = s.toByteArray(Charsets.UTF_8)

    /** 固定尺寸解码桩：任何图片都报 120x60。传 null 时模拟解码失败走默认占位。 */
    private val sizeStub: (ByteArray) -> Pair<Int, Int> = { 120 to 60 }

    // ── 迷你 EPUB 构造 ──

    private val containerXml = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

    private fun opfXml(includeNcx: Boolean, includeCoverMeta: Boolean = true) = """
<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:title>测试之书</dc:title><dc:creator>某作者</dc:creator>
${if (includeCoverMeta) "<meta name=\"cover\" content=\"cover-image\"/>" else ""}
</metadata>
<manifest>
<item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
<item id="c1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
<item id="c2" href="chap2.xhtml" media-type="application/xhtml+xml"/>
${if (includeNcx) "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>" else ""}
<item id="img1" href="images/i.png" media-type="image/png"/>
<item id="cover-image" href="images/cover.jpg" media-type="image/jpeg"/>
</manifest>
<spine${if (includeNcx) " toc=\"ncx\"" else ""}>
<itemref idref="cover"/><itemref idref="c1"/><itemref idref="c2"/>
</spine>
</package>"""

    private val ncxXml = """<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<navMap>
<navPoint id="n1"><navLabel><text>前言</text></navLabel><content src="chap1.xhtml"/></navPoint>
<navPoint id="n2"><navLabel><text>第一章</text></navLabel><content src="chap1.xhtml#sec2"/></navPoint>
<navPoint id="n3"><navLabel><text>第二章</text></navLabel><content src="chap2.xhtml"/></navPoint>
</navMap>
</ncx>"""

    private val coverXhtml = """<html><head><title>封面</title></head>
<body><p class="center"><img src="images/cover.jpg"/></p></body></html>"""

    // 一个 xhtml 含两个 TOC 锚点（前言=文件头，第一章=#sec2）
    private val chap1Xhtml = """<html><head><title>前言</title></head>
<body><h3>前言</h3><p>第一段内容。</p><p id="sec2">第二段内容。</p><p><img src="images/i.png"/>图后文字。</p></body></html>"""

    private val chap2Xhtml = """<html><head><title>第二章</title></head>
<body><h3>第二章</h3><p>第二章正文。</p></body></html>"""

    private fun buildEpub(
        includeNcx: Boolean = true,
        encryptionXml: Boolean = false,
        dropContainer: Boolean = false,
        ncx: String = ncxXml
    ): ByteArray {
        val entries = mutableListOf<Pair<String, ByteArray>>(
            "mimetype" to str("application/epub+zip"),
            "META-INF/container.xml" to str(containerXml),
            "OEBPS/content.opf" to str(opfXml(includeNcx)),
            "OEBPS/cover.xhtml" to str(coverXhtml),
            "OEBPS/chap1.xhtml" to str(chap1Xhtml),
            "OEBPS/chap2.xhtml" to str(chap2Xhtml),
            "OEBPS/images/i.png" to byteArrayOf(1, 2, 3),
            "OEBPS/images/cover.jpg" to byteArrayOf(9, 8, 7)
        )
        if (includeNcx) entries += "OEBPS/toc.ncx" to str(ncx)
        if (encryptionXml) entries += "META-INF/encryption.xml" to str("<encryption/>")
        if (dropContainer) entries.removeIf { it.first == "META-INF/container.xml" }
        return zipOf(*entries.toTypedArray())
    }

    // ── 用例 ──

    @Test
    fun drm_epubRejected() {
        try {
            EpubParser.parse(buildEpub(encryptionXml = true), sizeStub)
            throw AssertionError("应抛出 DRM 异常")
        } catch (e: EpubParser.EpubDrmException) {
            assertTrue(e.message!!.contains("加密"))
        }
    }

    @Test
    fun invalidContainer_rejected() {
        try {
            EpubParser.parse(buildEpub(dropContainer = true), sizeStub)
            throw AssertionError("应抛出无效 EPUB 异常")
        } catch (e: EpubParser.InvalidEpubException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun tocFirst_splitByFragment_frontMatterAndImages() {
        val book = EpubParser.parse(buildEpub(), sizeStub)
        assertEquals("测试之书", book.meta.title)
        assertEquals("某作者", book.meta.author)
        // 封面字节来自 meta name="cover"
        assertNotNull(book.coverBytes)

        // 卷首（封面页）+ 前言 + 第一章（#sec2 切桶）+ 第二章；无书末页
        assertEquals(listOf("卷首", "前言", "第一章", "第二章"), book.chapters.map { it.title })

        // 卷首：纯图页 → 独立插图段
        val front = book.chapters[0].paragraphs
        assertEquals(1, front.size)
        val coverImg = front[0] as EpubParser.Paragraph.Image
        assertEquals("oebps/images/cover.jpg", coverImg.zipHref)
        assertEquals(120, coverImg.widthPx)

        // 前言桶：开头与章节标题重复的 h3 被剔除，只剩文本段
        val preface = book.chapters[1].paragraphs
        assertEquals(listOf<EpubParser.Paragraph>(EpubParser.Paragraph.Text("第一段内容。")), preface)

        // 第一章桶：从 #sec2 起；图片强制独立成段且把所在 <p> 的文字断开
        val ch1 = book.chapters[2].paragraphs
        assertEquals(3, ch1.size)
        assertEquals(EpubParser.Paragraph.Text("第二段内容。"), ch1[0])
        assertTrue(ch1[1] is EpubParser.Paragraph.Image)
        assertEquals("oebps/images/i.png", (ch1[1] as EpubParser.Paragraph.Image).zipHref)
        assertEquals(EpubParser.Paragraph.Text("图后文字。"), ch1[2])

        // 第二章：开头 h3 与章节标题相同被剔除
        val ch2 = book.chapters[3].paragraphs
        assertEquals(listOf<EpubParser.Paragraph>(EpubParser.Paragraph.Text("第二章正文。")), ch2)
    }

    @Test
    fun noToc_fallsBackToSpineWithTitleTag() {
        val book = EpubParser.parse(buildEpub(includeNcx = false), sizeStub)
        // TOC 缺失：每个 spine 资源一章，标题取 xhtml <title>
        assertEquals(listOf("封面", "前言", "第二章"), book.chapters.map { it.title })
    }

    @Test
    fun decodeFailure_usesPlaceholderDims() {
        val book = EpubParser.parse(buildEpub()) { null }
        val img = book.chapters[0].paragraphs[0] as EpubParser.Paragraph.Image
        assertEquals(EpubParser.DEFAULT_IMG_W, img.widthPx)
        assertEquals(EpubParser.DEFAULT_IMG_H, img.heightPx)
    }

    @Test
    fun toChapterDicts_producesTxtContractWithIllustrationLinks() {
        val book = EpubParser.parse(buildEpub(), sizeStub)
        val dicts = EpubParser.toChapterDicts(bookId = 7L, book = book, nameOf = { href ->
            when (href) {
                "oebps/images/cover.jpg" -> "aaaa1111.jpg"
                "oebps/images/i.png" -> "bbbb2222.png"
                else -> null
            }
        })
        // 契约校验：content 按 \n\n 分段后每段都能被 ReadingContentParser 还原为同数量段落
        dicts.forEach { dict ->
            val parts = dict.content.split("\n\n")
            parts.forEach { part ->
                if (part.isNotEmpty()) {
                    // 链接段或文本段都必须整段可解析（无内嵌换行断裂）
                    assertTrue(part.isNotBlank())
                }
            }
        }

        // 插图链接语法正确（bookId 注入）
        val ch1Dict = dicts.first { it.title == "第一章" }
        assertTrue(ch1Dict.content.contains(IllustrationLink.build("7/bbbb2222.png", 120, 60)))

        // 未知 href 的插图被丢弃而不是产出坏链接
        val droppedDicts = EpubParser.toChapterDicts(7L, book, nameOf = { null })
        droppedDicts.forEach { d ->
            d.content.split("\n\n").forEach { p ->
                assertNull("丢弃的插图不应残留链接", IllustrationLink.parse(p.trim())?.let { p })
            }
        }
    }

    @Test
    fun imageMissingFromManifest_stillExtracted() {
        // 盗版/转制 EPUB 常见：插图在 zip 里但 manifest 不声明。兜底收录后应正常成段。
        val opf = opfXml(includeNcx = false).replace(
            "<item id=\"img1\" href=\"images/i.png\" media-type=\"image/png\"/>", ""
        )
        val epub = zipOf(
            "mimetype" to str("application/epub+zip"),
            "META-INF/container.xml" to str(containerXml),
            "OEBPS/content.opf" to str(opf),
            "OEBPS/cover.xhtml" to str(coverXhtml),
            "OEBPS/chap1.xhtml" to str(chap1Xhtml),
            "OEBPS/chap2.xhtml" to str(chap2Xhtml),
            "OEBPS/images/i.png" to byteArrayOf(1, 2, 3),
            "OEBPS/images/cover.jpg" to byteArrayOf(9, 8, 7)
        )
        val book = EpubParser.parse(epub, sizeStub)
        val ch1 = book.chapters.first { it.title == "封面" } // spine 兜底标题取 <title>
        val imgs = ch1.paragraphs.filterIsInstance<EpubParser.Paragraph.Image>()
        assertEquals(1, imgs.size)
        assertEquals("oebps/images/cover.jpg", imgs[0].zipHref)
        // chap1（前言）里的未声明图片 i.png 也被收录
        val preface = book.chapters.first { it.title == "前言" }
        assertTrue(preface.paragraphs.any {
            it is EpubParser.Paragraph.Image && it.zipHref == "oebps/images/i.png"
        })
    }

    @Test
    fun localizeGeneratedTitle_followsBookSourceLanguage() {
        assertEquals("卷首", localizeGeneratedTitle(FRONT_MATTER_TITLE, "zh"))
        assertEquals("书末", localizeGeneratedTitle(BACK_MATTER_TITLE, "zh"))
        assertEquals("Front Matter", localizeGeneratedTitle(FRONT_MATTER_TITLE, "en"))
        assertEquals("Back Matter", localizeGeneratedTitle(BACK_MATTER_TITLE, "en"))
        // 真实章节标题不做本地化
        assertEquals("Chapter One", localizeGeneratedTitle("Chapter One", "en"))
        assertEquals("第一章", localizeGeneratedTitle("第一章", "zh"))
    }

    @Test
    fun toChapterDicts_dropsEmptyChapters_andLocalizesGeneratedTitles() {
        val book = EpubParser.EpubBook(
            meta = EpubParser.EpubMeta("Test", null),
            coverBytes = null,
            chapters = listOf(
                EpubParser.EpubChapter(FRONT_MATTER_TITLE, null, emptyList()), // 空卷首 → 剔除
                EpubParser.EpubChapter("卷首", null, listOf(EpubParser.Paragraph.Text("前言文字。"))),
                EpubParser.EpubChapter("Chapter One", null, listOf(EpubParser.Paragraph.Text("Hello world."))),
                EpubParser.EpubChapter(BACK_MATTER_TITLE, null, emptyList())    // 空书末 → 剔除
            ),
            images = emptyMap()
        )
        val zhDicts = EpubParser.toChapterDicts(7L, book, { null }, "zh")
        assertEquals(listOf("卷首", "Chapter One"), zhDicts.map { it.title })
        // 英文原版：保留的非空卷首标题本地化为 Front Matter
        val enDicts = EpubParser.toChapterDicts(7L, book, { null }, "en")
        assertEquals(listOf("Front Matter", "Chapter One"), enDicts.map { it.title })
        assertEquals("Hello world.", enDicts[1].content)
    }

    @Test
    fun resolve_handlesParentNavigation() {
        // 反射不可用时直接验证行为：嵌套目录中的相对链接能归一到包内路径
        val epub = zipOf(
            "mimetype" to str("application/epub+zip"),
            "META-INF/container.xml" to str(containerXml),
            "OEBPS/content.opf" to str(
                opfXml(includeNcx = false)
                    .replace("href=\"chap1.xhtml\"", "href=\"text/chap1.xhtml\"")
            ),
            "OEBPS/text/chap1.xhtml" to str("""<html><head><title>T</title></head><body><p>你好。</p></body></html>"""),
            "OEBPS/chap2.xhtml" to str(chap2Xhtml),
            "OEBPS/cover.xhtml" to str(coverXhtml)
        )
        val book = EpubParser.parse(epub) { 10 to 5 }
        assertTrue(book.chapters.any { it.paragraphs.contains(EpubParser.Paragraph.Text("你好。")) })
    }

    // ── 补充分支：卷映射、EPUB3 nav、书末、锚点丢失 ──

    /** 嵌套 navPoint：子章节挂父节点标题为卷。 */
    private val nestedNcxXml = """<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<navMap>
<navPoint id="v1"><navLabel><text>第一卷</text></navLabel><content src="chap1.xhtml"/>
<navPoint id="c1"><navLabel><text>第一章</text></navLabel><content src="chap1.xhtml#sec2"/></navPoint>
</navPoint>
<navPoint id="n3"><navLabel><text>外传</text></navLabel><content src="chap2.xhtml"/></navPoint>
</navMap>
</ncx>"""

    @Test
    fun ncx_nestedNavPoints_mapParentTitleAsSection() {
        val book = EpubParser.parse(buildEpub(ncx = nestedNcxXml), sizeStub)
        assertEquals(
            listOf(
                "卷首" to null,
                "第一卷" to null,      // 父条目自身是章，无卷
                "第一章" to "第一卷",   // 子条目挂父标题为卷
                "外传" to null          // 顶层兄弟条目不继承前面的卷
            ),
            book.chapters.map { it.title to it.section }
        )
    }

    @Test
    fun epub3_navXhtml_parsedWithSectionMapping() {
        // EPUB3：manifest 声明 properties="nav"，嵌套 ol 子条目挂父条目文本为卷
        val opf = opfXml(includeNcx = false).replace(
            "</manifest>",
            "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/></manifest>"
        )
        val navXhtml = """<html><body><nav><ol>
<li><a href="chap1.xhtml">第一卷</a><ol><li><a href="chap1.xhtml#sec2">第一章</a></li></ol></li>
<li><a href="chap2.xhtml">第二章</a></li>
</ol></nav></body></html>"""
        val epub = zipOf(
            "mimetype" to str("application/epub+zip"),
            "META-INF/container.xml" to str(containerXml),
            "OEBPS/content.opf" to str(opf),
            "OEBPS/nav.xhtml" to str(navXhtml),
            "OEBPS/cover.xhtml" to str(coverXhtml),
            "OEBPS/chap1.xhtml" to str(chap1Xhtml),
            "OEBPS/chap2.xhtml" to str(chap2Xhtml),
            "OEBPS/images/i.png" to byteArrayOf(1, 2, 3),
            "OEBPS/images/cover.jpg" to byteArrayOf(9, 8, 7)
        )
        val book = EpubParser.parse(epub, sizeStub)
        assertEquals(
            listOf(
                "卷首" to null,
                "第一卷" to null,
                "第一章" to "第一卷",
                "第二章" to null
            ),
            book.chapters.map { it.title to it.section }
        )
    }

    @Test
    fun backMatter_spinePagesAfterLastTocEntry_becomeBackMatterChapter() {
        // spine 在最后一个 TOC 资源之后还有页面：合并为「书末」章，不丢内容
        val opf = opfXml(includeNcx = true)
            .replace("</manifest>", "<item id=\"end\" href=\"end.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>")
            .replace("<itemref idref=\"c2\"/>", "<itemref idref=\"c2\"/><itemref idref=\"end\"/>")
        val endXhtml = """<html><head><title>后记</title></head><body><p>后记内容。</p></body></html>"""
        val epub = zipOf(
            "mimetype" to str("application/epub+zip"),
            "META-INF/container.xml" to str(containerXml),
            "OEBPS/content.opf" to str(opf),
            "OEBPS/cover.xhtml" to str(coverXhtml),
            "OEBPS/chap1.xhtml" to str(chap1Xhtml),
            "OEBPS/chap2.xhtml" to str(chap2Xhtml),
            "OEBPS/end.xhtml" to str(endXhtml),
            "OEBPS/images/i.png" to byteArrayOf(1, 2, 3),
            "OEBPS/images/cover.jpg" to byteArrayOf(9, 8, 7),
            "OEBPS/toc.ncx" to str(ncxXml)
        )
        val book = EpubParser.parse(epub, sizeStub)
        assertEquals(listOf("卷首", "前言", "第一章", "第二章", "书末"), book.chapters.map { it.title })
        assertEquals(listOf(EpubParser.Paragraph.Text("后记内容。")), book.chapters.last().paragraphs)
    }

    @Test
    fun anchorNotFound_mergesIntoPreviousEntry_droppingTitle() {
        // #missing 锚点在文件中不存在：该条目标题丢弃，内容并入前一条目（前言）
        val brokenNcx = ncxXml.replace("chap1.xhtml#sec2", "chap1.xhtml#missing")
        val book = EpubParser.parse(buildEpub(ncx = brokenNcx), sizeStub)
        assertEquals(listOf("卷首", "前言", "第二章"), book.chapters.map { it.title })

        // 原本属于「第一章」条目的内容保留在前言章里，没有静默丢失
        val preface = book.chapters[1].paragraphs
        assertTrue(preface.contains(EpubParser.Paragraph.Text("第二段内容。")))
    }
}
