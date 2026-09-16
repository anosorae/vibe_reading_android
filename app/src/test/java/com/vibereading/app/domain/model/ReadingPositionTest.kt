package com.vibereading.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadingPositionTest {

    @Test
    fun `position keeps chapter and non negative offset`() {
        assertEquals(ReadingPosition(42L, 128), ReadingPosition(chapterId = 42L, offset = 128))
        assertEquals(0, ReadingPosition.Beginning.offset)
    }

    @Test
    fun `negative offset is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReadingPosition(chapterId = 1L, offset = -1)
        }
    }

    @Test
    fun `position without chapter can only be beginning`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReadingPosition(chapterId = null, offset = 1)
        }
    }

    @Test
    fun `normalized clamps to chapter length`() {
        assertEquals(ReadingPosition(1L, 4), ReadingPosition(1L, 20).normalized(4))
    }

    // ── clampOffset：App 进度写入与 Web 伴读上报共用的归一化入口 ──

    @Test
    fun `clampOffset clamps negative offset to zero`() {
        assertEquals(0, ReadingPosition.clampOffset(-5, 100))
    }

    @Test
    fun `clampOffset keeps offset inside content`() {
        assertEquals(42, ReadingPosition.clampOffset(42, 100))
    }

    @Test
    fun `clampOffset clamps offset beyond content length to length`() {
        assertEquals(100, ReadingPosition.clampOffset(150, 100))
    }

    @Test
    fun `clampOffset handles empty chapter content`() {
        assertEquals(0, ReadingPosition.clampOffset(10, 0))
        assertEquals(0, ReadingPosition.clampOffset(-1, 0))
    }

    @Test
    fun `clampOffset agrees with normalized`() {
        val position = ReadingPosition(1L, 150)
        assertEquals(position.normalized(100).offset, ReadingPosition.clampOffset(position.offset, 100))
    }
}
