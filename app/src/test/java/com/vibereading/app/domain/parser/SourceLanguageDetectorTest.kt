package com.vibereading.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SourceLanguageDetector 单测（ADR-003）：首章前 20 段抽样，CJK 占比 ≥30% 判中文、
 * 否则英文；空样本保守归中文。
 */
class SourceLanguageDetectorTest {

    @Test
    fun `chinese dominant text detected as zh`() {
        val text = """
            第一章
            夜幕缓缓降临，城市的灯火次第亮起。
            他站在窗前，望着远方沉默不语。
        """.trimIndent()
        assertEquals(SourceLanguageDetector.ZH, SourceLanguageDetector.detect(text))
    }

    @Test
    fun `english dominant text detected as en`() {
        val text = """
            Chapter One
            The night fell slowly, and the city lights came on one by one.
            He stood by the window, silent, staring into the distance.
        """.trimIndent()
        assertEquals(SourceLanguageDetector.EN, SourceLanguageDetector.detect(text))
    }

    @Test
    fun `mixed text with low cjk ratio detected as en`() {
        // 英文为主夹杂少量中文（书名/人名），占比低于阈值
        val text = "The story of 张三 begins with a long journey across the mountains and rivers."
        assertEquals(SourceLanguageDetector.EN, SourceLanguageDetector.detect(text))
    }

    @Test
    fun `empty sample defaults to zh`() {
        assertEquals(SourceLanguageDetector.ZH, SourceLanguageDetector.detect(""))
        assertEquals(SourceLanguageDetector.ZH, SourceLanguageDetector.detect("   \n\n  "))
    }

    @Test
    fun `only first sample paragraphs are considered`() {
        // 前 20 段中文、其后一大段英文：只按样本判定
        val sample = (0 until 20).joinToString("\n\n") { "第${it}段中文内容。" }
        val tail = (0 until 50).joinToString("\n\n") { "This is English paragraph number $it with enough words." }
        assertEquals(SourceLanguageDetector.ZH, SourceLanguageDetector.detect("$sample\n\n$tail"))
    }

    // ── detectFirstNonBlank：跳过空首章/纯封面页（EPUB 卷首回归） ──

    @Test
    fun `empty first chapter falls through to english second chapter`() {
        // EPUB 卷首：空章节（纯封面页）→ 首个有文本的章节是英文 → 判 en
        val lang = SourceLanguageDetector.detectFirstNonBlank(
            listOf(
                "",
                "Information\nTable of Contents URL: https://www.royalroad.com/fiction/21220/mother-of-learning",
                "Good morning, brother. He said the words softly, almost to himself."
            )
        )
        assertEquals(SourceLanguageDetector.EN, lang)
    }

    @Test
    fun `whitespace first chapter falls through to chinese chapter`() {
        val lang = SourceLanguageDetector.detectFirstNonBlank(
            listOf("   \n\n  ", "第一章\n夜幕降临，城市灯火次第亮起。")
        )
        assertEquals(SourceLanguageDetector.ZH, lang)
    }

    @Test
    fun `tiny sample falls through to next qualified chapter`() {
        // 首章只有一行短英文（不足最小样本量）→ 继续取下一章判定
        val lang = SourceLanguageDetector.detectFirstNonBlank(
            listOf("Cover", "The night fell slowly and the city lights came on one by one.")
        )
        assertEquals(SourceLanguageDetector.EN, lang)
    }

    @Test
    fun `all chapters blank defaults to zh`() {
        assertEquals(SourceLanguageDetector.ZH, SourceLanguageDetector.detectFirstNonBlank(emptyList()))
        assertEquals(SourceLanguageDetector.ZH, SourceLanguageDetector.detectFirstNonBlank(listOf("", "  ")))
    }
}