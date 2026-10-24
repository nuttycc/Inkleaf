package com.exio.inkleaf.ui

import com.exio.inkleaf.data.ShelfFilter
import com.exio.inkleaf.data.ShelfGroupFilter
import com.exio.inkleaf.data.ShelfSourceFilter
import com.exio.inkleaf.data.db.ChapterEntity
import com.exio.inkleaf.data.db.ComicEntity
import com.exio.inkleaf.data.db.GroupWithCount
import com.exio.inkleaf.plugin.OnlineComicRecord

internal data class ShelfProgress(val currentPage: Int, val totalPages: Int)

/** 在线书架条目 + 它的自定义分组；分组关系来自 Room，与在线 JSON 记录分开存储。 */
internal data class ShelfOnlineItem(
    val record: OnlineComicRecord,
    val groupId: Long?,
)

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

    data class Online(
        val record: OnlineComicRecord,
        val groupId: Long?,
    ) : ShelfEntry {
        override val key = "online:${record.key.pluginId}:${record.key.sourceId}"
        override val title = record.detail?.title ?: record.key.sourceId
        override val sortTimeMs = record.lastSeenAtMs
    }
}

internal data class ShelfUiState(val entries: List<ShelfEntry>)

internal fun buildShelfEntries(
    comics: List<ComicEntity>,
    chapters: List<ChapterEntity>,
    online: List<ShelfOnlineItem>,
    filter: ShelfFilter,
): List<ShelfEntry> {
    val chaptersByComic = chapters.groupBy(ChapterEntity::comicId)
    val localEntries =
        comics
            .asSequence()
            .filter { filter.source != ShelfSourceFilter.ONLINE && filter.matchesGroup(it.groupId) }
            .map { comic ->
                ShelfEntry.Local(
                    comic = comic,
                    progress = wholeComicProgress(comic, chaptersByComic[comic.id].orEmpty()),
                )
            }
    val onlineEntries =
        if (filter.source == ShelfSourceFilter.LOCAL) {
            emptySequence()
        } else {
            online
                .asSequence()
                .filter { filter.matchesGroup(it.groupId) }
                .map { ShelfEntry.Online(it.record, it.groupId) }
        }
    return (localEntries + onlineEntries)
        .sortedWith(
            compareByDescending<ShelfEntry>(ShelfEntry::sortTimeMs)
                .thenBy(String.CASE_INSENSITIVE_ORDER, ShelfEntry::title)
                .thenBy(ShelfEntry::key)
        )
        .toList()
}

private fun ShelfFilter.matchesGroup(groupId: Long?): Boolean =
    when (val target = group) {
        ShelfGroupFilter.All -> true
        ShelfGroupFilter.Ungrouped -> groupId == null
        is ShelfGroupFilter.Group -> groupId == target.id
    }

internal fun ShelfFilter.withoutGroup(groupId: Long): ShelfFilter =
    if ((group as? ShelfGroupFilter.Group)?.id == groupId) {
        copy(group = ShelfGroupFilter.All)
    } else {
        this
    }

internal fun shelfSourceLabel(source: ShelfSourceFilter): String =
    when (source) {
        ShelfSourceFilter.ALL -> "全部"
        ShelfSourceFilter.LOCAL -> "本地"
        ShelfSourceFilter.ONLINE -> "在线"
    }

internal fun shelfFilterTitle(
    filter: ShelfFilter,
    groups: List<GroupWithCount>?,
): String {
    val sourcePart =
        filter.source.takeUnless { it == ShelfSourceFilter.ALL }?.let(::shelfSourceLabel)
    val groupPart =
        when (val group = filter.group) {
            ShelfGroupFilter.All -> null
            ShelfGroupFilter.Ungrouped -> "未分组"
            is ShelfGroupFilter.Group ->
                groups?.firstOrNull { it.group.id == group.id }?.group?.name ?: "分组"
        }
    return listOfNotNull(sourcePart, groupPart).joinToString(" · ").ifEmpty { "全部" }
}

internal fun shelfEmptyTitle(filter: ShelfFilter): String {
    val source = filter.source.takeUnless { it == ShelfSourceFilter.ALL }?.let(::shelfSourceLabel)
    return when (filter.group) {
        is ShelfGroupFilter.Group ->
            source?.let { "这个分组里还没有${it}漫画" } ?: "这个分组里还没有漫画"

        ShelfGroupFilter.Ungrouped ->
            source?.let { "没有未分组的${it}漫画" } ?: "没有未分组的漫画"

        ShelfGroupFilter.All ->
            when (filter.source) {
                ShelfSourceFilter.ALL -> "还没有漫画"
                ShelfSourceFilter.LOCAL -> "还没有本地漫画"
                ShelfSourceFilter.ONLINE -> "还没有在线追读"
            }
    }
}

/** 分组数量 = 本地漫画数 + 当前书架上的在线漫画数；在线记录不在 Room 中，所以在这里合并。 */
internal fun withOnlineGroupCounts(
    groups: List<GroupWithCount>,
    online: List<ShelfOnlineItem>,
): List<GroupWithCount> {
    if (online.isEmpty()) return groups
    val countsByGroup = online.mapNotNull(ShelfOnlineItem::groupId).groupingBy { it }.eachCount()
    return groups.map { item ->
        val onlineCount = countsByGroup[item.group.id] ?: 0
        if (onlineCount == 0) item else item.copy(comicCount = item.comicCount + onlineCount)
    }
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

/**
 * 在线追读卡片的阅读进度文案。使用章节标题；章节缺失时只显示页码。
 * 无阅读位置时返回 null，由调用方显示来源名。
 */
internal fun onlineProgressLabel(record: OnlineComicRecord): String? {
    val position = record.position ?: return null
    val page = position.pageIndex + 1
    val chapter = record.chapters.firstOrNull { it.chapterId == position.chapterId }
    val chapterLabel = chapter?.title?.takeIf(String::isNotBlank)
    return chapterLabel?.let { "$it · 第 $page 页" } ?: "第 $page 页"
}
