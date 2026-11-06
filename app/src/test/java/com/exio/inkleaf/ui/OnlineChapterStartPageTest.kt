package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineChapterStartPageTest {
    @Test
    fun `bookmark jump wins over settled page and direction`() {
        val page =
            onlineChapterStartPage(
                bookmarkJump = OnlineBookmarkJump(page = 4, approximate = false),
                settledPage = 2,
                direction = ChapterSwitchDirection.MANUAL,
                restoredPage = 7,
                currentPage = 9,
                pageCount = 10,
            )

        assertEquals(4, page)
    }

    @Test
    fun `out of range jump falls back to the settled page`() {
        val page =
            onlineChapterStartPage(
                bookmarkJump = OnlineBookmarkJump(page = 12, approximate = true),
                settledPage = 2,
                direction = ChapterSwitchDirection.PREVIOUS,
                restoredPage = 7,
                currentPage = 9,
                pageCount = 10,
            )

        assertEquals(2, page)
    }

    @Test
    fun `out of range settled page falls back to the direction default`() {
        val page =
            onlineChapterStartPage(
                bookmarkJump = null,
                settledPage = 10,
                direction = ChapterSwitchDirection.MANUAL,
                restoredPage = 3,
                currentPage = 9,
                pageCount = 10,
            )

        assertEquals(3, page)
    }

    @Test
    fun `no bookmark opens the next chapter on its first page`() {
        assertEquals(
            0,
            onlineChapterStartPage(null, null, ChapterSwitchDirection.NEXT, 3, 9, 10),
        )
    }

    @Test
    fun `no bookmark opens the previous chapter on its last page`() {
        assertEquals(
            9,
            onlineChapterStartPage(null, null, ChapterSwitchDirection.PREVIOUS, 3, 9, 10),
        )
    }

    @Test
    fun `manual switch without a bookmark restores the saved page`() {
        assertEquals(
            5,
            onlineChapterStartPage(null, null, ChapterSwitchDirection.MANUAL, 5, 9, 10),
        )
    }

    @Test
    fun `refresh keeps the current page inside the chapter`() {
        assertEquals(
            9,
            onlineChapterStartPage(null, null, ChapterSwitchDirection.REFRESH, 0, 12, 10),
        )
    }

    @Test
    fun `single page chapter never opens past its only page`() {
        assertEquals(
            0,
            onlineChapterStartPage(null, 1, ChapterSwitchDirection.REFRESH, 0, 1, 1),
        )
    }
}
