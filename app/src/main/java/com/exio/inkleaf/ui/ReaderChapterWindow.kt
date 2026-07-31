package com.exio.inkleaf.ui

internal sealed interface ReaderChapterWindowKey

internal data class ReaderChapterPageKey(
    val chapterId: String,
    val chapterRevision: String,
    val pageIdentity: String,
) : ReaderChapterWindowKey {
    init {
        require(chapterId.isNotBlank())
        require(chapterRevision.isNotBlank())
        require(pageIdentity.isNotBlank())
    }
}

internal data class ReaderPageStateKey(
    val namespace: String,
    val pageIdentity: String,
) {
    init {
        require(namespace.isNotBlank())
        require(pageIdentity.isNotBlank())
    }
}

internal fun ReaderChapterPageKey.toReaderPageStateKey(): ReaderPageStateKey =
    ReaderPageStateKey(
        namespace = "$chapterId\u0000$chapterRevision",
        pageIdentity = pageIdentity,
    )

internal data class ReaderChapterBoundaryKey(
    val previousChapterId: String?,
    val nextChapterId: String?,
) : ReaderChapterWindowKey {
    init {
        require(previousChapterId != null || nextChapterId != null)
        require(previousChapterId != nextChapterId)
    }
}

internal data class ReaderWindowChapter<T>(
    val chapterId: String,
    val chapterIndex: Int,
    val chapterRevision: String,
    val pageIdentities: List<String>,
    val payload: T,
) {
    init {
        require(chapterId.isNotBlank())
        require(chapterIndex >= 0)
        require(chapterRevision.isNotBlank())
        require(pageIdentities.isNotEmpty())
        require(pageIdentities.all(String::isNotBlank))
        require(pageIdentities.distinct().size == pageIdentities.size)
    }

    fun pageKey(pageIndex: Int): ReaderChapterPageKey =
        ReaderChapterPageKey(
            chapterId = chapterId,
            chapterRevision = chapterRevision,
            pageIdentity = pageIdentities[pageIndex],
        )
}

internal data class ReaderWindowAdjacent<T>(
    val direction: ReaderTransitionDirection,
    val targetChapterId: String?,
    val transition: ReaderChapterTransition,
    val preparedChapter: ReaderWindowChapter<T>? = null,
) {
    init {
        require(transition.direction == direction)
        require(preparedChapter == null || preparedChapter.chapterId == targetChapterId)
    }
}

internal sealed interface ReaderChapterWindowItem<out T> {
    val stableKey: ReaderChapterWindowKey

    data class Page<T>(
        val chapter: ReaderWindowChapter<T>,
        val pageIndex: Int,
    ) : ReaderChapterWindowItem<T> {
        init {
            require(pageIndex in chapter.pageIdentities.indices)
        }

        val pageKey: ReaderChapterPageKey = chapter.pageKey(pageIndex)
        override val stableKey: ReaderChapterWindowKey = pageKey
    }

    data class Boundary(
        val boundaryKey: ReaderChapterBoundaryKey,
        val transition: ReaderChapterTransition,
    ) : ReaderChapterWindowItem<Nothing> {
        override val stableKey: ReaderChapterWindowKey = boundaryKey
    }
}

internal fun ReaderChapterWindowItem<*>.saveablePagerKey(): String =
    when (this) {
        is ReaderChapterWindowItem.Page ->
            encodePagerKey(
                "page",
                pageKey.chapterId,
                pageKey.chapterRevision,
                pageKey.pageIdentity,
            )
        is ReaderChapterWindowItem.Boundary ->
            encodePagerKey(
                "boundary",
                boundaryKey.previousChapterId,
                boundaryKey.nextChapterId,
            )
    }

private fun encodePagerKey(type: String, vararg parts: String?): String =
    buildString {
        append(type)
        append(':')
        parts.forEach { part ->
            if (part == null) {
                append("-1:")
            } else {
                append(part.length)
                append(':')
                append(part)
            }
        }
    }

internal data class ReaderChapterWindow<T>(
    val activeChapterId: String,
    val items: List<ReaderChapterWindowItem<T>>,
) {
    init {
        require(items.isNotEmpty())
        require(items.map { it.stableKey }.distinct().size == items.size)
        require(
            items.filterIsInstance<ReaderChapterWindowItem.Page<*>>()
                .any { it.chapter.chapterId == activeChapterId }
        )
    }
}

internal fun <T> ReaderChapterWindow<T>.contextPageAt(
    itemIndex: Int,
): ReaderChapterWindowItem.Page<T> {
    val activePage =
        items.filterIsInstance<ReaderChapterWindowItem.Page<T>>()
            .first { it.chapter.chapterId == activeChapterId }
    val item = items.getOrNull(itemIndex) ?: return activePage
    if (item is ReaderChapterWindowItem.Page) return item
    val direction =
        when (item) {
            is ReaderChapterWindowItem.Boundary -> item.transition.direction
            is ReaderChapterWindowItem.Page -> return item
        }
    val searchIndices =
        if (direction == ReaderTransitionDirection.NEXT) {
            itemIndex - 1 downTo 0
        } else {
            itemIndex + 1..items.lastIndex
        }
    for (candidateIndex in searchIndices) {
        val candidate = items[candidateIndex]
        if (candidate is ReaderChapterWindowItem.Page) return candidate
    }
    return activePage
}

internal fun <T> buildReaderChapterWindow(
    active: ReaderWindowChapter<T>,
    previous: ReaderWindowAdjacent<T>?,
    next: ReaderWindowAdjacent<T>?,
): ReaderChapterWindow<T> {
    require(previous == null || previous.direction == ReaderTransitionDirection.PREVIOUS)
    require(next == null || next.direction == ReaderTransitionDirection.NEXT)
    val items = buildList {
        previous?.let { adjacent ->
            val boundary =
                ReaderChapterBoundaryKey(
                    previousChapterId = adjacent.targetChapterId,
                    nextChapterId = active.chapterId,
                )
            val prepared = adjacent.preparedChapter
            if (prepared != null) {
                prepared.pageIdentities.indices.forEach { add(ReaderChapterWindowItem.Page(prepared, it)) }
            }
            add(ReaderChapterWindowItem.Boundary(boundary, adjacent.transition))
        }
        active.pageIdentities.indices.forEach { add(ReaderChapterWindowItem.Page(active, it)) }
        next?.let { adjacent ->
            val boundary =
                ReaderChapterBoundaryKey(
                    previousChapterId = active.chapterId,
                    nextChapterId = adjacent.targetChapterId,
                )
            add(ReaderChapterWindowItem.Boundary(boundary, adjacent.transition))
            val prepared = adjacent.preparedChapter
            if (prepared != null) {
                prepared.pageIdentities.indices.forEach { add(ReaderChapterWindowItem.Page(prepared, it)) }
            }
        }
    }
    return ReaderChapterWindow(activeChapterId = active.chapterId, items = items)
}

internal sealed interface ReaderPageTurnResult {
    data class MoveTo(val index: Int) : ReaderPageTurnResult

    data object NoChange : ReaderPageTurnResult
}

internal fun readerWindowIndexForChapterPage(
    window: ReaderChapterWindow<*>?,
    chapterId: String?,
    pageIndex: Int,
): Int {
    if (window == null) return pageIndex
    if (chapterId == null) return -1
    return window.items.indexOfFirst { item ->
        item is ReaderChapterWindowItem.Page<*> &&
            item.chapter.chapterId == chapterId &&
            item.pageIndex == pageIndex
    }
}

internal fun readerPageTurnResult(
    items: List<ReaderChapterWindowItem<*>>,
    currentIndex: Int,
    delta: Int,
): ReaderPageTurnResult {
    require(currentIndex in items.indices)
    require(delta == -1 || delta == 1)
    val targetIndex = currentIndex + delta
    if (targetIndex !in items.indices) return ReaderPageTurnResult.NoChange
    return ReaderPageTurnResult.MoveTo(targetIndex)
}

internal fun canAdoptReaderChapterWindow(pagerIsScrolling: Boolean): Boolean = !pagerIsScrolling

internal data class ReaderChapterWindowAdoption(
    val targetIndex: Int,
    val fallbackIndex: Int,
    val anchoredToCurrentKey: Boolean,
) {
    val requiresExplicitScroll: Boolean = !anchoredToCurrentKey
}

internal fun <T> readerChapterWindowAdoption(
    currentWindow: ReaderChapterWindow<T>,
    currentIndex: Int,
    nextWindow: ReaderChapterWindow<T>,
    startPage: Int,
): ReaderChapterWindowAdoption {
    val fallbackIndex =
        nextWindow.items.indexOfFirst { item ->
            item is ReaderChapterWindowItem.Page<*> &&
                item.chapter.chapterId == nextWindow.activeChapterId &&
                item.pageIndex == startPage
        }.takeIf { it >= 0 } ?: 0
    val currentKey = currentWindow.items.getOrNull(currentIndex)?.stableKey
    val anchoredIndex =
        currentKey?.let { key -> nextWindow.items.indexOfFirst { it.stableKey == key } }
            ?.takeIf { it >= 0 }
    return ReaderChapterWindowAdoption(
        targetIndex = anchoredIndex ?: fallbackIndex,
        fallbackIndex = fallbackIndex,
        anchoredToCurrentKey = anchoredIndex != null,
    )
}

internal sealed interface ReaderSettledPageEffect {
    data class CommitChapter(
        val chapterId: String,
        val chapterIndex: Int,
        val pageIndex: Int,
    ) : ReaderSettledPageEffect

    data object None : ReaderSettledPageEffect
}

internal fun readerSettledPageEffect(
    activeChapterId: String,
    item: ReaderChapterWindowItem<*>,
): ReaderSettledPageEffect =
    when (item) {
        is ReaderChapterWindowItem.Page ->
            if (item.chapter.chapterId == activeChapterId) {
                ReaderSettledPageEffect.None
            } else {
                ReaderSettledPageEffect.CommitChapter(
                    chapterId = item.chapter.chapterId,
                    chapterIndex = item.chapter.chapterIndex,
                    pageIndex = item.pageIndex,
                )
            }
        is ReaderChapterWindowItem.Boundary -> ReaderSettledPageEffect.None
    }
