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
            onNavigateToModelDownload = onNavigateToModelDownload,
            readerMessage = viewModel.readerMessage,
            onReaderMessageConsumed = viewModel::consumeReaderMessage,
        )

    SharedReaderScreen(
        state = viewModel.state,
        features = features,
        actions = actions,
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
