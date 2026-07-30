package com.exio.inkleaf.ui

import com.exio.inkleaf.data.ShelfGroupFilterKind
import com.exio.inkleaf.data.ShelfGroupSelection
import com.exio.inkleaf.data.db.BookSourceType
import com.exio.inkleaf.data.db.ChapterEntity
import com.exio.inkleaf.data.db.ComicEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfEntryModelsTest {
    @Test
    fun `multi chapter progress is converted to a whole comic page`() {
        val comic = comic(pageCount = 30, chapterIndex = 1, page = 2)
        val progress = wholeComicProgress(comic, listOf(chapter(0, 10), chapter(1, 20)))

        assertEquals(ShelfProgress(currentPage = 13, totalPages = 30), progress)
    }

    @Test
    fun `unknown preceding chapter count hides misleading progress`() {
        val comic = comic(pageCount = 30, chapterIndex = 1, page = 2)

        assertNull(wholeComicProgress(comic, listOf(chapter(0, 0), chapter(1, 20))))
    }

    @Test
    fun `missing preceding chapter contributes zero pages`() {
        val comic = comic(pageCount = 20, chapterIndex = 1, page = 2)
        val progress =
            wholeComicProgress(
                comic,
                listOf(chapter(0, 10, isMissing = true), chapter(1, 20)),
            )

        assertEquals(ShelfProgress(currentPage = 3, totalPages = 20), progress)
    }

    @Test
    fun `missing current chapter hides progress`() {
        val comic = comic(pageCount = 20, chapterIndex = 1, page = 2)

        assertNull(
            wholeComicProgress(
                comic,
                listOf(chapter(0, 20), chapter(1, 10, isMissing = true)),
            )
        )
    }

    @Test
    fun `local filter excludes online entries`() {
        val entries =
            buildShelfEntries(
                comics = listOf(comic()),
                chapters = emptyList(),
                online = emptyList(),
                selection = ShelfGroupSelection(ShelfGroupFilterKind.LOCAL),
            )

        assertEquals(listOf("local:1"), entries.map(ShelfEntry::key))
    }

    private fun comic(
        pageCount: Int = 10,
        chapterIndex: Int = 0,
        page: Int = 0,
    ) =
        ComicEntity(
            id = 1,
            uri = "content://comic/1",
            fileKey = "comic-1",
            title = "Comic",
            pageCount = pageCount,
            lastReadChapterIndex = chapterIndex,
            lastReadPage = page,
            addedAt = 1,
            sourceType = BookSourceType.PDF_SERIES,
        )

    private fun chapter(index: Int, pageCount: Int, isMissing: Boolean = false) =
        ChapterEntity(
            id = index.toLong() + 1,
            comicId = 1,
            chapterIndex = index,
            uri = "content://chapter/$index",
            fileKey = "chapter-$index",
            title = "Chapter $index",
            pageCount = pageCount,
            isMissing = isMissing,
        )
}
