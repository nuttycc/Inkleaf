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
            if (isLeftZone) ReaderTransitionDirection.PREVIOUS else ReaderTransitionDirection.NEXT
        ReaderPageDirection.RIGHT_TO_LEFT ->
            if (isLeftZone) ReaderTransitionDirection.NEXT else ReaderTransitionDirection.PREVIOUS
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

    data class Error(val error: ContentLoadError) : ReaderPresentationState

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
    val thumbnailsByKey: Map<ReaderPageStateKey, androidx.compose.ui.graphics.ImageBitmap> =
        emptyMap(),
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

/** Where a bookmark points, independent of the chapter that is currently open. */
internal sealed interface ReaderBookmarkTarget {
    data class Local(val globalPage: Int) : ReaderBookmarkTarget

    data class Online(
        val chapterId: String,
        val chapterPageIndex: Int?,
        val pageId: String?,
    ) : ReaderBookmarkTarget
}

internal data class ReaderBookmarkItem(
    val key: String,
    val target: ReaderBookmarkTarget,
    /** Chapter order within the book; [Int.MAX_VALUE] when the chapter no longer exists. */
    val chapterOrder: Int,
    /** Page index within its chapter; null means the page number is not known yet. */
    val pageIndex: Int?,
    /** Book-global page index, available for local content only. */
    val globalPage: Int?,
    val chapterTitle: String,
    /** Source changed; confirm before opening an approximate location. */
    val stale: Boolean,
    /** False when the target chapter is missing or unavailable. */
    val available: Boolean,
    val addedAtMs: Long,
    /** Page that can be thumbnailed in the current context; null for cross-chapter entries. */
    val thumbnailPage: Int?,
)

/**
 * Book-wide ordering: chapter order, then chapter page, then insertion time. Entries whose chapter
 * is unavailable sort last, and unknown page numbers follow known ones within their chapter.
 */
internal fun sortedReaderBookmarks(bookmarks: List<ReaderBookmarkItem>): List<ReaderBookmarkItem> =
    bookmarks.sortedWith(
        compareBy<ReaderBookmarkItem>(
            { if (it.available) it.chapterOrder else Int.MAX_VALUE },
            { it.pageIndex ?: Int.MAX_VALUE },
            { it.addedAtMs },
            { it.key },
        )
    )

/** Page line for a bookmark row; null means there is no trustworthy page number to show. */
internal fun readerBookmarkPageLabel(bookmark: ReaderBookmarkItem): String? {
    val globalPage = bookmark.globalPage
    val pageIndex = bookmark.pageIndex
    return when {
        globalPage != null && pageIndex != null ->
            "全书第 ${globalPage + 1} 页 · 章节第 ${pageIndex + 1} 页"
        pageIndex != null -> "章节第 ${pageIndex + 1} 页"
        globalPage != null -> "全书第 ${globalPage + 1} 页"
        else -> null
    }
}

internal data class ReaderBookmarkUndo(val restore: suspend () -> Unit)

internal data class ReaderPresentationActions(
    val onNeedThumbnail: (Int) -> Unit,
    val onToggleBookmark: ((Int) -> Unit)?,
    val onRemoveBookmark: (suspend (ReaderBookmarkItem) -> ReaderBookmarkUndo)?,
    /** Handles bookmarks that cannot be resolved by the current pager (online cross-chapter). */
    val onBookmarkSelected: ((ReaderBookmarkItem) -> Unit)? = null,
    val onToggleFavorite: ((Int) -> Unit)?,
    val onSetCover: ((Int) -> Unit)?,
    val onSaveToGallery: ((Int) -> Unit)? = null,
    val onImport: (() -> Unit)? = null,
    val isImporting: Boolean = false,
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
