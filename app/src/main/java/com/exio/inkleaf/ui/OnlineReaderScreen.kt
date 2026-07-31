package com.exio.inkleaf.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun OnlineReaderScreen(
    pluginId: String,
    sourceId: String,
    chapterId: String,
    chapterRevision: String?,
    opaqueContextJson: String?,
    initialPageId: String? = null,
    initialPageIndex: Int? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToModelDownload: () -> Unit = {},
) {
    val viewModel: OnlineReaderViewModel = viewModel {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
        OnlineReaderViewModel(
            app = app,
            pluginId = pluginId,
            sourceId = sourceId,
            chapterId = chapterId,
            requestedRevision = chapterRevision,
            opaqueContextJson = opaqueContextJson,
            initialPageId = initialPageId,
            initialPageIndex = initialPageIndex,
        )
    }
    val features =
        ReaderPresentationFeatures(
            thumbnails = viewModel.thumbnails,
            bookmarkPages = viewModel.bookmarkPages.keys,
            bookmarks = viewModel.bookmarks,
            favoritePages = viewModel.favoritePages.keys,
        )
    val actions =
        ReaderPresentationActions(
            onNeedThumbnail = viewModel::requestThumbnail,
            onToggleBookmark = viewModel::toggleBookmark,
            onRemoveBookmark = viewModel::removeBookmark,
            onToggleFavorite = viewModel::toggleFavorite,
            onSetCover = null,
            onPageChanged = viewModel::saveProgress,
            onVolumeDisposed = viewModel::releaseInactiveVolume,
            onVolumeTaskStarted = viewModel::acquireVolumeTask,
            onVolumeTaskFinished = viewModel::releaseVolumeTask,
            isVolumeActive = viewModel::isActiveVolume,
            onNavigateToModelDownload = onNavigateToModelDownload,
            readerMessage = viewModel.readerMessage,
            onReaderMessageConsumed = viewModel::consumeReaderMessage,
        )

    SharedReaderScreen(
        state = viewModel.state,
        features = features,
        actions = actions,
        chapterNavigation =
            ReaderChapterNavigation(
                chapters = viewModel.readerChapters,
                currentChapterIndex = viewModel.currentChapterIndex,
                onSelectChapter = viewModel::selectChapter,
                onReachedLastPage = viewModel::preloadNextChapter,
                onReachedFirstPage = viewModel::preloadPreviousChapter,
                onBoundarySettled = viewModel::onBoundarySettled,
                onBoundaryIntent = viewModel::onBoundaryIntent,
                onGuardSettled = viewModel::onGuardSettled,
                onWindowPageSettled = viewModel::onWindowPageSettled,
                onPagerIdle = viewModel::onReaderPagerIdle,
            ),
        onExit = {
            viewModel.endReadingSession()
            onBack()
        },
        onErrorAction = { _ -> viewModel.reload() },
        errorBackLabel = "返回漫画",
        errorActionLabel = "重试",
        modifier = modifier,
    )
}
