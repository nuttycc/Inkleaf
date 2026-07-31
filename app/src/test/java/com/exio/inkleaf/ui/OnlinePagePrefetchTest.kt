package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlinePagePrefetchTest {
    @Test
    fun `metered and unmetered windows follow reading direction`() {
        assertEquals(listOf(6, 7), onlinePagePrefetchOrder(5, 20, direction = 1, count = 2))
        assertEquals(
            listOf(4, 3, 2, 1, 0),
            onlinePagePrefetchOrder(5, 20, direction = -1, count = 5),
        )
    }

    @Test
    fun `page windows clip at chapter boundaries`() {
        assertEquals(listOf(9), onlinePagePrefetchOrder(8, 10, direction = 1, count = 5))
        assertEquals(listOf(0), onlinePagePrefetchOrder(1, 10, direction = -1, count = 2))
    }

    @Test
    fun `adjacent chapters prefetch only two entry pages`() {
        assertEquals(listOf(0, 1), adjacentOnlinePagePrefetchOrder(5, direction = 1))
        assertEquals(listOf(4, 3), adjacentOnlinePagePrefetchOrder(5, direction = -1))
        assertEquals(listOf(0), adjacentOnlinePagePrefetchOrder(1, direction = 1))
    }
}
