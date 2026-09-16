package com.vibereading.app.domain.translation

import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.parser.IllustrationLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译前置判定单测：把原先嵌在 TranslationCoordinator 状态机里、只能靠集成测试
 * 间接覆盖的规则提到纯函数层直接断言。
 */
class TranslationPreflightTest {

    private fun chapter(content: String) =
        Chapter(id = 1L, bookId = 1, title = "第一章", chapterIndex = 0, content = content)

    private val settings = LlmSettings(apiKey = "key", chapterMaxChars = 1000)

    @Test
    fun `ready chapter passes`() {
        assertEquals(
            TranslationGate.TRANSLATE,
            TranslationPreflight.gate(chapter("第一段。\n\n第二段。"), settings)
        )
    }

    @Test
    fun `blank api key is rejected first`() {
        // Key 优先级最高：即使章节同时过长，也先报未配置
        val long = chapter("正文。".repeat(500))
        assertEquals(
            TranslationGate.MISSING_API_KEY,
            TranslationPreflight.gate(long, settings.copy(apiKey = "  "))
        )
    }

    @Test
    fun `chapter over limit is too long`() {
        assertEquals(
            TranslationGate.TOO_LONG,
            TranslationPreflight.gate(chapter("正文。".repeat(500)), settings)
        )
    }

    @Test
    fun `content exactly at limit still translates`() {
        // 边界是严格大于，等于上限必须照译
        val content = "正".repeat(1000)
        assertEquals(TranslationGate.TRANSLATE, TranslationPreflight.gate(chapter(content), settings))
    }

    @Test
    fun `empty or blank chapter has nothing to translate`() {
        assertEquals(
            TranslationGate.NOTHING_TO_TRANSLATE,
            TranslationPreflight.gate(chapter("   \n\n  \n"), settings)
        )
    }

    @Test
    fun `illustration only chapter has nothing to translate`() {
        // 纯插图章节不该调 API（空 prompt 会招来「请提供文本」式寒暄）
        val link = IllustrationLink.build("1/pic.jpg", 400, 300)
        assertEquals(
            TranslationGate.NOTHING_TO_TRANSLATE,
            TranslationPreflight.gate(chapter(link), settings)
        )
        // 图文混排仍然翻译
        assertEquals(
            TranslationGate.TRANSLATE,
            TranslationPreflight.gate(chapter("正文。\n\n$link"), settings)
        )
    }

    @Test
    fun `hasTranslatableText matches gate semantics`() {
        val link = IllustrationLink.build("1/pic.jpg", 400, 300)
        assertTrue(TranslationPreflight.hasTranslatableText("正文。"))
        assertFalse(TranslationPreflight.hasTranslatableText(""))
        assertFalse(TranslationPreflight.hasTranslatableText(link))
    }

    @Test
    fun `too long message carries the character count`() {
        assertEquals("章节过长 (1005 字符)", TranslationPreflight.tooLongMessage(chapter("正文。".repeat(335))))
    }
}
