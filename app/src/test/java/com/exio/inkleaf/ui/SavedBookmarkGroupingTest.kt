package com.exio.inkleaf.ui

import com.exio.inkleaf.data.db.BookSourceType
import com.exio.inkleaf.data.db.BookmarkEntity
import com.exio.inkleaf.data.db.BookmarkWithComic
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedBookmarkGroupingTest {
    @Test
    fun `groups are ordered by their newest bookmark`() {
        val olderGroup = bookmark(
            id = 1,
            comicId = 10,
            comicTitle = "Older comic",
            addedAt = 100,
        )
        val newerGroup = bookmark(
            id = 2,
            comicId = 20,
            comicTitle = "Newer comic",
            addedAt = 200,
        )

        val result = groupAndSortBookmarks(listOf(olderGroup, newerGroup))

        assertEquals(listOf(20L, 10L), result.map { it.comicId })
    }

    @Test
    fun `bookmarks in a comic are ordered by chapter then page`() {
        val result = groupAndSortBookmarks(
            listOf(
                bookmark(id = 1, chapterIndex = 2, pageIndex = 0),
                bookmark(id = 2, chapterIndex = 0, pageIndex = 8),
                bookmark(id = 3, chapterIndex = 0, pageIndex = 3),
                bookmark(id = 4, chapterIndex = 1, pageIndex = 1),
            ),
        )

        assertEquals(listOf(3L, 2L, 4L, 1L), result.single().bookmarks.map { it.bookmark.id })
    }

    @Test
    fun `equal positions use stable bookmark id ordering`() {
        val result = groupAndSortBookmarks(
            listOf(
                bookmark(id = 9, chapterIndex = 1, pageIndex = 2),
                bookmark(id = 4, chapterIndex = 1, pageIndex = 2),
            ),
        )

        assertEquals(listOf(4L, 9L), result.single().bookmarks.map { it.bookmark.id })
    }

    private fun bookmark(
        id: Long,
        comicId: Long = 1,
        comicTitle: String = "Comic",
        chapterIndex: Int = 0,
        pageIndex: Int = 0,
        addedAt: Long = id,
    ) = BookmarkWithComic(
        bookmark = BookmarkEntity(
            id = id,
            comicId = comicId,
            targetKey = "target-$id",
            pageIdentity = null,
            sourceRevision = "revision",
            globalPageIndex = pageIndex,
            chapterIndex = chapterIndex,
            pageIndex = pageIndex,
            chapterTitle = "Chapter ${chapterIndex + 1}",
            addedAt = addedAt,
        ),
        comicTitle = comicTitle,
        coverPath = null,
        isMissing = false,
        sourceType = BookSourceType.EXTERNAL_ARCHIVE,
    )
}
