package com.vibereading.app.ui.reader

import com.vibereading.app.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

/** 章节标签口径：全局章号（列表位置 +1），与滑块位置和「共N章」一致。 */
class ChapterLabelTest {

    private fun chapter(title: String, index: Int, section: String? = null) = Chapter(
        bookId = 1L, title = title, section = section, chapterIndex = index
    )

    @Test
    fun `无分卷书章号等于全局位置`() {
        val chapters = listOf(
            chapter("第一章 起源", 0),
            chapter("第二章 风暴", 1)
        )
        assertEquals("第1章", chapterLabel(chapters, 0))
        assertEquals("第2章", chapterLabel(chapters, 1))
    }

    @Test
    fun `分卷书每卷重新编号时仍显示全局章号`() {
        // 第二卷开头标题又是「第一章」：标题章号 1，全局位置 801
        val chapters = List(800) { i -> chapter("第一卷 第${i + 1}章", i, "第一卷") } +
            listOf(chapter("第一章 新的起点", 800, "第二卷"))
        assertEquals("第801章", chapterLabel(chapters, 800))
    }

    @Test
    fun `序章楔子显示原名`() {
        val chapters = listOf(
            chapter("序章", 0),
            chapter("楔子 黑暗中的火种", 1),
            chapter("第一章 起源", 2)
        )
        assertEquals("序章", chapterLabel(chapters, 0))
        assertEquals("序章", chapterLabel(chapters, 1))
        assertEquals("第3章", chapterLabel(chapters, 2))
    }

    @Test
    fun `越界索引返回占位符`() {
        val chapters = listOf(chapter("第一章 起源", 0))
        assertEquals("—", chapterLabel(chapters, -1))
        assertEquals("—", chapterLabel(chapters, 1))
    }

    @Test
    fun `页眉标题自带章号时不重复前置全局章号`() {
        // 分卷书：全局 203 章，标题是卷内编号——不得拼成「第203章 · 第29章 诞生」
        val chapters = List(202) { i -> chapter("第一卷 第${i + 1}章", i, "第一卷") } +
            listOf(chapter("第29章 诞生", 202, "第七卷"))
        assertEquals("第29章 诞生", chapterHeaderText(chapters, 202))
        // 「第 28 章」带空格变体同样只显示标题
        assertEquals("第 28 章", chapterHeaderText(listOf(chapter("第 28 章", 0)), 0))
        // 英文 Chapter N 同理
        assertEquals("Chapter 29 Birth", chapterHeaderText(listOf(chapter("Chapter 29 Birth", 0)), 0))
        // 数字点号式编号（英文原版书常见）同理
        assertEquals("1. Good Morning Brother", chapterHeaderText(listOf(chapter("1. Good Morning Brother", 1)), 0))
    }

    @Test
    fun `页眉标题无章号时前置全局章号`() {
        val chapters = List(27) { i -> chapter("第${i + 1}章", i) } + listOf(chapter("seed", 27))
        assertEquals("第28章 · seed", chapterHeaderText(chapters, 27))
        // 标题与标签相同（第1章）只显示标题
        assertEquals("第1章", chapterHeaderText(listOf(chapter("第1章", 0)), 0))
        // 序章显示原名
        assertEquals("序章", chapterHeaderText(listOf(chapter("序章", 0)), 0))
    }
}
