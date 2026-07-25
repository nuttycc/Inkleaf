package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderDockDestinationsTest {
    @Test
    fun `single chapter dock omits chapter navigation`() {
        assertEquals(
            listOf(
                ReaderDockDestination.Pages,
                ReaderDockDestination.Bookmarks,
                ReaderDockDestination.Tools,
            ),
            readerDockDestinations(chapterCount = 1),
        )
    }

    @Test
    fun `multi chapter dock keeps stable navigation order`() {
        assertEquals(
            listOf(
                ReaderDockDestination.Pages,
                ReaderDockDestination.Chapters,
                ReaderDockDestination.Bookmarks,
                ReaderDockDestination.Tools,
            ),
            readerDockDestinations(chapterCount = 2),
        )
    }
}
