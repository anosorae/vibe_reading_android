package com.vibereading.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 插图链接语法单测（ADR-002 D3）：整段匹配、正文不误判、build↔parse 往返一致。
 */
class IllustrationLinkTest {

    @Test
    fun parse_validLink() {
        val link = IllustrationLink.parse("![插图](vrimg://7/a1b2c3.png 800x600)")
        assertNotNull(link)
        assertEquals("7/a1b2c3.png", link!!.path)
        assertEquals(800, link.widthPx)
        assertEquals(600, link.heightPx)
    }

    @Test
    fun parse_buildRoundTrip() {
        val built = IllustrationLink.build("42/img-x.jpg", 1024, 768)
        val parsed = IllustrationLink.parse(built)
        assertEquals(IllustrationLink("42/img-x.jpg", 1024, 768), parsed)
    }

    @Test
    fun parse_rejectsProseAndInline() {
        // 正文里恰好出现的括号文本绝不能误判成图片（只有整段是链接才算）
        assertNull(IllustrationLink.parse("他写道![插图](vrimg://1/a.png 1x1)然后就走了"))
        assertNull(IllustrationLink.parse("普通段落文字"))
        assertNull(IllustrationLink.parse("[1] 这是被误标为链接的译文"))
        assertNull(IllustrationLink.parse("![插图](vrimg://1/bad.png 零x零)"))
        assertNull(IllustrationLink.parse("![插图](vrimg://1/neg.png -5x10)"))
        assertNull(IllustrationLink.parse(""))
    }

    @Test
    fun build_coercesPositiveDims() {
        val parsed = IllustrationLink.parse(IllustrationLink.build("1/x.png", 0, 0))
        assertNotNull(parsed)
        assertTrue(parsed!!.widthPx > 0 && parsed.heightPx > 0)
    }
}
