package com.vibereading.app.ui.reader

import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.ReadingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动模式内容分块单测：标题项生成、chunk key 解析、offset→列表索引的回退链。
 */
class ReaderScrollTest {

    private val ch1 = Chapter(
        id = 11L, bookId = 1L, title = "第一章", chapterIndex = 0,
        // 段落范围：[0,5) 与 [7,14)，中间 \n\n 空隙
        content = "第一段文字。\n\n第二段文字内容。"
    )
    private val ch2 = Chapter(
        id = 22L, bookId = 1L, title = "第二章", chapterIndex = 1,
        content = "另一章的内容。"
    )

    @Test
    fun `buildScrollChunks emits title and paragraphs per chapter`() {
        val items = buildScrollChunks(listOf(ch1, ch2), ReadingSettings.TITLE_MODE_LEFT)
        assertTrue(items[0] is ScrollItem.Title)
        assertEquals(11L, items[0].chapterId)
        val paraItems = items.filterIsInstance<ScrollItem.Paragraph>().filter { it.chapterId == 11L }
        assertEquals(2, paraItems.size)
        assertEquals("第一段文字。", paraItems[0].paragraph.sourceText)

        // 隐藏标题：只剩段落项
        val hidden = buildScrollChunks(listOf(ch1, ch2), ReadingSettings.TITLE_MODE_HIDDEN)
        assertTrue(hidden.none { it is ScrollItem.Title })
        assertEquals(3, hidden.size)
    }

    @Test
    fun `chapterIdOfChunkKey parses lazy list keys`() {
        assertEquals(11L, chapterIdOfChunkKey("para-11-3"))
        assertEquals(22L, chapterIdOfChunkKey("title-22"))
        assertNull(chapterIdOfChunkKey(null))
        assertNull(chapterIdOfChunkKey("garbage"))
    }

    @Test
    fun `indexInChunks falls back through containment then next then last`() {
        val items = buildScrollChunks(listOf(ch1, ch2), ReadingSettings.TITLE_MODE_LEFT)
        // 标题在 index 0，两段正文在 1、2，第二章标题/正文在后
        val ch1Para0 = items.indexOfFirst { it is ScrollItem.Paragraph && it.chapterId == 11L }

        // 1) 命中包含区间：offset 落在第一段内
        assertEquals(ch1Para0, items.indexInChunks(11L, 2))

        // 2) 落在段间空隙（第一段结束、第二段未开始）：回退到下一个起始更靠后的段落
        assertEquals(ch1Para0 + 1, items.indexInChunks(11L, 6))

        // 3) offset 超过本章所有段落：回退到本章最后一个段落项
        assertEquals(ch1Para0 + 1, items.indexInChunks(11L, 999))

        // 4) 章节不存在 / chapterId 为 null
        assertNull(items.indexInChunks(99L, 0))
        assertNull(items.indexInChunks(null, 0))
    }
}
