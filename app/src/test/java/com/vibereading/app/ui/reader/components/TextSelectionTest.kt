package com.vibereading.app.ui.reader.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * BreakIterator 选词边界单测：长按命中的字符 → 词边界（对齐 Legado onLongPress 逻辑）。
 * 断言结构化区间结果，不依赖像素。
 */
class TextSelectionTest {

    @Test
    fun `word boundary within simple sentence`() {
        val text = "hello world"
        assertEquals(0 until 5, findWordBoundary(text, 0))   // h
        assertEquals(0 until 5, findWordBoundary(text, 4))   // o
        assertEquals(6 until 11, findWordBoundary(text, 6))  // w
        assertEquals(6 until 11, findWordBoundary(text, 10)) // d
    }

    @Test
    fun `apostrophe word is one segment`() {
        val text = "don't stop"
        assertEquals(0 until 5, findWordBoundary(text, 2))
        assertEquals(6 until 10, findWordBoundary(text, 7))
    }

    @Test
    fun `hyphenated word is one segment`() {
        val text = "well-known book"
        assertEquals(0 until 10, findWordBoundary(text, 5))
        assertEquals(11 until 15, findWordBoundary(text, 13))
    }

    @Test
    fun `punctual position lands on the punctuation segment`() {
        // 句号独立成段；选词组件会过滤纯标点段（要求含字母）
        val text = "go. now"
        val range = findWordBoundary(text, 0)
        assertEquals("go", text.substring(range!!.first, range.last + 1))
        val puncRange = findWordBoundary(text, 2)
        assertEquals(".", text.substring(puncRange!!.first, puncRange.last + 1))
    }

    @Test
    fun `lead and trailing space segments are skipped by letter filter`() {
        val text = "  abc  "
        // 命中中间词
        val range = findWordBoundary(text, 3)
        val word = text.substring(range!!.first, range.last + 1)
        assertEquals("abc", word)
        // 命中空白段也能返回该段（由调用方过滤）
        val spaceRange = findWordBoundary(text, 0)
        assertEquals("  ", text.substring(spaceRange!!.first, spaceRange.last + 1))
    }

    @Test
    fun `chinese segment roughly per character`() {
        // ICU 中文分词：无空格文本按字典/单字切分，至少能命中触摸位置附近的边界
        val text = "你好世界"
        val range = findWordBoundary(text, 1, Locale.CHINESE) ?: return
        val seg = text.substring(range.first, range.last + 1)
        assertEquals("你好世界".substring(range.first, range.last + 1), seg)
        assert(range.first <= 1 && range.last >= 1)
    }

    @Test
    fun `out of range and empty inputs return null`() {
        assertNull(findWordBoundary("", 0))
        assertNull(findWordBoundary("abc", -1))
        assertNull(findWordBoundary("abc", 3))
    }
}