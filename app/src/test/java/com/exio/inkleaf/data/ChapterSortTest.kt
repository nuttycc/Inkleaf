package com.exio.inkleaf.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterSortTest {

    @Test
    fun `single digit sorts before double digit`() {
        val input = listOf("10.pdf", "2.pdf", "1.pdf")
        val sorted = input.sortedWith { a, b -> ChapterSort.compareNatural(a, b) }
        assertEquals(listOf("1.pdf", "2.pdf", "10.pdf"), sorted)
    }

    @Test
    fun `decimal chapter numbers sort correctly`() {
        val input = listOf("4.pdf", "3.5.pdf", "1-3.pdf")
        val sorted = input.sortedWith { a, b -> ChapterSort.compareNatural(a, b) }
        assertEquals(listOf("1-3.pdf", "3.5.pdf", "4.pdf"), sorted)
    }

    @Test
    fun `prefix numbers take precedence`() {
        val input = listOf("003 4.pdf", "001 1-3.pdf", "002 3.5.pdf")
        val sorted = input.sortedWith { a, b -> ChapterSort.compareNatural(a, b) }
        assertEquals(listOf("001 1-3.pdf", "002 3.5.pdf", "003 4.pdf"), sorted)
    }

    @Test
    fun `case insensitive tie breaker`() {
        val input = listOf("B.pdf", "a.pdf", "C.pdf")
        val sorted = input.sortedWith { a, b -> ChapterSort.compareNatural(a, b) }
        assertEquals(listOf("a.pdf", "B.pdf", "C.pdf"), sorted)
    }
}
