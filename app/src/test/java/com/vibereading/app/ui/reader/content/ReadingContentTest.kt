package com.vibereading.app.ui.reader.content

import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.parser.IllustrationLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReadingContent 组装层单测：原文 offset 直传、插图段防御、无范围哨兵、
 * ADR-003 中/英文侧插槽互换。
 */
class ReadingContentTest {

    // 三段：文本 / 插图 / 文本；用字面量 indexOf 作为期望值的独立来源
    private val content = buildString {
        append("第一段内容。")            // 6 个字符：0..5
        append("\n\n")
        append(IllustrationLink.build("1/a.jpg", 100, 50))
        append("\n\n")
        append("第三段内容。")
    }

    private fun chapter(translated: String? = null) = Chapter(
        id = 9L,
        bookId = 1L,
        title = "第一章",
        chapterIndex = 0,
        content = content,
        translatedContent = translated
    )

    @Test
    fun `fromChapter without translation passes offsets through and detects illustration`() {
        val rc = ReadingContent.fromChapter(chapter())
        assertEquals(3, rc.paragraphs.size)

        val p0 = rc.paragraphs[0]
        assertEquals(0, p0.sourceStartOffset)
        assertEquals(6, p0.sourceEndOffset)   // 半开区间：5 个字符 + 句号后界
        assertEquals("第一段内容。", content.substring(p0.sourceStartOffset, p0.sourceEndOffset).trim())
        assertTrue(p0.hasSourceOffset)

        val img = rc.paragraphs[1]
        assertTrue(img.illustration != null)
        assertEquals("1/a.jpg", img.illustration!!.path)
        assertNull(img.translatedText)
        assertEquals(content.indexOf("![插图]"), img.sourceStartOffset)
        assertTrue(img.hasSourceOffset)

        val p2 = rc.paragraphs[2]
        assertEquals(content.indexOf("第三段"), p2.sourceStartOffset)
        assertTrue(p2.hasSourceOffset)
    }

    @Test
    fun `fromChapter bilingual binds offsets and never shows translation on illustration`() {
        // 模型对插图编号 [2] 回了散落译文：不得当作该段文本显示
        val translation = "[1]Para one.\n[2]stray words\n[3]Para three."
        val rc = ReadingContent.fromChapter(chapter(translation))

        assertEquals(3, rc.paragraphs.size)
        assertEquals("Para one.", rc.paragraphs[0].translatedText)
        assertEquals(0, rc.paragraphs[0].sourceStartOffset)

        // 插图段：即使译文存在也强制置空，插图照常渲染
        assertNull(rc.paragraphs[1].translatedText)
        assertTrue(rc.paragraphs[1].illustration != null)
        assertEquals(content.indexOf("![插图]"), rc.paragraphs[1].sourceStartOffset)

        assertEquals("Para three.", rc.paragraphs[2].translatedText)
    }

    @Test
    fun `fromChapter unmatched translation keeps sentinel offset instead of faking zero`() {
        // 越界标记 [9] 的内容必须保留但不能伪造为章节开头 offset=0
        val translation = "[1]Para one.\n[9]orphan text"
        val rc = ReadingContent.fromChapter(chapter(translation))

        val orphan = rc.paragraphs.last()
        assertEquals("orphan text", orphan.translatedText)
        assertFalse(orphan.hasSourceOffset)
        assertEquals(ReadingContent.NO_SOURCE_OFFSET, orphan.sourceStartOffset)
        assertEquals(ReadingContent.NO_SOURCE_OFFSET, orphan.sourceEndOffset)
    }

    @Test
    fun `fromChapter blank translation renders as untranslated paragraph`() {
        val translation = "[1]\n[2] \n[3]Para three."
        val rc = ReadingContent.fromChapter(chapter(translation))

        assertNull(rc.paragraphs[0].translatedText)
        assertNull(rc.paragraphs[1].translatedText)
        assertEquals("Para three.", rc.paragraphs[2].translatedText)
    }

    @Test
    fun `chinese side follows source language slots`() {
        val translated = ReadingContent.fromChapter(chapter("[1]Para one."))
        val untranslated = ReadingContent.fromChapter(chapter(null))
        // 空白译文不算就绪
        val blank = ReadingContent.fromChapter(chapter("[1]  "))

        // 中文书：中文侧=中文原文（恒有），英文侧=译文或 null
        assertEquals("第一段内容。", translated.paragraphs[0].chineseSide("zh"))
        assertEquals("Para one.", translated.paragraphs[0].englishSide("zh"))
        assertNull(untranslated.paragraphs[0].englishSide("zh"))

        // 英文书：英文侧=英文原文（恒有），中文侧=译文或 null
        assertEquals("第一段内容。", translated.paragraphs[0].englishSide("en"))
        assertEquals("Para one.", translated.paragraphs[0].chineseSide("en"))
        assertNull(untranslated.paragraphs[0].chineseSide("en"))
        assertNull(blank.paragraphs[0].chineseSide("en"))
    }
}
