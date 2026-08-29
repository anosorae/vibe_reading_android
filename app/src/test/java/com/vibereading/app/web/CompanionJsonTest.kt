package com.vibereading.app.web

import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.ui.reader.content.ReadingContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web 伴读 JSON 层单测：offset 规范化语义 + 统一内容模型到伴读段落的映射
 * （offset 半开区间、插图段、无原文范围哨兵 -1）。
 */
class CompanionJsonTest {

    // ── offset 规范化（与 App 的半开区间/超长收敛语义一致） ──

    @Test
    fun `normalize clamps negative offset to zero`() {
        assertEquals(0, normalizeCompanionOffset(-5, 100))
    }

    @Test
    fun `normalize keeps offset inside content`() {
        assertEquals(42, normalizeCompanionOffset(42, 100))
    }

    @Test
    fun `normalize clamps offset beyond content length to length`() {
        assertEquals(100, normalizeCompanionOffset(150, 100))
    }

    @Test
    fun `normalize handles empty chapter content`() {
        assertEquals(0, normalizeCompanionOffset(10, 0))
        assertEquals(0, normalizeCompanionOffset(-1, 0))
    }

    // ── 段落映射：单语章节 ──

    @Test
    fun `paragraph mapping keeps source offsets for monolingual chapter`() {
        val content = "第一段内容。\n\n第二段内容。"
        val chapter = Chapter(bookId = 1, title = "章", chapterIndex = 0, content = content)
        val mapped = CompanionChapterContent.from(ReadingContent.fromChapter(chapter))

        assertEquals(2, mapped.paragraphs.size)
        assertEquals("p", mapped.paragraphs[0].type)
        val p0 = mapped.paragraphs[0]
        assertEquals(content.indexOf("第一段内容。"), p0.start)
        assertEquals(content.indexOf("第一段内容。") + "第一段内容。".length, p0.end)
        // 半开区间：start 指向段首字符，end 不含
        assertTrue(p0.start < p0.end)
        assertNull(p0.trans)
        assertNull(p0.imgUrl)
    }

    @Test
    fun `paragraph mapping carries translation for bilingual chapter`() {
        val original = "Hello world.\n\nSecond paragraph."
        val translation = "[1] 你好，世界。\n\n[2] 第二段。"
        val chapter = Chapter(
            bookId = 1, title = "章", chapterIndex = 0,
            content = original, translatedContent = translation,
            status = Chapter.STATUS_DONE
        )
        val mapped = CompanionChapterContent.from(ReadingContent.fromChapter(chapter))

        val withTrans = mapped.paragraphs.filter { it.trans != null }
        assertEquals(2, withTrans.size)
        // 中文书的译文侧是英文…反过来：中文书原文是中文侧；这里原文是英文书场景
        // 关键断言：每段都保留原文范围，供 Web 端进度定位
        withTrans.forEach { p ->
            assertTrue(p.start >= 0)
            assertTrue(p.end > p.start)
        }
    }

    @Test
    fun `illustration paragraph maps to img type with url and size`() {
        val content = "正文段落。\n\n![插图](vrimg://7/abc123.jpg 400x300)\n\n尾段。"
        val chapter = Chapter(bookId = 7, title = "章", chapterIndex = 0, content = content)
        val mapped = CompanionChapterContent.from(ReadingContent.fromChapter(chapter))

        val img = mapped.paragraphs.first { it.type == "img" }
        assertEquals("/img/7/abc123.jpg", img.imgUrl)
        assertEquals(400, img.imgW)
        assertEquals(300, img.imgH)
        assertNull(img.src) // 插图段不吐文本
    }

    @Test
    fun `companion chapter list keeps status constants`() {
        val c = CompanionChapter.from(
            Chapter(bookId = 1, title = "t", chapterIndex = 0, status = Chapter.STATUS_FAILED, errorMessage = "超时")
        )
        assertEquals(-1, c.status)
        assertEquals("超时", c.errorMessage)
        assertTrue(CompanionResult.success(null).ok)
        assertFalse(CompanionResult.failure("e").ok)
    }
}
