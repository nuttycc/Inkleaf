package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChapterWindowTest {
    @Test
    fun `page keys distinguish chapters revisions and pages`() {
        val chapter = chapter("chapter-1", revision = "rev-a")

        assertNotEquals(chapter.pageKey(0), chapter("chapter-2").pageKey(0))
        assertNotEquals(chapter.pageKey(0), chapter("chapter-1", revision = "rev-b").pageKey(0))
        assertNotEquals(chapter.pageKey(0), chapter.pageKey(1))
        assertEquals(chapter.pageKey(0), chapter("chapter-1", revision = "rev-a").pageKey(0))
    }

    @Test
    fun `window keeps boundary between current and prepared next chapter`() {
        val current = chapter("chapter-1", index = 0)
        val next = chapter("chapter-2", index = 1)
        val window =
            buildReaderChapterWindow(
                active = current,
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, next, prepared = true),
            )

        assertEquals(
            listOf("chapter-1:0", "chapter-1:1", "boundary", "chapter-2:0", "chapter-2:1"),
            window.items.map(::label),
        )
    }

    @Test
    fun `boundary identity is stable when its active side changes`() {
        val forward = ReaderChapterBoundaryKey("chapter-1", "chapter-2")
        val reverse = ReaderChapterBoundaryKey("chapter-1", "chapter-2")

        assertEquals(forward, reverse)
        assertNotEquals(forward, ReaderChapterBoundaryKey("chapter-2", "chapter-3"))
    }

    @Test
    fun `unprepared next chapter keeps a stable rebound guard beyond its boundary`() {
        val window =
            buildReaderChapterWindow(
                active = chapter("chapter-1", index = 0),
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, chapter("chapter-2", 1)),
            )

        assertEquals(
            listOf("chapter-1:0", "chapter-1:1", "boundary", "guard:NEXT"),
            window.items.map(::label),
        )
        assertEquals(
            ReaderPageTurnResult.MoveTo(3),
            readerPageTurnResult(window.items, currentIndex = 2, delta = 1),
        )
    }

    @Test
    fun `previous and next directions use symmetric ordering`() {
        val current = chapter("chapter-2", index = 1)
        val window =
            buildReaderChapterWindow(
                active = current,
                previous = adjacent(ReaderTransitionDirection.PREVIOUS, chapter("chapter-1", 0), true),
                next = adjacent(ReaderTransitionDirection.NEXT, chapter("chapter-3", 2), true),
            )

        assertEquals(
            listOf(
                "chapter-1:0",
                "chapter-1:1",
                "boundary",
                "chapter-2:0",
                "chapter-2:1",
                "boundary",
                "chapter-3:0",
                "chapter-3:1",
            ),
            window.items.map(::label),
        )
    }

    @Test
    fun `chapter commits only after an adjacent real page settles`() {
        val current = chapter("chapter-1", index = 0)
        val next = chapter("chapter-2", index = 1)
        val window =
            buildReaderChapterWindow(
                active = current,
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, next, prepared = true),
            )
        val boundary = window.items[2]
        val firstNextPage = window.items[3]

        assertEquals(ReaderSettledPageEffect.None, readerSettledPageEffect("chapter-1", boundary))
        assertEquals(
            ReaderSettledPageEffect.CommitChapter("chapter-2", chapterIndex = 1, pageIndex = 0),
            readerSettledPageEffect("chapter-1", firstNextPage),
        )
    }

    @Test
    fun `natural next chapter sequence requires two page turns`() {
        val window =
            buildReaderChapterWindow(
                active = chapter("chapter-1", index = 0),
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, chapter("chapter-2", 1), true),
            )

        assertEquals(ReaderPageTurnResult.MoveTo(2), readerPageTurnResult(window.items, 1, 1))
        assertEquals(
            ReaderSettledPageEffect.None,
            readerSettledPageEffect("chapter-1", window.items[2]),
        )
        assertEquals(ReaderPageTurnResult.MoveTo(3), readerPageTurnResult(window.items, 2, 1))
        assertEquals(
            ReaderSettledPageEffect.CommitChapter("chapter-2", 1, 0),
            readerSettledPageEffect("chapter-1", window.items[3]),
        )
    }

    @Test
    fun `natural previous chapter sequence enters its last page`() {
        val window =
            buildReaderChapterWindow(
                active = chapter("chapter-2", index = 1),
                previous = adjacent(ReaderTransitionDirection.PREVIOUS, chapter("chapter-1", 0), true),
                next = null,
            )

        assertEquals(ReaderPageTurnResult.MoveTo(2), readerPageTurnResult(window.items, 3, -1))
        assertEquals(ReaderPageTurnResult.MoveTo(1), readerPageTurnResult(window.items, 2, -1))
        assertEquals(
            ReaderSettledPageEffect.CommitChapter("chapter-1", 0, 1),
            readerSettledPageEffect("chapter-2", window.items[1]),
        )
    }

    @Test
    fun `guard settles as a rebound without queuing chapter entry`() {
        val window =
            buildReaderChapterWindow(
                active = chapter("chapter-1", index = 0),
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, chapter("chapter-2", 1)),
            )

        assertEquals(
            ReaderSettledPageEffect.ReboundBoundary(ReaderTransitionDirection.NEXT),
            readerSettledPageEffect("chapter-1", window.items.last()),
        )
        assertTrue(window.items[window.items.lastIndex - 1] is ReaderChapterWindowItem.Boundary)
    }

    @Test
    fun `loading intent does not queue entry and ready requires a new intent`() {
        assertEquals(
            ReaderBoundaryIntentEffect.None,
            readerBoundaryIntentEffect(ReaderTransitionStatus.Loading),
        )
        assertEquals(
            ReaderBoundaryIntentEffect.PublishPreparedPages,
            readerBoundaryIntentEffect(ReaderTransitionStatus.Ready),
        )
    }

    @Test
    fun `error intent retries while content boundary remains inert`() {
        assertEquals(
            ReaderBoundaryIntentEffect.RetryPreparation,
            readerBoundaryIntentEffect(ReaderTransitionStatus.Error),
        )
        assertEquals(
            ReaderBoundaryIntentEffect.None,
            readerBoundaryIntentEffect(ReaderTransitionStatus.Boundary),
        )
    }

    @Test
    fun `guard settled after loading becomes ready still rebounds without entering`() {
        assertEquals(
            ReaderBoundaryIntentEffect.None,
            readerGuardSettledEffect(ReaderTransitionStatus.Ready),
        )
        assertEquals(
            ReaderBoundaryIntentEffect.None,
            readerGuardSettledEffect(ReaderTransitionStatus.Loading),
        )
        assertEquals(
            ReaderBoundaryIntentEffect.RetryPreparation,
            readerGuardSettledEffect(ReaderTransitionStatus.Error),
        )
    }

    private fun chapter(
        id: String,
        index: Int = 0,
        revision: String = "rev-a",
    ) =
        ReaderWindowChapter(
            chapterId = id,
            chapterIndex = index,
            chapterRevision = revision,
            pageIdentities = listOf("page-1", "page-2"),
            payload = id,
        )

    private fun adjacent(
        direction: ReaderTransitionDirection,
        target: ReaderWindowChapter<String>,
        prepared: Boolean = false,
    ) =
        ReaderWindowAdjacent(
            direction = direction,
            targetChapterId = target.chapterId,
            transition =
                ReaderChapterTransition(
                    direction = direction,
                    chapterIndex = target.chapterIndex,
                    chapterLabel = "chapter",
                    title = target.chapterId,
                    status = if (prepared) ReaderTransitionStatus.Ready else ReaderTransitionStatus.Loading,
                ),
            preparedChapter = target.takeIf { prepared },
        )

    private fun label(item: ReaderChapterWindowItem<String>): String =
        when (item) {
            is ReaderChapterWindowItem.Page -> "${item.chapter.chapterId}:${item.pageIndex}"
            is ReaderChapterWindowItem.Boundary -> "boundary"
            is ReaderChapterWindowItem.Guard -> "guard:${item.direction}"
        }
}
