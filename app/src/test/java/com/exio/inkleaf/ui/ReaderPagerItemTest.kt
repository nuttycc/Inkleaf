package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPagerItemTest {
    private val next =
        ReaderChapterTransition(
            direction = ReaderTransitionDirection.NEXT,
            chapterIndex = 2,
            chapterLabel = "第 3 章",
            title = "第三章",
            status = ReaderTransitionStatus.Ready,
        )
    private val previous = next.copy(direction = ReaderTransitionDirection.PREVIOUS)

    @Test
    fun `real pages keep indexes without transition`() {
        assertEquals(ReaderPagerItem.Page(0), readerPagerItem(0, 3, null))
        assertEquals(ReaderPagerItem.Page(2), readerPagerItem(2, 3, null))
    }

    @Test
    fun `next transition is appended after real pages`() {
        assertEquals(ReaderPagerItem.Page(0), readerPagerItem(0, 3, next))
        assertEquals(ReaderPagerItem.Page(2), readerPagerItem(2, 3, next))
        assertTrue(readerPagerItem(3, 3, next) is ReaderPagerItem.Transition)
    }

    @Test
    fun `previous transition is prepended before real pages`() {
        assertTrue(readerPagerItem(0, 3, previous) is ReaderPagerItem.Transition)
        assertEquals(ReaderPagerItem.Page(0), readerPagerItem(1, 3, previous))
        assertEquals(ReaderPagerItem.Page(2), readerPagerItem(3, 3, previous))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mapping rejects an index outside pager bounds`() {
        readerPagerItem(4, 3, next)
    }

    @Test
    fun `transition only returns after its page was entered`() {
        assertFalse(shouldReturnFromReaderTransition(false, false))
        assertFalse(shouldReturnFromReaderTransition(true, true))
        assertTrue(shouldReturnFromReaderTransition(true, false))
    }

    @Test
    fun `transition page keeps pager drag enabled`() {
        assertTrue(
            readerPagerUserScrollEnabled(true, isZoomed = true, isOcrSelectionActive = true)
        )
        assertFalse(
            readerPagerUserScrollEnabled(false, isZoomed = true, isOcrSelectionActive = false)
        )
        assertFalse(
            readerPagerUserScrollEnabled(false, isZoomed = false, isOcrSelectionActive = true)
        )
        assertTrue(
            readerPagerUserScrollEnabled(false, isZoomed = false, isOcrSelectionActive = false)
        )
    }
}
