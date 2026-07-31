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

    data class Guard(
        val boundaryKey: ReaderChapterBoundaryKey,
        val direction: ReaderTransitionDirection,
    ) : ReaderChapterWindowItem<Nothing> {
        override val stableKey: ReaderChapterWindowKey =
            ReaderBoundaryGuardKey(boundaryKey, direction)
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
        is ReaderChapterWindowItem.Guard ->
            encodePagerKey(
                "guard",
                boundaryKey.previousChapterId,
                boundaryKey.nextChapterId,
                direction.name,
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

private data class ReaderBoundaryGuardKey(
    val boundary: ReaderChapterBoundaryKey,
    val direction: ReaderTransitionDirection,
) : ReaderChapterWindowKey

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
            if (prepared == null) {
                add(ReaderChapterWindowItem.Guard(boundary, ReaderTransitionDirection.PREVIOUS))
            } else {
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
            if (prepared == null) {
                add(ReaderChapterWindowItem.Guard(boundary, ReaderTransitionDirection.NEXT))
            } else {
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

internal sealed interface ReaderBoundaryIntentEffect {
    data object PublishPreparedPages : ReaderBoundaryIntentEffect

    data object RetryPreparation : ReaderBoundaryIntentEffect

    data object None : ReaderBoundaryIntentEffect
}

internal fun readerBoundaryIntentEffect(
    status: ReaderTransitionStatus?
): ReaderBoundaryIntentEffect =
    when (status) {
        ReaderTransitionStatus.Ready -> ReaderBoundaryIntentEffect.PublishPreparedPages
        ReaderTransitionStatus.Error -> ReaderBoundaryIntentEffect.RetryPreparation
        ReaderTransitionStatus.Loading,
        ReaderTransitionStatus.Boundary,
        null -> ReaderBoundaryIntentEffect.None
    }

internal fun readerGuardSettledEffect(
    status: ReaderTransitionStatus?
): ReaderBoundaryIntentEffect =
    if (status == ReaderTransitionStatus.Error) {
        ReaderBoundaryIntentEffect.RetryPreparation
    } else {
        ReaderBoundaryIntentEffect.None
    }

internal fun readerPageTurnResult(
    items: List<ReaderChapterWindowItem<*>>,
    currentIndex: Int,
    delta: Int,
): ReaderPageTurnResult {
    require(currentIndex in items.indices)
    require(delta == -1 || delta == 1)
    val targetIndex = currentIndex + delta
    val target = items.getOrNull(targetIndex) ?: return ReaderPageTurnResult.NoChange
    return ReaderPageTurnResult.MoveTo(targetIndex)
}

internal sealed interface ReaderSettledPageEffect {
    data class CommitChapter(
        val chapterId: String,
        val chapterIndex: Int,
        val pageIndex: Int,
    ) : ReaderSettledPageEffect

    data class ReboundBoundary(val direction: ReaderTransitionDirection) : ReaderSettledPageEffect

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
        is ReaderChapterWindowItem.Guard ->
            ReaderSettledPageEffect.ReboundBoundary(item.direction)
        is ReaderChapterWindowItem.Boundary -> ReaderSettledPageEffect.None
    }
