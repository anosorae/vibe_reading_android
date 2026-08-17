package com.vibereading.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingContentParserTest {

    @Test
    fun `paragraph ranges point at exact source text`() {
        val content = "  第一段  \n\n第二段\n\n第三段  "

        val paragraphs = ReadingContentParser.parseParagraphs(content)

        assertEquals(listOf("第一段", "第二段", "第三段"), paragraphs.map { it.text })
        assertTrue(paragraphs.all { it.isFrom(content) })
        assertEquals("第一段", content.substring(paragraphs[0].startOffset, paragraphs[0].endOffset))
        assertEquals("第二段", content.substring(paragraphs[1].startOffset, paragraphs[1].endOffset))
        assertEquals("第三段", content.substring(paragraphs[2].startOffset, paragraphs[2].endOffset))
        assertEquals(2, paragraphs[0].startOffset)
        assertEquals(content.length - 2, paragraphs.last().endOffset)
    }

    @Test
    fun `without blank lines each non blank line is a paragraph`() {
        val paragraphs = ReadingContentParser.parseParagraphs("第一行\n\n")

        assertEquals(listOf("第一行"), paragraphs.map { it.text })
        assertEquals(listOf("第一行"), ReadingContentParser.splitParagraphs("第一行"))
    }

    @Test
    fun `crlf cr and whitespace-only lines use the same paragraph boundaries`() {
        val lf = ReadingContentParser.parseParagraphs("甲\n\n乙\n丙").map { it.text }
        val crlf = ReadingContentParser.parseParagraphs("甲\r\n\r\n乙\r\n丙").map { it.text }
        val cr = ReadingContentParser.parseParagraphs("甲\r\r乙\r丙").map { it.text }
        val whitespace = ReadingContentParser.parseParagraphs("甲\r\n \t\r\n乙\r\n丙").map { it.text }

        fun normalize(text: String) = text.replace("\r\n", "\n").replace('\r', '\n')
        assertEquals(lf, lf)
        assertEquals(lf.map(::normalize), crlf.map(::normalize))
        assertEquals(lf.map(::normalize), cr.map(::normalize))
        assertEquals(lf.map(::normalize), whitespace.map(::normalize))
    }

    @Test
    fun `source ranges exclude separators but preserve internal whitespace`() {
        val content = "  甲  \r\n\r\n乙  丙  "
        val paragraphs = ReadingContentParser.parseParagraphs(content)

        assertEquals(listOf("甲", "乙  丙"), paragraphs.map { it.text })
        assertTrue(paragraphs.all { it.isFrom(content) })
        assertEquals("乙  丙", content.substring(paragraphs[1].startOffset, paragraphs[1].endOffset))
    }

    @Test
    fun `marked translations preserve marker and source offset`() {
        val original = "甲\n\n乙"

        val pairs = ReadingContentParser.parseBilingualParagraphs("[1] one\n[2] two", original)

        assertEquals(listOf(1, 2), pairs.map { it.marker })
        assertEquals(listOf("one", "two"), pairs.map { it.translatedText })
        assertEquals(listOf("甲", "乙"), pairs.map { it.chineseText })
        assertEquals(0, pairs[0].original?.startOffset)
        assertEquals(3, pairs[1].original?.startOffset)
    }

    @Test
    fun `invalid marker does not silently bind another source paragraph`() {
        val pair = ReadingContentParser.parseBilingualParagraphs("[9] orphan", "原文").single()

        assertEquals(9, pair.marker)
        assertEquals("orphan", pair.translatedText)
        assertFalse(pair.original != null)
        assertEquals("", pair.chineseText)
    }

    @Test
    fun `missing legal marker keeps original paragraph`() {
        val pairs = ReadingContentParser.parseBilingualParagraphs("[1] one\n[3] three", "甲\n\n乙\n\n丙")

        assertEquals(listOf(1, 2, 3), pairs.map { it.marker })
        assertEquals(listOf("one", "", "three"), pairs.map { it.translatedText })
        assertEquals(listOf("甲", "乙", "丙"), pairs.map { it.chineseText })
        assertEquals(3, pairs[1].original?.startOffset)
    }

    @Test
    fun `invalid marked translation is retained without fake offset`() {
        val content = com.vibereading.app.domain.model.Chapter(
            id = 7L,
            bookId = 1L,
            title = "测试",
            chapterIndex = 0,
            content = "甲\n\n乙",
            translatedContent = "[1] one\n[9] orphan"
        )
        val reading = com.vibereading.app.ui.reader.content.ReadingContent.fromChapter(content)

        assertEquals(listOf("one", "", "orphan"), reading.paragraphs.map { it.translatedText.orEmpty() })
        assertEquals(listOf(0, 3, -1), reading.paragraphs.map { it.sourceStartOffset })
    }
}
