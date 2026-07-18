package com.exio.inkleaf.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementCacheProgressTest {
    @Test
    fun `non contiguous pages count immediately and retain earliest gap`() {
        val progress = calculateEnhancementCacheProgress(
            startPageInclusive = 2,
            endPageInclusive = 8,
            completedPages = setOf(2, 4, 7),
        )

        assertEquals(3, progress.completedPages)
        assertEquals(3, progress.nextPage)
        assertFalse(progress.isComplete)
    }

    @Test
    fun `duplicates and pages outside the range do not inflate progress`() {
        val progress = calculateEnhancementCacheProgress(
            startPageInclusive = 2,
            endPageInclusive = 5,
            completedPages = listOf(1, 2, 2, 3, 6),
        )

        assertEquals(2, progress.completedPages)
        assertEquals(4, progress.nextPage)
        assertFalse(progress.isComplete)
    }

    @Test
    fun `fully completed range advances to exclusive end`() {
        val progress = calculateEnhancementCacheProgress(
            startPageInclusive = 2,
            endPageInclusive = 5,
            completedPages = setOf(2, 3, 4, 5),
        )

        assertEquals(4, progress.completedPages)
        assertEquals(6, progress.nextPage)
        assertTrue(progress.isComplete)
    }
}
