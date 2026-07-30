package com.exio.inkleaf.ui

import com.exio.inkleaf.data.ComicVolume

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
    val onSelectChapter: (Int) -> Unit,
    val onForwardPastEnd: (() -> Unit)? = null,
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
    val onPageChanged: (Int) -> Unit,
    val onNavigateToModelDownload: () -> Unit,
    val readerMessage: String?,
    val onReaderMessageConsumed: () -> Unit,
)
