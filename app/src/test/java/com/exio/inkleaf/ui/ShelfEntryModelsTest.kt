package com.exio.inkleaf.ui

import com.exio.inkleaf.data.ShelfGroupFilterKind
import com.exio.inkleaf.data.ShelfGroupSelection
import com.exio.inkleaf.data.db.BookSourceType
import com.exio.inkleaf.data.db.ChapterEntity
import com.exio.inkleaf.data.db.ComicEntity
import com.exio.inkleaf.plugin.ChapterSummary
import com.exio.inkleaf.plugin.OnlineComicRecord
import com.exio.inkleaf.plugin.OnlineContentKey
import com.exio.inkleaf.plugin.OnlineReadingPosition
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

    @Test
    fun `online progress shows chapter title and page`() {
        val record =
            onlineRecord(
                chapters =
                    listOf(
                        ChapterSummary(chapterId = "c-1", title = "第 1 话", number = 1.0),
                        ChapterSummary(chapterId = "c-2", title = "第 2 话", number = 2.0),
                        ChapterSummary(chapterId = "c-3", title = "第 3 话", number = 3.0),
                    ),
                chapterId = "c-3",
                pageIndex = 4,
            )

        assertEquals("第 3 话 · 第 5 页", onlineProgressLabel(record))
    }

    @Test
    fun `online progress uses matched chapter title when chapters are newest first`() {
        val record =
            onlineRecord(
                chapters =
                    listOf(
                        ChapterSummary(chapterId = "c-42", title = "第 42 话", number = 2.0),
                        ChapterSummary(chapterId = "c-10", title = "第 10 话", number = 42.0),
                    ),
                chapterId = "c-42",
                pageIndex = 4,
            )

        assertEquals("第 42 话 · 第 5 页", onlineProgressLabel(record))
    }

    @Test
    fun `online progress ignores misleading decimal chapter number`() {
        val record =
            onlineRecord(
                chapters =
                    listOf(ChapterSummary(chapterId = "c-35", title = "第 3 话", number = 3.5)),
                chapterId = "c-35",
                pageIndex = 4,
            )

        assertEquals("第 3 话 · 第 5 页", onlineProgressLabel(record))
    }

    @Test
    fun `online progress ignores list ordinal when extras are present`() {
        val chapters =
            (1..11).map { number ->
                ChapterSummary(
                    chapterId = "c-$number",
                    title = "第 $number 话",
                    number = number.toDouble(),
                )
            } +
                listOf(
                    ChapterSummary(chapterId = "extra-1", title = "番外 A", number = 12.0),
                    ChapterSummary(chapterId = "extra-2", title = "番外 B", number = 13.0),
                    ChapterSummary(chapterId = "extra-3", title = "番外 C", number = 14.0),
                    ChapterSummary(chapterId = "c-12", title = "第 12 话", number = 15.0),
                )
        val record = onlineRecord(chapters = chapters, chapterId = "c-12", pageIndex = 4)

        assertEquals("第 12 话 · 第 5 页", onlineProgressLabel(record))
    }

    @Test
    fun `online progress uses chapter title when number is missing`() {
        val record =
            onlineRecord(
                chapters = listOf(ChapterSummary(chapterId = "extra", title = "番外")),
                chapterId = "extra",
                pageIndex = 4,
            )

        assertEquals("番外 · 第 5 页", onlineProgressLabel(record))
    }

    @Test
    fun `online progress falls back to page only when chapters are missing`() {
        val record = onlineRecord(chapters = emptyList(), chapterId = "c-3", pageIndex = 4)

        assertEquals("第 5 页", onlineProgressLabel(record))
    }

    @Test
    fun `online progress falls back to page only when chapter id is unknown`() {
        val record =
            onlineRecord(
                chapters = listOf(ChapterSummary(chapterId = "c-1", title = "第 1 话")),
                chapterId = "missing",
                pageIndex = 4,
            )

        assertEquals("第 5 页", onlineProgressLabel(record))
    }

    @Test
    fun `online progress is null without a reading position`() {
        val record = onlineRecord(chapters = emptyList(), chapterId = null, pageIndex = 0)

        assertNull(onlineProgressLabel(record))
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

    private fun onlineRecord(
        chapters: List<ChapterSummary>,
        chapterId: String?,
        pageIndex: Int,
    ) =
        OnlineComicRecord(
            key = OnlineContentKey("io.example.source", "comic-1"),
            chapters = chapters,
            position =
                chapterId?.let {
                    OnlineReadingPosition(
                        chapterId = it,
                        pageIndex = pageIndex,
                        updatedAtMs = 1,
                    )
                },
        )
}
