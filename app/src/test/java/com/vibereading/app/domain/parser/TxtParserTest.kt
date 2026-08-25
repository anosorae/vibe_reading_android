package com.vibereading.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * TxtParser 分章/卷名解析测试。
 * 断言结构化结果（标题、section 归属、切段内容），不 pin 像素或格式细节。
 */
class TxtParserTest {

    private fun parse(text: String): List<TxtParser.ChapterDict> = TxtParser.parseText(text.trimIndent() + "\n")

    @Test
    fun `正文开头的段落不再被误判为章节标题`() {
        // 回归：曾因 SPECIAL 含「正文(?!完|结)」导致「正文内容…」整段成为假章节，
        // 连锁把前后真实章节的内容清空丢弃（数据丢失级 bug）。
        val chapters = parse(
            """
            第一章 前情

            正文内容甲，故事从这里开始。

            第二卷 群雄逐鹿

            第二章 开战

            正文内容乙，战斗打响了。
            """
        )
        assertEquals(listOf("第一章 前情", "第二章 开战"), chapters.map { it.title })
        assertEquals("正文内容甲，故事从这里开始。", chapters[0].content)
        assertEquals("第二卷 群雄逐鹿", chapters[1].section)
        assertEquals("正文内容乙，战斗打响了。", chapters[1].content)
    }

    @Test
    fun `单独成行的正文仍识别为章名`() {
        val chapters = parse(
            """
            序章

            引子内容。

            正文

            第一段正文。

            正文 第二部

            第二段正文。
            """
        )
        assertEquals(listOf("序章", "正文", "正文 第二部"), chapters.map { it.title })
        assertEquals("第一段正文。", chapters[1].content)
    }

    @Test
    fun `两行式卷头合并卷名且不污染上一章`() {
        val chapters = parse(
            """
            第一章 甲

            内容甲。

            第二卷

            群雄逐鹿

            第二章 乙

            内容乙。
            """
        )
        assertFalse(chapters[0].content.contains("群雄逐鹿"))
        assertEquals("第二卷 群雄逐鹿", chapters[1].section)
    }

    @Test
    fun `光杆卷头隔空行的下一短行合并为卷名`() {
        val chapters = parse(
            """
            第一章 甲

            内容甲。

            第三卷

            风起云涌

            第一章 乙

            内容乙。
            """
        )
        assertEquals("第三卷 风起云涌", chapters[1].section)
        assertFalse(chapters[0].content.contains("风起云涌"))
    }

    @Test
    fun `卷尾标记不产生幽灵分组`() {
        val chapters = parse(
            """
            第一章 甲

            内容甲。

            第一卷 完

            第二章 乙

            内容乙。
            """
        )
        assertNull(chapters[1].section)
        assertTrue(chapters.none { it.section?.endsWith("完") == true })
    }

    @Test
    fun `以句号结尾的正文行不再误判为卷名`() {
        // 回归：真实书籍 xiuxian.txt 中 318/339 章后的两个坏卷名，均为
        // 「第N部/第N篇」开头的独立成行叙述句（负向断言防不住部后接天/篇后接便）
        val chapters = parse(
            """
            第318章 册封群神

            元鼎帝寻来诸国律法，互相借鉴增补。
            数年后。
            第一部天庭律法完成。
            律法从方方面面规定了神仙言行举止。

            第319章 天帝百年

            内容继续。
            """
        )
        assertEquals(listOf("第318章 册封群神", "第319章 天帝百年"), chapters.map { it.title })
        assertTrue(chapters[0].content.contains("第一部天庭律法完成。"))
        assertNull(chapters[1].section)

        val chapters2 = parse(
            """
            第347章 佛魔一体

            孙长生施法起了个石台，盘坐上面诵读道经。
            第一篇便是《太上延生篆》。

            第348章 天庭震动

            内容继续。
            """
        )
        assertTrue(chapters2[0].content.contains("第一篇便是《太上延生篆》。"))
        assertNull(chapters2[1].section)
    }

    @Test
    fun `半角标点收尾的编号标题保留`() {
        // 回归：发条新娘真实标题「039：你愿意战斗吗?」为半角 ? 收尾，
        // 不能因正文误判规则（只针对全角句末标点）被吞掉
        val chapters = parse(
            """
            039：你愿意战斗吗?

            内容甲。

            071：恐惧卡莲之力!

            内容乙。
            """
        )
        assertEquals(listOf("039：你愿意战斗吗?", "071：恐惧卡莲之力!"), chapters.map { it.title })
        assertTrue(chapters[0].content.contains("内容甲。"))
    }

    @Test
    fun `括号包裹的章名与卷头可解析`() {
        val chapters = parse(
            """
            【第一卷】风起云涌

            【第一章】出发

            内容甲。
            """
        )
        assertEquals("第一卷 风起云涌", chapters[0].section)
        assertEquals("第一章 出发", chapters[0].title)
        assertEquals("内容甲。", chapters[0].content)
    }

    @Test
    fun `纯标记章名不吞并紧邻的对话行`() {
        // 对话以引号收尾，是正文首句不是副标题
        val chapters = parse(
            """
            第一章
            「少爷，该动身了。」
            车夫低声说。
            """
        )
        assertEquals(1, chapters.size)
        assertEquals("第一章", chapters[0].title)
        assertTrue(chapters[0].content.startsWith("「少爷"))
    }

    @Test
    fun `纯标记章名仍合并纯文本副标题`() {
        val chapters = parse(
            """
            第一章
            风起云涌

            正文从此处展开，篇幅足够长不会被合并。
            """
        )
        assertEquals("第一章 风起云涌", chapters[0].title)
        assertEquals("正文从此处展开，篇幅足够长不会被合并。", chapters[0].content)
    }

    @Test
    fun `含单位字的正文行不误判为章节`() {
        val chapters = parse(
            """
            第一章 唯一

            第一节课现在开始。
            第一回合结束了。
            第三部分完。
            第一场电影散场了。
            正文到此为止。
            """
        )
        // 全部落入第一章正文，不产生额外分章；
        // 断言正文行保留在内容里，防止「假章节吃掉真章节内容」的连锁丢失
        assertEquals(1, chapters.size)
        assertEquals("第一章 唯一", chapters[0].title)
        assertTrue(chapters[0].content.contains("第一回合结束了。"))
        assertTrue(chapters[0].content.contains("正文到此为止。"))
    }

    @Test
    fun `卷归属跨越特殊章传递`() {
        // 卷头 → 序章 → 正式章：正式章仍归该卷
        val chapters = parse(
            """
            第一卷 风起

            序章

            雪夜。

            第一章 出发

            内容甲。

            第二章 到达

            内容乙。
            """
        )
        assertEquals(3, chapters.size)
        assertTrue(chapters.all { it.section == "第一卷 风起" })
    }

    @Test
    fun `无任何标题时全文兜底为单章`() {
        val chapters = parse("第一段。\n\n第二段。")
        assertEquals(1, chapters.size)
        assertEquals("全文", chapters[0].title)
        assertEquals("第一段。\n\n第二段。", chapters[0].content)
    }

    @Test
    fun `基本分章与段落拼接不受影响`() {
        val chapters = parse(
            """
            楔子

            天下大势，分久必合。

            第一章 出发 少年行

            雪落长安。

            第二章 归来

            春满江南。
            """
        )
        assertEquals(listOf("楔子", "第一章 出发 少年行", "第二章 归来"), chapters.map { it.title })
        assertEquals("天下大势，分久必合。", chapters[0].content)
        assertEquals("雪落长安。", chapters[1].content)
    }

    @Test
    fun `GB18030编码回退解码`() {
        val bytes = "第一章 测试".toByteArray(Charset.forName("GB18030"))
        val decoded = TxtParser.decodeBytes(bytes)
        assertEquals("第一章 测试", decoded)
    }
}
