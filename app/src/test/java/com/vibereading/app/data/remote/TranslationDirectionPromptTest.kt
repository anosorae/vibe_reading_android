package com.vibereading.app.data.remote

import com.vibereading.app.domain.parser.IllustrationLink
import com.vibereading.app.domain.parser.SourceLanguageDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译方向单测（ADR-003）：buildUserPrompt / translationSystemPrompt 按书籍原文语言
 * 生成中→英或英→中 prompt，[N] 标记契约与插图编号空洞保持原样。
 */
class TranslationDirectionPromptTest {

    private val service = LlmApiService()

    @Test
    fun zhSource_usesChineseToEnglishPrompt() {
        val prompt = service.buildUserPrompt("第一章", "第一段。\n\n第二段。")
        assertTrue("zh 源应要求译成英文", prompt.contains("请将以下整章中文翻译为英文"))
        assertTrue("应保留段落标记", prompt.contains("[1] 第一段。"))
        assertTrue("应保留段落标记", prompt.contains("[2] 第二段。"))
        assertFalse("不应出现英译中指令", prompt.contains("翻译为中文"))
        assertTrue("系统 prompt 为中文→英文", LlmApiService.translationSystemPrompt("zh").contains("中文翻译为英文"))
    }

    @Test
    fun enSource_usesEnglishToChinesePrompt() {
        val prompt = service.buildUserPrompt("Chapter One", "Hello world.\n\nNice day.", sourceLanguage = "en")
        assertTrue("en 源应要求译成中文", prompt.contains("请将以下整章英文翻译为中文"))
        assertTrue("应保留段落标记", prompt.contains("[1] Hello world."))
        assertFalse("不应出现中译英指令", prompt.contains("中文翻译为英文"))
        assertTrue(
            "系统 prompt 为英文→中文且契约一致",
            LlmApiService.translationSystemPrompt("en").contains("英文翻译为中文")
        )
    }

    @Test
    fun illustrationParagraphs_keptOutOfPrompt_inBothDirections() {
        val link = IllustrationLink.build("9/x.png", 800, 600)
        val zhPrompt = service.buildUserPrompt("第一章", "第一段。\n\n$link")
        assertTrue(zhPrompt.contains("[1] 第一段。"))
        assertFalse("插图段内容不应进 prompt（编号空洞）", zhPrompt.contains("vrimg://"))
        assertFalse("插图段标记仍然编号", zhPrompt.contains("[2]"))

        val enPrompt = service.buildUserPrompt("Chapter", "First paragraph.\n\n$link", sourceLanguage = "en")
        assertTrue(enPrompt.contains("[1] First paragraph."))
        assertFalse("英文书方向同样剔除插图内容", enPrompt.contains("vrimg://"))
    }

    @Test
    fun defaultSourceLanguage_isChinese() {
        assertEquals(SourceLanguageDetector.ZH, "zh")
        assertTrue("默认 zh 走中→英", service.buildUserPrompt("第一章", "正文").contains("中文翻译为英文"))
    }
}