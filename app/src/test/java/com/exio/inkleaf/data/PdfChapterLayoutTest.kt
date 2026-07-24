package com.exio.inkleaf.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `global page only requires counts through its containing chapter`() {
        val layout = PdfChapterLayout(intArrayOf(0, 12, -1, -1))

        assertTrue(layout.resolvesGlobalPage(0))
        assertTrue(layout.resolvesGlobalPage(11))
        assertFalse(layout.resolvesGlobalPage(12))
    }

    @Test
    fun `unknown counts before target prevent using approximate offsets`() {
        val layout = PdfChapterLayout(intArrayOf(5, -1, 10))

        assertTrue(layout.resolvesGlobalPage(4))
        assertFalse(layout.resolvesGlobalPage(5))
        assertFalse(layout.resolvesGlobalPage(12))
    }

    @Test
    fun `fully resolved layout also resolves pages past the book end`() {
        val layout = PdfChapterLayout(intArrayOf(0, 3, 0))

        assertTrue(layout.resolvesGlobalPage(3))
    }
}
