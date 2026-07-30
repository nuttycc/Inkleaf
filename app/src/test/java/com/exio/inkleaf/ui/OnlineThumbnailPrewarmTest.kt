package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineThumbnailPrewarmTest {
    @Test
    fun `current page is first and neighbors alternate forward then backward`() {
        assertEquals(listOf(5, 6, 4, 7, 3), thumbnailPrewarmOrder(5, 10, 2))
    }

    @Test
    fun `window is clipped at chapter boundaries`() {
        assertEquals(listOf(0, 1, 2), thumbnailPrewarmOrder(0, 3, 2))
    }
}
