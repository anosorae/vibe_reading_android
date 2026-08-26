package com.vibereading.app.data.remote

import com.vibereading.app.domain.parser.IllustrationLink
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插图段的翻译 prompt 处理（ADR-002 D4）：保留编号、剔除内容（编号空洞）。
 */
class IllustrationPromptTest {

    @Test
    fun buildUserPrompt_skipsIllustrationParagraph_keepsNumberGaps() {
        val link = IllustrationLink.build("1/a.jpg", 800, 600)
        val content = "第一段中文。\n\n$link\n\n第三段中文。"
        val prompt = LlmApiService().buildUserPrompt("标题", content)

        assertTrue("编号 1 应保留", prompt.contains("[1] 第一段中文。"))
        // 编号空洞：插图段占 [2] 但内容不发送，模型输出自然缺失 [2]
        assertFalse("链接不应进入 prompt", prompt.contains(link))
        assertFalse(prompt.contains("[2]"))
        assertTrue("编号与原文索引保持对齐（[3]）", prompt.contains("[3] 第三段中文。"))
    }

    @Test
    fun buildUserPrompt_chapterWithoutImages_unchanged() {
        val prompt = LlmApiService().buildUserPrompt("标题", "一。\n\n二。")
        assertTrue(prompt.contains("[1] 一。"))
        assertTrue(prompt.contains("[2] 二。"))
    }
}
