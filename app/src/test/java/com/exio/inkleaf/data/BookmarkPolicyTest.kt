package com.exio.inkleaf.data

import com.exio.inkleaf.data.db.BookSourceType
import com.exio.inkleaf.data.db.BookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkPolicyTest {
    @Test
    fun `identity target key survives reorder and revision changes`() {
        val before = bookmarkTargetKey(
            pageIdentity = "stable-page",
            sourceRevision = "revision-a",
            globalPageIndex = 3,
        )
        val after = bookmarkTargetKey(
            pageIdentity = "stable-page",
            sourceRevision = "revision-b",
            globalPageIndex = 20,
        )

        assertEquals(before, after)
    }

    @Test
    fun `position target key is scoped to revision and page`() {
        val original = bookmarkTargetKey(null, "revision-a", 3)

        assertNotEquals(original, bookmarkTargetKey(null, "revision-b", 3))
        assertNotEquals(original, bookmarkTargetKey(null, "revision-a", 4))
    }

    @Test
    fun `same source revision opens stored global page without remapping`() {
        var lookupCalled = false

        val result = resolveBookmarkLocation(
            comicId = 42,
            sourceType = BookSourceType.EXTERNAL_ARCHIVE,
            storedSourceRevision = "same",
            storedGlobalPage = 8,
            pageIdentity = "page-id",
            currentSourceRevision = "same",
            currentPageCount = 20,
            findPageByIdentity = {
                lookupCalled = true
                2
            },
        )

        assertEquals(BookmarkResolution.Ready(42, 8), result)
        assertTrue(!lookupCalled)
    }

    @Test
    fun `edited album and zip remap a surviving identity automatically`() {
        for (sourceType in listOf(BookSourceType.CREATED_ALBUM, BookSourceType.EXTERNAL_ARCHIVE)) {
            val result = resolveBookmarkLocation(
                comicId = 42,
                sourceType = sourceType,
                storedSourceRevision = "before",
                storedGlobalPage = 8,
                pageIdentity = "page-id",
                currentSourceRevision = "after",
                currentPageCount = 20,
                findPageByIdentity = { 2 },
            )

            assertEquals(BookmarkResolution.Ready(42, 2), result)
        }
    }

    @Test
    fun `edited pdf remains source changed even when chapter page survives`() {
        val result = resolveBookmarkLocation(
            comicId = 42,
            sourceType = BookSourceType.PDF_SERIES,
            storedSourceRevision = "before",
            storedGlobalPage = 8,
            pageIdentity = "pdf-page",
            currentSourceRevision = "after",
            currentPageCount = 20,
            findPageByIdentity = { 2 },
        )

        assertEquals(BookmarkResolution.SourceChanged(42, 2), result)
    }

    @Test
    fun `missing identity reports source change with clamped approximation`() {
        val result = resolveBookmarkLocation(
            comicId = 42,
            sourceType = BookSourceType.EXTERNAL_ARCHIVE,
            storedSourceRevision = "before",
            storedGlobalPage = 80,
            pageIdentity = "removed-page",
            currentSourceRevision = "after",
            currentPageCount = 20,
            findPageByIdentity = { null },
        )

        assertEquals(BookmarkResolution.SourceChanged(42, 19), result)
    }

    @Test
    fun `empty source is unavailable`() {
        val result = resolveBookmarkLocation(
            comicId = 42,
            sourceType = BookSourceType.CREATED_ALBUM,
            storedSourceRevision = "before",
            storedGlobalPage = 0,
            pageIdentity = "page-id",
            currentSourceRevision = "after",
            currentPageCount = 0,
            findPageByIdentity = { 0 },
        )

        assertTrue(result is BookmarkResolution.Unavailable)
    }

    @Test
    fun `toggle finds stale bookmark that currently resolves to the same page`() {
        val stale = bookmark(
            id = 1,
            targetKey = "old-content",
            pageIdentity = "replaced-page",
            sourceRevision = "before",
            globalPageIndex = 4,
        )
        val exact = bookmark(
            id = 2,
            targetKey = "current-content",
            pageIdentity = "current-page",
            sourceRevision = "after",
            globalPageIndex = 4,
        )

        val matches = bookmarkMatchesForCurrentPage(
            bookmarks = listOf(stale, exact),
            comicId = 42,
            sourceType = BookSourceType.EXTERNAL_ARCHIVE,
            currentSourceRevision = "after",
            currentPageCount = 10,
            currentGlobalPage = 4,
            findPageByIdentity = { identity ->
                if (identity == "current-page") 4 else null
            },
        )

        assertEquals(listOf(exact), matches.ready)
        assertEquals(listOf(stale), matches.sourceChanged)
    }

    @Test
    fun `source changed location cannot supply a thumbnail page`() {
        val changed = BookmarkResolution.SourceChanged(comicId = 42, approximateGlobalPage = 8)

        assertNull(changed.thumbnailPageOrNull())
        assertEquals(8, BookmarkResolution.Ready(42, 8).thumbnailPageOrNull())
    }

    private fun bookmark(
        id: Long,
        targetKey: String,
        pageIdentity: String?,
        sourceRevision: String,
        globalPageIndex: Int,
    ) = BookmarkEntity(
        id = id,
        comicId = 42,
        targetKey = targetKey,
        pageIdentity = pageIdentity,
        sourceRevision = sourceRevision,
        globalPageIndex = globalPageIndex,
        chapterIndex = 0,
        pageIndex = globalPageIndex,
        chapterTitle = "Chapter",
        addedAt = id,
    )
}
