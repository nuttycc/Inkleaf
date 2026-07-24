package com.exio.inkleaf.data

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PdfChapterLayoutTest {
    @Test
    fun `missing chapters keep their source positions without consuming pages`() {
        assertArrayEquals(
            intArrayOf(0, 0, 12, 12, 17),
            calculateChapterStartPages(intArrayOf(0, 12, 0, 5, 0)),
        )
    }

    @Test
    fun `changed page counts rebuild all following chapter offsets`() {
        val pageCounts = intArrayOf(10, 20, 30)
        assertArrayEquals(intArrayOf(0, 10, 30), calculateChapterStartPages(pageCounts))

        pageCounts[0] = 15

        assertArrayEquals(intArrayOf(0, 15, 35), calculateChapterStartPages(pageCounts))
    }
}
