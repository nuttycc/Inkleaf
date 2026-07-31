package com.exio.inkleaf.ui

import com.exio.inkleaf.data.ComicVolume

internal enum class ReaderTransitionDirection {
    PREVIOUS,
    NEXT,
}

internal sealed interface ReaderTransitionStatus {
    data object Loading : ReaderTransitionStatus

    data object Ready : ReaderTransitionStatus

    data object Error : ReaderTransitionStatus

    data object Boundary : ReaderTransitionStatus
}

internal data class ReaderChapterTransition(
    val direction: ReaderTransitionDirection,
    val chapterIndex: Int?,
    val chapterLabel: String,
    val title: String,
    val status: ReaderTransitionStatus,
)

internal sealed interface ReaderPagerItem {
    data class Page(val pageIndex: Int) : ReaderPagerItem

    data class Transition(val value: ReaderChapterTransition) : ReaderPagerItem
}

internal fun readerPagerItem(
    pagerIndex: Int,
    realPageCount: Int,
    transition: ReaderChapterTransition?,
): ReaderPagerItem {
    require(realPageCount > 0)
    require(pagerIndex in 0..realPageCount)
    if (transition == null) {
        // PagerState can retain the removed NEXT transition index for one composition frame.
        return ReaderPagerItem.Page(pagerIndex.coerceAtMost(realPageCount - 1))
    }
    if (transition.direction == ReaderTransitionDirection.PREVIOUS && pagerIndex == 0) {
        return ReaderPagerItem.Transition(transition)
    }
    if (transition.direction == ReaderTransitionDirection.NEXT && pagerIndex == realPageCount) {
        return ReaderPagerItem.Transition(transition)
    }
    val realPageIndex =
        pagerIndex - if (transition.direction == ReaderTransitionDirection.PREVIOUS) 1 else 0
    require(realPageIndex in 0 until realPageCount)
    return ReaderPagerItem.Page(realPageIndex)
}

internal fun shouldReturnFromReaderTransition(
    transitionPageWasEntered: Boolean,
    isTransitionPage: Boolean,
): Boolean = transitionPageWasEntered && !isTransitionPage

internal fun readerPagerUserScrollEnabled(
    isTransitionPage: Boolean,
    isZoomed: Boolean,
    isOcrSelectionActive: Boolean,
): Boolean = isTransitionPage || (!isZoomed && !isOcrSelectionActive)

internal sealed interface ReaderPresentationState {
    data object Loading : ReaderPresentationState

    data class Error(val message: String) : ReaderPresentationState

    data class Ready(
        val volume: ComicVolume,
        val startPage: Int,
        val title: String,
        val cacheKeyPrefix: String,
    ) : ReaderPresentationState
}

internal data class ReaderPresentationFeatures(
    val thumbnails: Map<Int, androidx.compose.ui.graphics.ImageBitmap> = emptyMap(),
    val bookmarkPages: Set<Int> = emptySet(),
    val bookmarks: List<ReaderBookmarkItem> = emptyList(),
    val favoritePages: Set<Int> = emptySet(),
)

internal data class ReaderChapterNavigation(
    val chapters: List<ReaderChapterItem>?,
    val currentChapterIndex: Int,
    val transition: ReaderChapterTransition? = null,
    val onSelectChapter: (Int) -> Unit,
    val onForwardPastEnd: (() -> Unit)? = null,
    // 越界（向后）：在首页继续向后滑动或点击左区，进入上一章
    val onBackwardPastStart: (() -> Unit)? = null,
    // 到达末页：触发下一章预加载，使越界切换无网络等待
    val onReachedLastPage: (() -> Unit)? = null,
    // 到达首页：触发上一章预加载
    val onReachedFirstPage: (() -> Unit)? = null,
    val onTransitionEntered: (() -> Unit)? = null,
    val onTransitionForward: (() -> Unit)? = null,
    val onTransitionBackward: (() -> Unit)? = null,
    val onTransitionReturn: ((ReaderTransitionDirection) -> Unit)? = null,
    val onRetryTransition: (() -> Unit)? = null,
)

internal data class ReaderBookmarkItem(
    val key: String,
    val globalPage: Int,
    val chapterIndex: Int,
    val pageIndex: Int,
    val chapterTitle: String,
    val stale: Boolean,
)

internal data class ReaderBookmarkUndo(val restore: suspend () -> Unit)

internal data class ReaderPresentationActions(
    val onNeedThumbnail: (Int) -> Unit,
    val onToggleBookmark: ((Int) -> Unit)?,
    val onRemoveBookmark: (suspend (ReaderBookmarkItem) -> ReaderBookmarkUndo)?,
    val onToggleFavorite: ((Int) -> Unit)?,
    val onSetCover: ((Int) -> Unit)?,
    val onPageChanged: (ComicVolume, Int) -> Unit,
    val onVolumeDisposed: (ComicVolume) -> Unit = {},
    val isVolumeActive: (ComicVolume) -> Boolean = { true },
    val onNavigateToModelDownload: () -> Unit,
    val readerMessage: String?,
    val onReaderMessageConsumed: () -> Unit,
)
