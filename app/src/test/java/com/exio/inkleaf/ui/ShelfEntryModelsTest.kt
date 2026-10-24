package com.exio.inkleaf.ui

import com.exio.inkleaf.data.ShelfFilter
import com.exio.inkleaf.data.ShelfGroupFilter
import com.exio.inkleaf.data.ShelfSourceFilter
import com.exio.inkleaf.data.db.BookSourceType
import com.exio.inkleaf.data.db.ChapterEntity
import com.exio.inkleaf.data.db.ComicEntity
import com.exio.inkleaf.data.db.ComicGroupEntity
import com.exio.inkleaf.data.db.GroupWithCount
import com.exio.inkleaf.plugin.ChapterSummary
import com.exio.inkleaf.plugin.OnlineComicRecord
import com.exio.inkleaf.plugin.OnlineContentKey
import com.exio.inkleaf.plugin.OnlineReadingPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                online = listOf(onlineItem(onlineRecord())),
                filter = ShelfFilter(source = ShelfSourceFilter.LOCAL),
            )

        assertEquals(listOf("local:1"), entries.map(ShelfEntry::key))
    }

    @Test
    fun `online filter excludes local entries`() {
        val entries =
            buildShelfEntries(
                comics = listOf(comic()),
                chapters = emptyList(),
                online = listOf(onlineItem(onlineRecord())),
                filter = ShelfFilter(source = ShelfSourceFilter.ONLINE),
            )

        assertEquals(listOf("online:io.example.source:comic-1"), entries.map(ShelfEntry::key))
    }

    @Test
    fun `all source combines local and online entries`() {
        val entries =
            buildShelfEntries(
                comics = listOf(comic()),
                chapters = emptyList(),
                online = listOf(onlineItem(onlineRecord())),
                filter = ShelfFilter(),
            )

        assertEquals(
            setOf("local:1", "online:io.example.source:comic-1"),
            entries.map(ShelfEntry::key).toSet(),
        )
    }

    @Test
    fun `group filter matches both local and online members`() {
        val entries =
            buildShelfEntries(
                comics = listOf(comic(id = 1, groupId = 7), comic(id = 2, groupId = 8)),
                chapters = emptyList(),
                online =
                    listOf(
                        onlineItem(onlineRecord(), groupId = 7),
                        onlineItem(onlineRecord(sourceId = "comic-2")),
                    ),
                filter =
                    ShelfFilter(
                        source = ShelfSourceFilter.ALL,
                        group = ShelfGroupFilter.Group(7),
                    ),
            )

        assertEquals(
            setOf("local:1", "online:io.example.source:comic-1"),
            entries.map(ShelfEntry::key).toSet(),
        )
        assertEquals(7L, entries.filterIsInstance<ShelfEntry.Online>().single().groupId)
    }

    @Test
    fun `source and group filters compose independently`() {
        val comics = listOf(comic(id = 1, groupId = 7), comic(id = 2))
        val online =
            listOf(
                onlineItem(onlineRecord(), groupId = 7),
                onlineItem(onlineRecord(sourceId = "comic-2")),
            )

        assertEquals(
            setOf("local:1"),
            keys(
                ShelfFilter(ShelfSourceFilter.LOCAL, ShelfGroupFilter.Group(7)),
                comics,
                online,
            ),
        )
        assertEquals(
            setOf("online:io.example.source:comic-1"),
            keys(
                ShelfFilter(ShelfSourceFilter.ONLINE, ShelfGroupFilter.Group(7)),
                comics,
                online,
            ),
        )
        assertEquals(
            setOf("local:2"),
            keys(
                ShelfFilter(ShelfSourceFilter.LOCAL, ShelfGroupFilter.Ungrouped),
                comics,
                online,
            ),
        )
        assertEquals(
            setOf("online:io.example.source:comic-2"),
            keys(
                ShelfFilter(ShelfSourceFilter.ONLINE, ShelfGroupFilter.Ungrouped),
                comics,
                online,
            ),
        )
    }

    @Test
    fun `ungrouped filter matches ungrouped local and online entries`() {
        val entries =
            buildShelfEntries(
                comics = listOf(comic(id = 1), comic(id = 2, groupId = 7)),
                chapters = emptyList(),
                online =
                    listOf(
                        onlineItem(onlineRecord()),
                        onlineItem(onlineRecord(sourceId = "comic-2"), groupId = 7),
                    ),
                filter = ShelfFilter(group = ShelfGroupFilter.Ungrouped),
            )

        assertEquals(
            setOf("local:1", "online:io.example.source:comic-1"),
            entries.map(ShelfEntry::key).toSet(),
        )
    }

    @Test
    fun `default filter requires both dimensions to be all`() {
        assertTrue(ShelfFilter().isDefault)
        assertFalse(ShelfFilter(source = ShelfSourceFilter.LOCAL).isDefault)
        assertFalse(ShelfFilter(group = ShelfGroupFilter.Ungrouped).isDefault)
    }

    @Test
    fun `empty title reflects source and group`() {
        assertEquals(
            "还没有在线追读",
            shelfEmptyTitle(ShelfFilter(source = ShelfSourceFilter.ONLINE)),
        )
        assertEquals(
            "这个分组里还没有在线漫画",
            shelfEmptyTitle(
                ShelfFilter(
                    source = ShelfSourceFilter.ONLINE,
                    group = ShelfGroupFilter.Group(7),
                )
            ),
        )
        assertEquals(
            "没有未分组的本地漫画",
            shelfEmptyTitle(
                ShelfFilter(
                    source = ShelfSourceFilter.LOCAL,
                    group = ShelfGroupFilter.Ungrouped,
                )
            ),
        )
        assertEquals("还没有漫画", shelfEmptyTitle(ShelfFilter()))
        assertEquals(
            "没有未分组的漫画",
            shelfEmptyTitle(ShelfFilter(group = ShelfGroupFilter.Ungrouped)),
        )
        assertEquals(
            "这个分组里还没有漫画",
            shelfEmptyTitle(ShelfFilter(group = ShelfGroupFilter.Group(7))),
        )
        assertEquals(
            "这个分组里还没有本地漫画",
            shelfEmptyTitle(
                ShelfFilter(
                    source = ShelfSourceFilter.LOCAL,
                    group = ShelfGroupFilter.Group(7),
                )
            ),
        )
    }

    @Test
    fun `filter title combines source and group labels`() {
        val groups =
            listOf(
                GroupWithCount(
                    ComicGroupEntity(id = 7, name = "Favorite 1", createdAt = 1),
                    3,
                )
            )

        assertEquals("全部", shelfFilterTitle(ShelfFilter(), groups))
        assertEquals(
            "本地",
            shelfFilterTitle(ShelfFilter(source = ShelfSourceFilter.LOCAL), groups),
        )
        assertEquals(
            "Favorite 1",
            shelfFilterTitle(ShelfFilter(group = ShelfGroupFilter.Group(7)), groups),
        )
        assertEquals(
            "本地 · Favorite 1",
            shelfFilterTitle(
                ShelfFilter(
                    source = ShelfSourceFilter.LOCAL,
                    group = ShelfGroupFilter.Group(7),
                ),
                groups,
            ),
        )
        assertEquals(
            "在线 · 未分组",
            shelfFilterTitle(
                ShelfFilter(
                    source = ShelfSourceFilter.ONLINE,
                    group = ShelfGroupFilter.Ungrouped,
                ),
                groups,
            ),
        )
        assertEquals(
            "分组",
            shelfFilterTitle(ShelfFilter(group = ShelfGroupFilter.Group(99)), groups),
        )
    }

    @Test
    fun `removing active group preserves source`() {
        val filter =
            ShelfFilter(
                source = ShelfSourceFilter.ONLINE,
                group = ShelfGroupFilter.Group(7),
            )

        assertEquals(
            ShelfFilter(source = ShelfSourceFilter.ONLINE),
            filter.withoutGroup(7),
        )
        assertEquals(filter, filter.withoutGroup(8))
    }

    @Test
    fun `group counts include matching online shelf items`() {
        val groups =
            listOf(
                GroupWithCount(ComicGroupEntity(id = 7, name = "Favorite 1", createdAt = 1), 2),
                GroupWithCount(ComicGroupEntity(id = 8, name = "Favorite 2", createdAt = 2), 1),
            )
        val online =
            listOf(
                onlineItem(onlineRecord(), groupId = 7),
                onlineItem(onlineRecord(sourceId = "comic-2"), groupId = 7),
                onlineItem(onlineRecord(sourceId = "comic-3")),
            )

        val merged = withOnlineGroupCounts(groups, online)

        assertEquals(listOf(4, 1), merged.map(GroupWithCount::comicCount))
        assertEquals(groups, withOnlineGroupCounts(groups, emptyList()))
        assertEquals(
            groups,
            withOnlineGroupCounts(
                groups,
                listOf(onlineItem(onlineRecord(), groupId = 99)),
            ),
        )
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
        id: Long = 1,
        pageCount: Int = 10,
        chapterIndex: Int = 0,
        page: Int = 0,
        groupId: Long? = null,
    ) =
        ComicEntity(
            id = id,
            uri = "content://comic/$id",
            fileKey = "comic-$id",
            title = "Comic $id",
            pageCount = pageCount,
            lastReadChapterIndex = chapterIndex,
            lastReadPage = page,
            addedAt = 1,
            groupId = groupId,
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
        chapters: List<ChapterSummary> = emptyList(),
        chapterId: String? = null,
        pageIndex: Int = 0,
        sourceId: String = "comic-1",
    ) =
        OnlineComicRecord(
            key = OnlineContentKey("io.example.source", sourceId),
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

    private fun onlineItem(record: OnlineComicRecord, groupId: Long? = null) =
        ShelfOnlineItem(record = record, groupId = groupId)

    private fun keys(
        filter: ShelfFilter,
        comics: List<ComicEntity>,
        online: List<ShelfOnlineItem>,
    ): Set<String> =
        buildShelfEntries(
                comics = comics,
                chapters = emptyList(),
                online = online,
                filter = filter,
            )
            .map(ShelfEntry::key)
            .toSet()
}
