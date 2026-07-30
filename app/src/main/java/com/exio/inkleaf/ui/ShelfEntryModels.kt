package com.exio.inkleaf.ui

import com.exio.inkleaf.data.ShelfGroupFilterKind
import com.exio.inkleaf.data.ShelfGroupSelection
import com.exio.inkleaf.data.db.ChapterEntity
import com.exio.inkleaf.data.db.ComicEntity
import com.exio.inkleaf.plugin.OnlineComicRecord

internal data class ShelfProgress(val currentPage: Int, val totalPages: Int)

internal sealed interface ShelfEntry {
    val key: String
    val title: String
    val sortTimeMs: Long

    data class Local(
        val comic: ComicEntity,
        val progress: ShelfProgress?,
    ) : ShelfEntry {
        override val key = "local:${comic.id}"
        override val title = comic.title
        override val sortTimeMs = maxOf(comic.lastReadAt, comic.addedAt)
    }

    data class Online(val record: OnlineComicRecord) : ShelfEntry {
        override val key = "online:${record.key.pluginId}:${record.key.sourceId}"
        override val title = record.detail?.title ?: record.key.sourceId
        override val sortTimeMs = record.lastSeenAtMs
    }
}

internal data class ShelfUiState(val entries: List<ShelfEntry>)

internal fun buildShelfEntries(
    comics: List<ComicEntity>,
    chapters: List<ChapterEntity>,
    online: List<OnlineComicRecord>,
    selection: ShelfGroupSelection,
): List<ShelfEntry> {
    val chaptersByComic = chapters.groupBy(ChapterEntity::comicId)
    val localEntries =
        comics
            .asSequence()
            .filter { comic ->
                when (selection.kind) {
                    ShelfGroupFilterKind.ALL, ShelfGroupFilterKind.LOCAL -> true
                    ShelfGroupFilterKind.ONLINE -> false
                    ShelfGroupFilterKind.UNGROUPED -> comic.groupId == null
                    ShelfGroupFilterKind.GROUP -> comic.groupId == selection.groupId
                }
            }
            .map { comic ->
                ShelfEntry.Local(
                    comic = comic,
                    progress = wholeComicProgress(comic, chaptersByComic[comic.id].orEmpty()),
                )
            }
    val onlineEntries =
        if (selection.kind == ShelfGroupFilterKind.ALL || selection.kind == ShelfGroupFilterKind.ONLINE) {
            online.asSequence().map { ShelfEntry.Online(it) }
        } else {
            emptySequence()
        }
    return (localEntries + onlineEntries)
        .sortedWith(
            compareByDescending<ShelfEntry>(ShelfEntry::sortTimeMs)
                .thenBy(String.CASE_INSENSITIVE_ORDER, ShelfEntry::title)
                .thenBy(ShelfEntry::key)
        )
}

internal fun wholeComicProgress(
    comic: ComicEntity,
    chapters: List<ChapterEntity>,
): ShelfProgress? {
    if (comic.pageCount <= 0) return null
    if (chapters.isEmpty()) {
        return ShelfProgress(
            currentPage = (comic.lastReadPage + 1).coerceIn(1, comic.pageCount),
            totalPages = comic.pageCount,
        )
    }
    val ordered = chapters.sortedBy(ChapterEntity::chapterIndex)
    val currentOffset = ordered.indexOfFirst { it.chapterIndex == comic.lastReadChapterIndex }
    if (currentOffset < 0) return null
    val preceding = ordered.take(currentOffset)
    if (preceding.any { !it.isMissing && it.pageCount <= 0 }) return null
    val currentChapter = ordered[currentOffset]
    if (currentChapter.isMissing) return null
    val currentChapterPages = currentChapter.pageCount
    if (currentChapterPages <= 0) return null
    val current =
        preceding.sumOf { chapter -> if (chapter.isMissing) 0 else chapter.pageCount } +
            comic.lastReadPage +
            1
    return ShelfProgress(
        currentPage = current.coerceIn(1, comic.pageCount),
        totalPages = comic.pageCount,
    )
}
