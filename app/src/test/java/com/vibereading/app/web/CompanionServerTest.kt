package com.vibereading.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 伴读服务 Token 三通道判定（ADR-005）。
 *
 * 鉴权出错的表现是「电脑上网页打不开」，现场只有一台电脑和一个浏览器，
 * 排查成本很高，所以这里把 query / header / Cookie 三条通道和边界都钉住。
 */
class CompanionServerTest {

    private val expected = "Abc234xyzABC234xyzABC234"

    @Test
    fun `query 参数命中`() {
        assertTrue(CompanionServer.tokenAccepted(expected, null, null, expected))
    }

    @Test
    fun `X-Companion-Token 头命中`() {
        assertTrue(CompanionServer.tokenAccepted(null, expected, null, expected))
    }

    @Test
    fun `Cookie 命中——首访之后不带 token 的旧地址仍可打开`() {
        assertTrue(CompanionServer.tokenAccepted(null, null, "${CompanionServer.COOKIE_NAME}=$expected", expected))
    }

    @Test
    fun `多 Cookie 并存时仍能取出伴读 Token`() {
        val header = "theme=dark; ${CompanionServer.COOKIE_NAME}=$expected; other=1"
        assertTrue(CompanionServer.tokenAccepted(null, null, header, expected))
        assertEquals(expected, CompanionServer.cookieToken(header))
    }

    @Test
    fun `Cookie 值含等号时取第一个等号之后的部分`() {
        assertEquals("a=b=c", CompanionServer.cookieToken("${CompanionServer.COOKIE_NAME}=a=b=c"))
    }

    @Test
    fun `三条通道都不匹配则拒绝`() {
        assertFalse(CompanionServer.tokenAccepted("wrong", "wrong", "other=wrong", expected))
    }

    @Test
    fun `服务端 Token 为空时一律拒绝——避免空 Token 放行`() {
        assertFalse(CompanionServer.tokenAccepted("", null, "${CompanionServer.COOKIE_NAME}=", ""))
        assertFalse(CompanionServer.tokenAccepted(null, null, null, ""))
    }

    @Test
    fun `Cookie 名后缀相同不算命中`() {
        assertFalse(CompanionServer.tokenAccepted(null, null, "x${CompanionServer.COOKIE_NAME}=$expected", expected))
        assertNull(CompanionServer.cookieToken("x${CompanionServer.COOKIE_NAME}=$expected"))
    }

    @Test
    fun `没有 Cookie 头时取值为 null`() {
        assertNull(CompanionServer.cookieToken(null))
        assertNull(CompanionServer.cookieToken(""))
    }
}
