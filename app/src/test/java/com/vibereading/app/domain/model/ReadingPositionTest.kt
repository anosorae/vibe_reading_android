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
}
