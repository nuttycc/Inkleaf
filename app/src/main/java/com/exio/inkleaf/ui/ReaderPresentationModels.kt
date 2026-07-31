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
        val chapterWindow: ReaderChapterWindow<ReaderWindowChapterContent>? = null,
    ) : ReaderPresentationState
}

internal data class ReaderWindowChapterContent(
    val volume: ComicVolume,
    val cacheKeyPrefix: String,
)

internal data class ReaderPresentationFeatures(
    val thumbnails: Map<Int, androidx.compose.ui.graphics.ImageBitmap> = emptyMap(),
    val bookmarkPages: Set<Int> = emptySet(),
    val bookmarks: List<ReaderBookmarkItem> = emptyList(),
    val favoritePages: Set<Int> = emptySet(),
    val thumbnailsByKey:
        Map<ReaderPageStateKey, androidx.compose.ui.graphics.ImageBitmap> = emptyMap(),
    val bookmarkPageKeys: Set<ReaderPageStateKey> = emptySet(),
    val favoritePageKeys: Set<ReaderPageStateKey> = emptySet(),
)

internal data class ReaderChapterNavigation(
    val chapters: List<ReaderChapterItem>?,
    val currentChapterIndex: Int,
    val onSelectChapter: (Int) -> Unit,
    val onReachedLastPage: (() -> Unit)? = null,
    val onReachedFirstPage: (() -> Unit)? = null,
    val onBoundarySettled: ((ReaderTransitionDirection) -> Unit)? = null,
    val onBoundaryIntent: ((ReaderTransitionDirection) -> Unit)? = null,
    val onGuardSettled: ((ReaderTransitionDirection) -> Unit)? = null,
    val onWindowPageSettled: ((ReaderChapterPageKey) -> Unit)? = null,
    val onPagerIdle: ((ReaderChapterWindowKey) -> Unit)? = null,
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
    val onVolumeTaskStarted: (ComicVolume) -> Boolean = { true },
    val onVolumeTaskFinished: (ComicVolume) -> Unit = {},
    val isVolumeActive: (ComicVolume) -> Boolean = { true },
    val onNavigateToModelDownload: () -> Unit,
    val readerMessage: String?,
    val onReaderMessageConsumed: () -> Unit,
)
