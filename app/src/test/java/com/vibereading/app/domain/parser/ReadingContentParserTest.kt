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
}
