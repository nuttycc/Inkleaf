package com.exio.inkleaf.ui

import androidx.compose.ui.Alignment
import com.exio.inkleaf.data.ComicVolume
import com.exio.inkleaf.data.ReaderPageDirection
import com.exio.inkleaf.data.ReaderPageStatusColor
import com.exio.inkleaf.data.ReaderPageStatusPosition
import com.exio.inkleaf.data.ReaderSettings
import com.exio.inkleaf.data.ReaderStageBackground

internal enum class ReaderTransitionDirection {
    PREVIOUS,
    NEXT,
}

internal fun readerPageTurnDelta(direction: ReaderTransitionDirection): Int =
    when (direction) {
        ReaderTransitionDirection.PREVIOUS -> -1
        ReaderTransitionDirection.NEXT -> 1
    }

internal fun readerTapTurnDirection(
    pageDirection: ReaderPageDirection,
    isLeftZone: Boolean,
): ReaderTransitionDirection =
    when (pageDirection) {
        ReaderPageDirection.LEFT_TO_RIGHT ->
            if (isLeftZone) ReaderTransitionDirection.PREVIOUS
            else ReaderTransitionDirection.NEXT
        ReaderPageDirection.RIGHT_TO_LEFT ->
            if (isLeftZone) ReaderTransitionDirection.NEXT
            else ReaderTransitionDirection.PREVIOUS
    }

internal fun readerPagerReverseLayout(pageDirection: ReaderPageDirection): Boolean =
    pageDirection == ReaderPageDirection.RIGHT_TO_LEFT

internal fun readerPageStatusAlignment(position: ReaderPageStatusPosition): Alignment =
    when (position) {
        ReaderPageStatusPosition.START -> Alignment.BottomStart
        ReaderPageStatusPosition.CENTER -> Alignment.BottomCenter
        ReaderPageStatusPosition.END -> Alignment.BottomEnd
    }

internal enum class ReaderPageStatusTone {
    LIGHT_CONTENT,
    DARK_CONTENT,
}

internal fun readerPageStatusTone(settings: ReaderSettings): ReaderPageStatusTone =
    when (settings.pageStatusColor) {
        ReaderPageStatusColor.WHITE -> ReaderPageStatusTone.LIGHT_CONTENT
        ReaderPageStatusColor.BLACK -> ReaderPageStatusTone.DARK_CONTENT
        ReaderPageStatusColor.AUTO ->
            when (settings.stageBackground) {
                ReaderStageBackground.BEIGE -> ReaderPageStatusTone.DARK_CONTENT
                ReaderStageBackground.BLACK,
                ReaderStageBackground.DARK_GRAY -> ReaderPageStatusTone.LIGHT_CONTENT
            }
    }

internal fun readerIsCurrentPageZoomed(
    currentPageStateKey: ReaderPageStateKey?,
    zoomedPage: ReaderPageStateKey?,
): Boolean = currentPageStateKey != null && zoomedPage == currentPageStateKey

internal sealed interface ReaderTransitionStatus {
    data object Loading : ReaderTransitionStatus

    data object Ready : ReaderTransitionStatus

    data object Error : ReaderTransitionStatus

    data object Boundary : ReaderTransitionStatus
}

internal data class ReaderChapterTransition(
    val direction: ReaderTransitionDirection,
    val chapterIndex: Int?,
    val title: String,
    val status: ReaderTransitionStatus,
)

internal enum class ReaderBottomControlsMode {
    HIDDEN,
    PAGE,
    TRANSITION,
}

internal fun readerBottomControlsMode(
    showControls: Boolean,
    isTransitionPage: Boolean,
    isCurrentVolumeActive: Boolean,
): ReaderBottomControlsMode =
    when {
        !showControls -> ReaderBottomControlsMode.HIDDEN
        isTransitionPage -> ReaderBottomControlsMode.TRANSITION
        isCurrentVolumeActive -> ReaderBottomControlsMode.PAGE
        else -> ReaderBottomControlsMode.HIDDEN
    }

internal fun readerTransitionHeading(transition: ReaderChapterTransition): String =
    when {
        transition.status == ReaderTransitionStatus.Boundary &&
            transition.direction == ReaderTransitionDirection.NEXT -> "没有下一章"
        transition.status == ReaderTransitionStatus.Boundary -> "没有上一章"
        transition.direction == ReaderTransitionDirection.NEXT -> "下一章"
        else -> "上一章"
    }

internal fun readerPagerUserScrollEnabled(
    isTransitionPage: Boolean,
    isZoomed: Boolean,
): Boolean = isTransitionPage || !isZoomed

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
    val onBoundaryRetry: ((ReaderTransitionDirection) -> Unit)? = null,
    val onWindowPageSettled: ((ReaderChapterPageKey) -> Unit)? = null,
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
    val onSaveToGallery: ((Int) -> Unit)? = null,
    val onPageChanged: (ComicVolume, Int) -> Unit,
    val onPageDirectionChanged: (ReaderPageDirection) -> Unit,
    val onStageBackgroundChanged: (ReaderStageBackground) -> Unit,
    val onPageStatusPositionChanged: (ReaderPageStatusPosition) -> Unit,
    val onPageStatusColorChanged: (ReaderPageStatusColor) -> Unit,
    val onVolumeDisposed: (ComicVolume) -> Unit = {},
    val onVolumeTaskStarted: (ComicVolume) -> Boolean = { true },
    val onVolumeTaskFinished: (ComicVolume) -> Unit = {},
    val isVolumeActive: (ComicVolume) -> Boolean = { true },
    val readerMessage: String?,
    val onReaderMessageConsumed: () -> Unit,
)
