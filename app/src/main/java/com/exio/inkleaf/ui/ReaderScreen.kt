package com.exio.inkleaf.ui

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.composables.icons.materialsymbols.outlined.R as MaterialSymbolsOutlinedR
import com.exio.inkleaf.R
import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.ComicVolume
import com.exio.inkleaf.data.PageRenderRequest
import com.exio.inkleaf.data.ReaderPageCacheKey
import com.exio.inkleaf.data.ReaderPageDirection
import com.exio.inkleaf.data.ReaderSettings
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class ReaderPageTarget(
    val key: ReaderPageStateKey,
    val volume: ComicVolume,
    val page: Int,
)

private enum class ReaderStatePhase {
    LOADING,
    ERROR,
    READY,
}

private fun ReaderPresentationState.phase(): ReaderStatePhase =
    when (this) {
        ReaderPresentationState.Loading -> ReaderStatePhase.LOADING
        is ReaderPresentationState.Error -> ReaderStatePhase.ERROR
        is ReaderPresentationState.Ready -> ReaderStatePhase.READY
    }

@Composable
fun ReaderScreen(
    comicId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialPage: Int? = null,
    resumeFromPersistedPosition: Boolean = false,
) {
    val viewModel: ReaderViewModel = viewModel {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
        ReaderViewModel(
            app = app,
            comicId = comicId,
            initialPageOverride = initialPage,
            resumeFromPersistedPosition = resumeFromPersistedPosition,
        )
    }
    val settingsViewModel: ReaderSettingsViewModel = viewModel()
    val readerSettings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val bookmarksByKey =
        viewModel.resolvedBookmarks.associate { resolved ->
            "local:${resolved.bookmark.id}" to resolved.bookmark
        }
    val presentationState =
        when (val state = viewModel.state) {
            ReaderUiState.Loading -> ReaderPresentationState.Loading
            is ReaderUiState.Error -> ReaderPresentationState.Error(state.message)
            is ReaderUiState.Ready ->
                ReaderPresentationState.Ready(
                    volume = state.volume,
                    startPage = state.startPage,
                    title = state.title,
                    cacheKeyPrefix = "comic-$comicId",
                )
        }
    val features =
        ReaderPresentationFeatures(
            thumbnails = viewModel.thumbnails,
            bookmarkPages = viewModel.bookmarkPages.keys.toSet(),
            bookmarks =
                viewModel.resolvedBookmarks.map { resolved ->
                    val bookmark = resolved.bookmark
                    ReaderBookmarkItem(
                        key = "local:${bookmark.id}",
                        globalPage = resolved.globalPage,
                        chapterIndex = bookmark.chapterIndex,
                        pageIndex = bookmark.pageIndex,
                        chapterTitle = bookmark.chapterTitle,
                        stale = resolved.stale,
                    )
                },
            favoritePages = viewModel.favoritePages.keys.toSet(),
        )
    val actions =
        ReaderPresentationActions(
            onNeedThumbnail = viewModel::requestThumbnail,
            onToggleBookmark = viewModel::toggleBookmark,
            onRemoveBookmark = { item ->
                val bookmark = requireNotNull(bookmarksByKey[item.key]) { "Unknown bookmark" }
                viewModel.removeBookmark(bookmark)
                ReaderBookmarkUndo { viewModel.restoreBookmark(bookmark) }
            },
            onToggleFavorite = viewModel::toggleFavorite,
            onSetCover = viewModel::setCurrentPageAsCover,
            onSaveToGallery = viewModel::saveCurrentPageToGallery,
            onPageChanged = { _, page -> viewModel.saveProgress(page) },
            onPageDirectionChanged = settingsViewModel::setPageDirection,
            onStageBackgroundChanged = settingsViewModel::setStageBackground,
            onPageStatusPositionChanged = settingsViewModel::setPageStatusPosition,
            onPageStatusColorChanged = settingsViewModel::setPageStatusColor,
            readerMessage = viewModel.readerMessage,
            onReaderMessageConsumed = viewModel::consumeReaderMessage,
        )

    SharedReaderScreen(
        state = presentationState,
        features = features,
        actions = actions,
        settings = readerSettings,
        onExit = {
            viewModel.endReadingSession()
            onBack()
        },
        onErrorAction = { onDone -> viewModel.removeFromShelf(onDone) },
        errorBackLabel = "返回书架",
        errorActionLabel = "从书架移除",
        modifier = modifier,
    )
}

@Composable
internal fun SharedReaderScreen(
    state: ReaderPresentationState,
    features: ReaderPresentationFeatures,
    actions: ReaderPresentationActions,
    settings: ReaderSettings,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    chapterNavigation: ReaderChapterNavigation? = null,
    onErrorAction: ((() -> Unit) -> Unit)? = null,
    errorBackLabel: String = "返回",
    errorActionLabel: String? = null,
) {
    var showControls by remember { mutableStateOf(false) }
    val stagePalette = readerStagePalette(settings.stageBackground)
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    val exitReader = {
        if (window != null) {
            WindowCompat.getInsetsController(window, view)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        onExit()
    }

    BackHandler(onBack = exitReader)
    if (window != null) {
        DisposableEffect(showControls) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (showControls) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {}
        }
        DisposableEffect(Unit) {
            onDispose {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val readerContent: @Composable (ReaderPresentationState) -> Unit = { current ->
        when (current) {
            ReaderPresentationState.Loading ->
                LoadingView(
                    contentColor = stagePalette.content,
                    modifier = Modifier.fillMaxSize(),
                )
            is ReaderPresentationState.Error ->
                ErrorView(
                    message = current.message,
                    onBack = exitReader,
                    backLabel = errorBackLabel,
                    onRemove =
                        onErrorAction?.let { action ->
                            { action(exitReader) }
                        },
                    removeLabel = errorActionLabel,
                    backgroundColor = stagePalette.background,
                    contentColor = stagePalette.content,
                    modifier = Modifier.fillMaxSize(),
                )

            is ReaderPresentationState.Ready ->
                ComicPager(
                    volume = current.volume,
                    startPage = current.startPage,
                    title = current.title,
                    cacheKeyPrefix = current.cacheKeyPrefix,
                    chapterWindow = current.chapterWindow,
                    features = features,
                    actions = actions,
                    settings = settings,
                    stagePalette = stagePalette,
                    chapterNavigation = chapterNavigation,
                    onBack = exitReader,
                    showControls = showControls,
                    onToggleControls = { showControls = !showControls },
                    modifier = Modifier.fillMaxSize(),
                )
        }
    }
    Box(modifier = modifier.fillMaxSize().background(stagePalette.background)) {
        if (chapterNavigation == null) {
            Crossfade(targetState = state, label = "reader-state", content = readerContent)
        } else {
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                contentKey = ReaderPresentationState::phase,
                label = "online-reader-state",
            ) { current ->
                readerContent(current)
            }
        }
    }
}

@Composable
private fun ComicPager(
    volume: ComicVolume,
    startPage: Int,
    title: String,
    cacheKeyPrefix: String,
    chapterWindow: ReaderChapterWindow<ReaderWindowChapterContent>? = null,
    features: ReaderPresentationFeatures,
    actions: ReaderPresentationActions,
    settings: ReaderSettings,
    stagePalette: ReaderStagePalette,
    chapterNavigation: ReaderChapterNavigation?,
    onBack: () -> Unit,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var visibleChapterWindow by remember { mutableStateOf(chapterWindow) }
    val pagerChapterWindow = visibleChapterWindow
    val retainedVolumes =
        remember(pagerChapterWindow, volume) {
            pagerChapterWindow?.items
                ?.mapNotNull { it as? ReaderChapterWindowItem.Page<ReaderWindowChapterContent> }
                ?.map { it.chapter.payload.volume }
                ?.distinct()
                ?: listOf(volume)
        }
    DisposableEffect(retainedVolumes) {
        onDispose { retainedVolumes.forEach(actions.onVolumeDisposed) }
    }
    val context = LocalContext.current
    val initialPagerPage =
        remember(pagerChapterWindow, startPage) {
            readerWindowIndexForChapterPage(
                window = pagerChapterWindow,
                chapterId = pagerChapterWindow?.activeChapterId,
                pageIndex = startPage,
            ).takeIf { it >= 0 } ?: startPage
        }
    val pagerState =
        rememberPagerState(
            initialPage = initialPagerPage,
            pageCount = {
                pagerChapterWindow?.items?.size ?: volume.totalPageCount
            },
        )
    // A gesture must use one immutable item set so it cannot cross a newly revealed boundary.
    LaunchedEffect(chapterWindow, pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .first(::canAdoptReaderChapterWindow)
        val oldWindow = visibleChapterWindow
        val nextWindow = chapterWindow
        val adoption =
            if (oldWindow != null && nextWindow != null) {
                readerChapterWindowAdoption(
                    currentWindow = oldWindow,
                    currentIndex = pagerState.settledPage,
                    nextWindow = nextWindow,
                    startPage = startPage,
                )
            } else {
                null
            }
        visibleChapterWindow = nextWindow
        if (adoption?.requiresExplicitScroll == true) {
            pagerState.requestScrollToPage(adoption.targetIndex)
        }
    }
    val scope = rememberCoroutineScope()
    val currentWindowItem = pagerChapterWindow?.items?.getOrNull(pagerState.currentPage)
    val currentWindowPage =
        currentWindowItem as? ReaderChapterWindowItem.Page<ReaderWindowChapterContent>
    val currentContextPage = pagerChapterWindow?.contextPageAt(pagerState.currentPage)
    val currentRealPage = currentContextPage?.pageIndex ?: pagerState.currentPage
    val currentVolume = currentContextPage?.chapter?.payload?.volume ?: volume
    val isCurrentVolumeActive = actions.isVolumeActive(currentVolume)
    val currentTransition =
        (currentWindowItem as? ReaderChapterWindowItem.Boundary)?.transition
    val isTransitionPage = currentTransition != null
    val bottomControlsMode =
        readerBottomControlsMode(
            showControls = showControls,
            isTransitionPage = isTransitionPage,
            isCurrentVolumeActive = isCurrentVolumeActive,
        )
    val currentPageTarget =
        currentWindowPage?.let { page ->
            ReaderPageTarget(
                key = page.pageKey.toReaderPageStateKey(),
                volume = page.chapter.payload.volume,
                page = page.pageIndex,
            )
        } ?: if (!isTransitionPage && currentRealPage in 0 until volume.totalPageCount) {
            ReaderPageTarget(
                key =
                    ReaderPageStateKey(
                        cacheKeyPrefix,
                        volume.pageIdentity(currentRealPage) ?: "index:$currentRealPage",
                    ),
                volume = volume,
                page = currentRealPage,
            )
        } else {
            null
        }
    val currentPageStateKey = currentPageTarget?.key

    var zoomedPage by remember { mutableStateOf<ReaderPageStateKey?>(null) }
    var zoomToggleRequest by remember { mutableIntStateOf(0) }
    var zoomResetRequest by remember { mutableIntStateOf(0) }
    var zoomTogglePage by remember { mutableStateOf<ReaderPageStateKey?>(null) }
    var zoomResetPage by remember { mutableStateOf<ReaderPageStateKey?>(null) }
    var zoomToggleAnchor by remember { mutableStateOf(Offset.Unspecified) }
    val isCurrentPageZoomed =
        readerIsCurrentPageZoomed(
            currentPageStateKey = currentPageStateKey,
            zoomedPage = zoomedPage,
        )
    var activePanel by remember { mutableStateOf<ReaderPanel?>(null) }
    var chapterLayoutVersion by remember(currentVolume) { mutableIntStateOf(0) }
    val volumeChapters by
        produceState<List<ReaderChapterItem>?>(
            initialValue = null,
            key1 = currentVolume,
        ) {
            if (shouldShowChapterMenu(currentVolume.chapterCount)) {
                value = loadReaderChapterItems(currentVolume)
            }
        }
    LaunchedEffect(volumeChapters) {
        if (volumeChapters != null) chapterLayoutVersion++
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val bookmarkRemovalsInFlight = remember { mutableStateSetOf<String>() }
    var bottomControlsHeightPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(showControls) {
        if (!showControls) activePanel = null
    }
    LaunchedEffect(currentPageStateKey) {
        zoomedPage = null
        activePanel = null
    }

    fun removeBookmark(bookmark: ReaderBookmarkItem) {
        if (!actions.isVolumeActive(currentVolume)) return
        val remove = actions.onRemoveBookmark ?: return
        if (!bookmarkRemovalsInFlight.add(bookmark.key)) return
        scope.launch {
            try {
                val undo = remove(bookmark)
                val result =
                    snackbarHostState.showSnackbar(
                        message = "已移除书签",
                        actionLabel = "撤销",
                    )
                if (result == SnackbarResult.ActionPerformed) {
                    try {
                        undo.restore()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        snackbarHostState.showSnackbar("恢复书签失败")
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                snackbarHostState.showSnackbar("移除书签失败")
            } finally {
                bookmarkRemovalsInFlight.remove(bookmark.key)
            }
        }
    }

    BackHandler(enabled = activePanel != null) {
        activePanel = null
    }

    // 当前页对应的章节信息，用于多章书籍的界面提示
    val currentPage = currentRealPage
    val chapterProgress =
        remember(currentPage, currentVolume, chapterLayoutVersion) {
            currentVolume.globalToChapterPage(currentPage)
        }
    val chapterTitle =
        remember(chapterProgress, currentVolume, chapterLayoutVersion) {
            currentVolume.chapterTitle(chapterProgress.chapterIndex)
        }
    val readerChapters =
        if (chapterNavigation != null) chapterNavigation.chapters else volumeChapters
    val readerChapterCount =
        if (chapterNavigation != null) {
            chapterNavigation.chapters?.size ?: (chapterNavigation.currentChapterIndex + 1)
        } else {
            currentVolume.chapterCount
        }
    val currentReaderChapterIndex =
        currentWindowPage?.chapter?.chapterIndex
            ?: chapterNavigation?.currentChapterIndex
            ?: chapterProgress.chapterIndex
    val currentReaderChapterTitle =
        readerChapters?.getOrNull(currentReaderChapterIndex)?.title ?: chapterTitle

    // 点击和未来按键都通过同一个逻辑方向入口；Pager 滑动仍由原生 Pager 驱动。
    val turnPage: (ReaderTransitionDirection) -> Unit = { direction ->
        val delta = readerPageTurnDelta(direction)
        if (pagerChapterWindow != null) {
            when (
                val result =
                    readerPageTurnResult(
                        pagerChapterWindow.items,
                        pagerState.currentPage,
                        delta,
                    )
            ) {
                is ReaderPageTurnResult.MoveTo ->
                    scope.launch { pagerState.animateScrollToPage(result.index) }
                ReaderPageTurnResult.NoChange -> Unit
            }
        } else if (actions.isVolumeActive(currentVolume)) {
            val requested = pagerState.currentPage + delta
            val target = requested.coerceIn(0, pagerState.pageCount - 1)
            if (target != pagerState.currentPage) {
                scope.launch { pagerState.animateScrollToPage(target) }
            }
        }
    }
    val windowIndexForCurrentChapterPage: (Int) -> Int = { selectedPage ->
        readerWindowIndexForChapterPage(
            window = pagerChapterWindow,
            chapterId = currentWindowPage?.chapter?.chapterId,
            pageIndex = selectedPage,
        )
    }

    val latestVisibleChapterWindow by rememberUpdatedState(visibleChapterWindow)
    val latestChapterNavigation by rememberUpdatedState(chapterNavigation)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }.collect {
            (settledPage, inProgress) ->
            if (inProgress) return@collect
            val window = latestVisibleChapterWindow ?: return@collect
            val item = window.items.getOrNull(settledPage) ?: return@collect
            when (item) {
                is ReaderChapterWindowItem.Page<*> -> {
                    val content = item.chapter.payload as ReaderWindowChapterContent
                    latestChapterNavigation?.onWindowPageSettled?.invoke(item.pageKey)
                    if (actions.isVolumeActive(content.volume)) {
                        if (item.pageIndex == 0) {
                            latestChapterNavigation?.onReachedFirstPage?.invoke()
                        }
                        if (item.pageIndex == item.chapter.pageIdentities.lastIndex) {
                            latestChapterNavigation?.onReachedLastPage?.invoke()
                        }
                    }
                }
                is ReaderChapterWindowItem.Boundary -> {
                    latestChapterNavigation?.onBoundarySettled?.invoke(item.transition.direction)
                }
            }
        }
    }

    // Single-volume readers still report their chapter-local page index directly.
    LaunchedEffect(pagerState, volume, pagerChapterWindow) {
        if (pagerChapterWindow != null) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }.collect { page ->
            actions.onPageChanged(volume, page)
        }
    }

    val bottomControlsHeight = with(LocalDensity.current) { bottomControlsHeightPx.toDp() }

    SnackbarMessageEffect(
        message = actions.readerMessage,
        hostState = snackbarHostState,
        onConsumed = actions.onReaderMessageConsumed,
    )

    Box(
        modifier =
            modifier
                .fillMaxSize()
                // 1x 时 Pager 接管单指拖动；放大后由当前页接管平移。
                // 点按检测在移动超过阈值时自动作废，工具栏上的按钮/滑杆会消费
                // 自己的事件，也不会误触发这里。
                .pointerInput(
                    pagerState.currentPage,
                    pagerChapterWindow,
                    zoomedPage,
                    settings.pageDirection,
                ) {
                    detectTapGestures(
                        onDoubleTap = { anchor ->
                            zoomToggleAnchor = anchor
                            zoomTogglePage = currentPageStateKey
                            zoomToggleRequest++
                        },
                        onTap = { offset ->
                            val third = size.width / 3f
                            when {
                                isCurrentPageZoomed && offset.x !in third..(third * 2) -> Unit
                                offset.x < third ->
                                    turnPage(
                                        readerTapTurnDirection(
                                            pageDirection = settings.pageDirection,
                                            isLeftZone = true,
                                        )
                                    )
                                offset.x > third * 2 ->
                                    turnPage(
                                        readerTapTurnDirection(
                                            pageDirection = settings.pageDirection,
                                            isLeftZone = false,
                                        )
                                    )
                                else -> onToggleControls()
                            }
                        },
                    )
                }
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            reverseLayout = readerPagerReverseLayout(settings.pageDirection),
            userScrollEnabled =
                readerPagerUserScrollEnabled(
                    isTransitionPage = isTransitionPage,
                    isZoomed = isCurrentPageZoomed,
                ),
            key = { page ->
                pagerChapterWindow?.items?.getOrNull(page)?.saveablePagerKey() ?: "single:$page"
            },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            if (pagerChapterWindow != null) {
                when (val item = pagerChapterWindow.items[page]) {
                    is ReaderChapterWindowItem.Page<*> -> {
                        val content = item.chapter.payload as ReaderWindowChapterContent
                        val isActiveVolume = actions.isVolumeActive(content.volume)
                        val pageStateKey = item.pageKey.toReaderPageStateKey()
                        ComicPage(
                            volume = content.volume,
                            page = item.pageIndex,
                            contentColor = stagePalette.content,
                            pageStateKey = pageStateKey,
                            currentPageStateKey = currentPageStateKey,
                            cacheKeyPrefix = content.cacheKeyPrefix,
                            thumbnail =
                                features.thumbnailsByKey[pageStateKey]
                                    ?: features.thumbnails[item.pageIndex].takeIf { isActiveVolume },
                            zoomToggleRequest = zoomToggleRequest,
                            zoomResetRequest = zoomResetRequest,
                            zoomTogglePage = zoomTogglePage,
                            zoomResetPage = zoomResetPage,
                            zoomToggleAnchor = zoomToggleAnchor,
                            onZoomChanged = { isZoomed ->
                                if (page == pagerState.currentPage) {
                                    zoomedPage = if (isZoomed) pageStateKey else null
                                }
                            },
                            onVolumeTaskStarted = actions.onVolumeTaskStarted,
                            onVolumeTaskFinished = actions.onVolumeTaskFinished,
                        )
                    }
                    is ReaderChapterWindowItem.Boundary ->
                        ReaderChapterTransitionPage(
                            transition = item.transition,
                            backgroundColor = stagePalette.background,
                            contentColor = stagePalette.content,
                            onRetry = {
                                chapterNavigation?.onBoundaryRetry?.invoke(
                                    item.transition.direction
                                )
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                }
            } else {
                val pageStateKey =
                    ReaderPageStateKey(
                        cacheKeyPrefix,
                        volume.pageIdentity(page) ?: "index:$page",
                    )
                ComicPage(
                    volume = volume,
                    page = page,
                    contentColor = stagePalette.content,
                    pageStateKey = pageStateKey,
                    currentPageStateKey = currentPageStateKey,
                    cacheKeyPrefix = cacheKeyPrefix,
                    thumbnail = features.thumbnails[page],
                    zoomToggleRequest = zoomToggleRequest,
                    zoomResetRequest = zoomResetRequest,
                    zoomTogglePage = zoomTogglePage,
                    zoomResetPage = zoomResetPage,
                    zoomToggleAnchor = zoomToggleAnchor,
                    onZoomChanged = { isZoomed ->
                        if (page == pagerState.currentPage) {
                            zoomedPage = if (isZoomed) pageStateKey else null
                        }
                    },
                    onVolumeTaskStarted = actions.onVolumeTaskStarted,
                    onVolumeTaskFinished = actions.onVolumeTaskFinished,
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(
                        bottom =
                            if (bottomControlsHeightPx > 0) bottomControlsHeight + 12.dp else 16.dp
                    ),
        )

        if (
            !showControls &&
                !isTransitionPage &&
                snackbarHostState.currentSnackbarData == null
        ) {
            val pageCountLabel = "${currentRealPage + 1} / ${currentVolume.totalPageCount}"
            val pageLabel =
                if (readerChapterCount > 1) {
                    "$currentReaderChapterTitle · $pageCountLabel"
                } else {
                    pageCountLabel
                }
            val pageStatusTone = readerPageStatusTone(settings)
            ReaderPageStatus(
                pageLabel = pageLabel,
                contentColor = readerPageStatusContentColor(pageStatusTone),
                containerColor = readerPageStatusContainerColor(pageStatusTone),
                modifier =
                    Modifier.align(readerPageStatusAlignment(settings.pageStatusPosition))
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }

        ReaderTopBar(
            visible = showControls,
            title = title,
            isBookmarked =
                !isTransitionPage &&
                    currentPageStateKey != null &&
                    (currentPageStateKey in features.bookmarkPageKeys ||
                        (pagerChapterWindow == null && currentRealPage in features.bookmarkPages)),
            isZoomed = !isTransitionPage && isCurrentPageZoomed,
            onBack = onBack,
            onToggleBookmark =
                if (isTransitionPage) {
                    null
                } else {
                    actions.onToggleBookmark?.let { toggle ->
                        {
                            if (actions.isVolumeActive(currentVolume)) toggle(currentRealPage)
                        }
                    }
                },
            onResetZoom = {
                zoomResetPage = currentPageStateKey
                zoomResetRequest++
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        ReaderBottomControls(
            visible = bottomControlsMode != ReaderBottomControlsMode.HIDDEN,
            transition = currentTransition,
            onTransitionRetry =
                currentTransition?.let { transition ->
                    {
                        chapterNavigation?.onBoundaryRetry?.invoke(transition.direction)
                    }
                },
            currentPage = currentRealPage,
            pageCount = currentVolume.totalPageCount,
            pageDirection = settings.pageDirection,
            onPageSelected = { selectedPage ->
                val target = windowIndexForCurrentChapterPage(selectedPage)
                if (target >= 0) scope.launch { pagerState.scrollToPage(target) }
            },
            chapterCount = readerChapterCount,
            thumbnails = features.thumbnails,
            bookmarkPages = features.bookmarkPages,
            onNeedThumbnail = { page ->
                if (actions.isVolumeActive(currentVolume)) actions.onNeedThumbnail(page)
            },
            onPagesSelected = { activePanel = null },
            onHeightChanged = { bottomControlsHeightPx = it },
            activePanel = activePanel,
            onPanelSelected = { panel ->
                activePanel = if (activePanel == panel) null else panel
            },
            attachedContent = { panel ->
                when (panel) {
                    ReaderPanel.Chapters ->
                        ReaderChaptersPanelContent(
                            chapters = readerChapters,
                            currentChapterIndex = currentReaderChapterIndex,
                            onSelect = { chapterIndex ->
                                activePanel = null
                                if (actions.isVolumeActive(currentVolume)) {
                                    val externalNavigation = chapterNavigation
                                    if (externalNavigation != null) {
                                        externalNavigation.onSelectChapter(chapterIndex)
                                    } else {
                                        scope.launch {
                                            pagerState.scrollToPage(
                                                currentVolume.chapterPageToGlobal(chapterIndex, 0)
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    ReaderPanel.Bookmarks ->
                        ReaderBookmarksPanelContent(
                            bookmarks = features.bookmarks,
                            thumbnails = features.thumbnails,
                            onNeedThumbnail = { page ->
                                if (actions.isVolumeActive(currentVolume)) actions.onNeedThumbnail(page)
                            },
                            onSelect = { page ->
                                activePanel = null
                                val target = windowIndexForCurrentChapterPage(page)
                                if (target >= 0) scope.launch { pagerState.scrollToPage(target) }
                            },
                            removalsInFlight = bookmarkRemovalsInFlight,
                            onRemove =
                                if (actions.onRemoveBookmark == null) null else ::removeBookmark,
                        )
                    ReaderPanel.Tools ->
                        ReaderToolsPanelContent(
                            isFavorite =
                                !isTransitionPage &&
                                    currentPageStateKey != null &&
                                    (currentPageStateKey in features.favoritePageKeys ||
                                        (pagerChapterWindow == null &&
                                            currentRealPage in features.favoritePages)),
                            onToggleFavorite =
                                actions.onToggleFavorite?.let { toggleFavorite ->
                                    {
                                        activePanel = null
                                        if (actions.isVolumeActive(currentVolume)) {
                                            toggleFavorite(currentRealPage)
                                        }
                                    }
                                },
                            onSetCover =
                                actions.onSetCover?.let { setCover ->
                                    {
                                        activePanel = null
                                        if (actions.isVolumeActive(currentVolume)) {
                                            setCover(currentRealPage)
                                        }
                                    }
                                },
                            onSaveToGallery =
                                actions.onSaveToGallery?.let { saveToGallery ->
                                    {
                                        activePanel = null
                                        if (actions.isVolumeActive(currentVolume)) {
                                            saveToGallery(currentRealPage)
                                        }
                                    }
                                },
                        )
                    ReaderPanel.Settings ->
                        ReaderSettingsPanelContent(
                            settings = settings,
                            onPageDirectionChanged = actions.onPageDirectionChanged,
                            onStageBackgroundChanged = actions.onStageBackgroundChanged,
                            onPageStatusPositionChanged = actions.onPageStatusPositionChanged,
                            onPageStatusColorChanged = actions.onPageStatusColorChanged,
                        )
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ReaderChapterTransitionPage(
    transition: ReaderChapterTransition,
    backgroundColor: Color,
    contentColor: Color,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val directionLabel =
        if (transition.direction == ReaderTransitionDirection.NEXT) "下一章" else "上一章"
    Column(
        modifier = modifier.background(backgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = readerTransitionHeading(transition),
            color = contentColor,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        if (transition.title.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = transition.title,
                color = contentColor,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        when (transition.status) {
            ReaderTransitionStatus.Loading -> {
                CircularProgressIndicator(color = contentColor)
                Spacer(modifier = Modifier.height(12.dp))
                Text("正在加载$directionLabel", color = contentColor)
            }
            ReaderTransitionStatus.Ready -> Text("继续翻页进入", color = contentColor)
            ReaderTransitionStatus.Error -> {
                Text("${directionLabel}加载失败", color = contentColor)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { onRetry?.invoke() },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = contentColor,
                            contentColor = backgroundColor,
                        ),
                ) {
                    Text("重试")
                }
            }
            ReaderTransitionStatus.Boundary -> Text("已经到达内容边界", color = contentColor)
        }
    }
}

/** 顶部工具栏：返回 + 书名。页码信息只在底栏出现，避免重复 */
@Composable
private fun ReaderTopBar(
    visible: Boolean,
    title: String,
    isBookmarked: Boolean,
    isZoomed: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: (() -> Unit)?,
    onResetZoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val accent = readerAccentColor()
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回书架",
                    tint = Color.White,
                )
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            if (isZoomed) {
                TextButton(onClick = onResetZoom) {
                    Text(text = "100%", color = Color.White)
                }
            }
            if (onToggleBookmark != null) {
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        painter =
                            painterResource(
                                if (isBookmarked) {
                                    R.drawable.ic_bookmark
                                } else {
                                    R.drawable.ic_bookmark_border
                                }
                            ),
                        contentDescription = if (isBookmarked) "移除当前页书签" else "添加当前页书签",
                        tint = if (isBookmarked) accent else Color.White,
                    )
                }
            }
        }
    }
}

/** Shared reader bottom Chrome for page controls and chapter-transition status. */
// Slider 的 thumb/track 自定义插槽在 M3 里仍标记为实验性 API
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderBottomControls(
    visible: Boolean,
    transition: ReaderChapterTransition?,
    onTransitionRetry: (() -> Unit)?,
    currentPage: Int,
    pageCount: Int,
    pageDirection: ReaderPageDirection,
    onPageSelected: (Int) -> Unit,
    chapterCount: Int,
    thumbnails: Map<Int, ImageBitmap>,
    bookmarkPages: Set<Int>,
    onNeedThumbnail: (Int) -> Unit,
    onPagesSelected: () -> Unit,
    onHeightChanged: (Int) -> Unit,
    activePanel: ReaderPanel?,
    onPanelSelected: (ReaderPanel) -> Unit,
    attachedContent: @Composable ColumnScope.(ReaderPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = readerAccentColor()
    // Keep the exact filmstrip position while another dock tab replaces the page content.
    val filmstripListState =
        rememberLazyListState(initialFirstVisibleItemIndex = currentPage)

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        DisposableEffect(Unit) {
            onDispose { onHeightChanged(0) }
        }

        Column(
            modifier =
                Modifier.fillMaxWidth()
                    // Attached content must remain legible over monochrome artwork.
                    // This is one shared reader-control surface, not a translucent sheet.
                    .background(Color.Black)
                    .navigationBarsPadding()
                    .onSizeChanged { onHeightChanged(it.height) }
                    .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (transition != null) {
                ReaderTransitionBottomContent(
                    transition = transition,
                    onRetry = onTransitionRetry,
                    accent = accent,
                )
            } else {
                ReaderAttachedPanel(
                    panel = activePanel,
                    accent = accent,
                    content = attachedContent,
                    modifier =
                        if (activePanel == ReaderPanel.Tools) {
                            Modifier
                        } else {
                            Modifier.fillMaxHeight(0.5f)
                        },
                )

                if (activePanel == null) {
                    Column {
                        // 拖动中的临时值；null = 未在拖动，滑杆跟随真实页码。
                        // 松手才真正跳页：拖动中实时翻页会狂触发图片加载
                        var draggingValue by remember { mutableStateOf<Float?>(null) }
                        val shownPage = draggingValue?.roundToInt() ?: currentPage

                        // 胶片条与滑杆共享 shownPage：拖滑杆时胶片实时跟随滚动，
                        // 形成"滑杆粗跳 + 胶片看准了再点"的两级定位。
                        FilmstripRow(
                            pageCount = pageCount,
                            pageDirection = pageDirection,
                            listState = filmstripListState,
                            thumbnails = thumbnails,
                            bookmarkPages = bookmarkPages,
                            onNeedThumbnail = onNeedThumbnail,
                            currentPage = shownPage,
                            accent = accent,
                            isDragging = draggingValue != null,
                            onPageSelected = onPageSelected,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // 页码在滑杆两端的单行布局：当前页 | 粗轨道滑杆 | 总页数。
                        // 比"页码单独一行 + 滑杆"省一行高度，也是控制态下唯一的页码来源
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        ) {
                            Text(
                                text = "${shownPage + 1}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(min = 36.dp),
                            )
                            Slider(
                                value = draggingValue ?: currentPage.toFloat(),
                                onValueChange = { draggingValue = it },
                                onValueChangeFinished = {
                                    draggingValue?.let { value ->
                                        onPageSelected(value.roundToInt())
                                        draggingValue = null
                                    }
                                },
                                valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat(),
                                thumb = {
                                    Box(
                                        modifier =
                                            Modifier.size(width = 5.dp, height = 28.dp)
                                                .clip(RoundedCornerShape(2.5.dp))
                                                .background(Color.White)
                                    )
                                },
                                track = {
                                    val fraction =
                                        if (pageCount > 1) {
                                            (draggingValue ?: currentPage.toFloat()) /
                                                (pageCount - 1)
                                        } else {
                                            0f
                                        }
                                    Box(modifier = Modifier.fillMaxWidth().height(14.dp)) {
                                        Box(
                                            modifier =
                                                Modifier.matchParentSize()
                                                    .clip(RoundedCornerShape(7.dp))
                                                    .background(Color.White.copy(alpha = 0.25f))
                                        )
                                        Box(
                                            modifier =
                                                Modifier.fillMaxWidth(fraction)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(7.dp))
                                                    .background(accent)
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            )
                            Text(
                                text = "$pageCount",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(min = 36.dp),
                            )
                        }
                    }
                }

                ReaderDockRow(
                    destinations = readerDockDestinations(chapterCount),
                    activePanel = activePanel,
                    accent = accent,
                    onPagesClick = onPagesSelected,
                    onPanelSelected = onPanelSelected,
                )
            }
        }
    }
}

@Composable
private fun ReaderTransitionBottomContent(
    transition: ReaderChapterTransition,
    onRetry: (() -> Unit)?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val statusLabel =
        when (transition.status) {
            ReaderTransitionStatus.Loading -> "正在加载"
            ReaderTransitionStatus.Ready -> "继续翻页进入"
            ReaderTransitionStatus.Error -> "加载失败"
            ReaderTransitionStatus.Boundary -> "已经到达内容边界"
        }
    Row(
        modifier = modifier.fillMaxWidth().height(60.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = readerTransitionHeading(transition),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = statusLabel,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        when (transition.status) {
            ReaderTransitionStatus.Loading ->
                CircularProgressIndicator(
                    color = accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
            ReaderTransitionStatus.Error ->
                if (onRetry != null) {
                    TextButton(onClick = onRetry) { Text("重试", color = accent) }
                }
            ReaderTransitionStatus.Ready,
            ReaderTransitionStatus.Boundary -> Unit
        }
    }
}

internal enum class ReaderDockDestination(
    val label: String,
    @DrawableRes val icon: Int,
    // null 表示"页码"这个默认态：不对应任何附加面板
    val panel: ReaderPanel?,
) {
    Pages(
        label = "页码",
        icon = MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_pages_outlined,
        panel = null,
    ),
    Chapters(
        label = "章节",
        icon = MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_list_alt_outlined,
        panel = ReaderPanel.Chapters,
    ),
    Bookmarks(
        label = "书签",
        icon = MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_bookmarks_outlined,
        panel = ReaderPanel.Bookmarks,
    ),
    Tools(
        label = "工具",
        icon = MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_handyman_outlined,
        panel = ReaderPanel.Tools,
    ),
    Settings(
        label = "设置",
        icon = MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_tune_outlined,
        panel = ReaderPanel.Settings,
    ),
}

internal fun readerDockDestinations(chapterCount: Int): List<ReaderDockDestination> = buildList {
    add(ReaderDockDestination.Pages)
    if (shouldShowChapterMenu(chapterCount)) add(ReaderDockDestination.Chapters)
    add(ReaderDockDestination.Bookmarks)
    add(ReaderDockDestination.Tools)
    add(ReaderDockDestination.Settings)
}

@Composable
private fun ReaderDockRow(
    destinations: List<ReaderDockDestination>,
    activePanel: ReaderPanel?,
    accent: Color,
    onPagesClick: () -> Unit,
    onPanelSelected: (ReaderPanel) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.Black.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { destination ->
                val panel = destination.panel
                ReaderDockItem(
                    destination = destination,
                    selected = activePanel == panel,
                    accent = accent,
                    onClick =
                        if (panel != null) {
                            { onPanelSelected(panel) }
                        } else {
                            onPagesClick
                        },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun ReaderDockItem(
    destination: ReaderDockDestination,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val indicatorColor by
        animateColorAsState(
            targetValue = if (selected) accent.copy(alpha = 0.28f) else Color.Transparent,
            label = "dock-item-indicator",
        )
    val iconTint by
        animateColorAsState(
            targetValue = if (selected) accent else Color.White.copy(alpha = 0.75f),
            label = "dock-item-icon",
        )
    val textColor by
        animateColorAsState(
            targetValue = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
            label = "dock-item-text",
        )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
            modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 2.dp)
                .semantics(mergeDescendants = true) { this.selected = selected },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(width = 52.dp, height = 28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(indicatorColor),
        ) {
            Icon(
                painter = painterResource(destination.icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = destination.label,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** 胶片式缩略图导航条：横向一行迷你缩略图，当前页高亮并保持居中。 横滑只是浏览，点击缩略图才跳页。 */
@Composable
private fun FilmstripRow(
    pageCount: Int,
    pageDirection: ReaderPageDirection,
    listState: LazyListState,
    thumbnails: Map<Int, ImageBitmap>,
    bookmarkPages: Set<Int>,
    onNeedThumbnail: (Int) -> Unit,
    currentPage: Int,
    accent: Color,
    isDragging: Boolean,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 当前页变化时让对应缩略图保持居中。
    // 用户手动横滑浏览时 currentPage 不变，不会触发回滚，互不打架
    var hasCentered by remember(pageDirection) { mutableStateOf(false) }
    LaunchedEffect(currentPage, isDragging, pageDirection) {
        // 首帧布局完成前 viewport 宽度为 0，算不出居中偏移，等它就绪
        snapshotFlow { listState.layoutInfo.viewportEndOffset }.first { it > 0 }
        val info = listState.layoutInfo
        val viewportWidth = info.viewportEndOffset - info.viewportStartOffset
        val itemWidth = info.visibleItemsInfo.firstOrNull()?.size ?: 0
        // 负偏移 = 让条目起点落在视口中部偏左半个条目宽，即视觉居中
        val centerOffset = -(viewportWidth - itemWidth) / 2

        // 跳转策略（与正文翻页的 scrollToPage 智慧对齐）：
        // - 首次定位/拖滑杆中：瞬时贴住。跟手场景播动画 = 动画刚起步就被
        //   下一次页码变化掐死重启，表现为抖动
        // - 目标就在屏幕内的近距离移动（如普通翻页）：动画滚动，平滑流动
        // - 目标在屏幕外（远跳）："剪辑式"跳转——先瞬移到目标同方向
        //   Jump to one screen before the target, then animate only the final screenful. Visible
        //   thumbnails load on demand without animateScrollToItem crawling through dozens of cells.
        val visibleItems = info.visibleItemsInfo
        val targetVisible = visibleItems.any { it.index == currentPage }
        when {
            !hasCentered || isDragging -> {
                listState.scrollToItem(currentPage, centerOffset)
                hasCentered = true
            }

            targetVisible -> listState.animateScrollToItem(currentPage, centerOffset)

            else -> {
                val visibleCount = visibleItems.size.coerceAtLeast(1)
                // 目标在当前视野的右侧则从左侧一屏外滑入，反之亦然，
                // 保证动画方向与跳转方向一致
                val jumpForward = currentPage > (visibleItems.firstOrNull()?.index ?: 0)
                val approach =
                    if (jumpForward) currentPage - visibleCount else currentPage + visibleCount
                listState.scrollToItem(approach.coerceIn(0, pageCount - 1), centerOffset)
                listState.animateScrollToItem(currentPage, centerOffset)
            }
        }
    }

    LazyRow(
        state = listState,
        reverseLayout = readerPagerReverseLayout(pageDirection),
        // 顶部留白给选中格的放大腾空间：缩放锚点在底边，80dp 高的格子
        // 放大 15% 全部向上生长（约 12dp）；可滚动容器会裁剪越界内容，
        // 不留白会被切平。底部不生长，只留 2dp 呼吸空隙
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        items(count = pageCount, key = { it }) { page ->
            FilmstripThumb(
                page = page,
                thumbnail = thumbnails[page],
                onNeedThumbnail = onNeedThumbnail,
                selected = page == currentPage,
                bookmarked = page in bookmarkPages,
                accent = accent,
                onClick = { onPageSelected(page) },
            )
        }
    }
}

@Composable
private fun FilmstripThumb(
    page: Int,
    thumbnail: ImageBitmap?,
    onNeedThumbnail: (Int) -> Unit,
    selected: Boolean,
    bookmarked: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Request a missing thumbnail when its cell becomes visible. The ViewModel deduplicates work.
    if (thumbnail == null) {
        LaunchedEffect(page) { onNeedThumbnail(page) }
    }

    // 非当前页的暗化量带动画：翻页时高亮在胶片上"流动"过去，而不是生硬跳格
    val dimAlpha by
        animateFloatAsState(
            targetValue = if (selected) 0f else 0.45f,
            label = "thumb-dim",
        )

    // 选中格放大 15%：左右各探出约 4.2dp，刚好落在 8dp 间距内不压邻居
    val scale by
        animateFloatAsState(
            targetValue = if (selected) 1.15f else 1f,
            label = "thumb-scale",
        )

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(4.dp)
        Box(
            modifier =
                Modifier
                    // graphicsLayer 放链首：缩放作用于后续的裁剪/背景/描边整体，
                    // 且只发生在绘制阶段——布局尺寸不变，邻居与居中算式都不受影响。
                    // 锚点钉在底边中点：放大只向上和左右生长，底边不动，
                    // 不会压到紧贴下方的页码数字（Dock 式向上弹起）
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .size(width = 56.dp, height = 80.dp)
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .then(
                        // 描边用主题强调色（经亮度兜底，见 readerAccentColor）：
                        // 白描边在白底漫画页上会隐形
                        if (selected) Modifier.border(1.dp, accent, shape) else Modifier
                    )
                    .semantics {
                        contentDescription =
                            if (bookmarked) {
                                "第 ${page + 1} 页缩略图，已添加书签"
                            } else {
                                "第 ${page + 1} 页缩略图"
                            }
                    }
                    .clickable(onClick = onClick)
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 暗化层盖在图片上方；当前页 alpha 为 0 等于不存在
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAlpha)))
            if (bookmarked) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.align(Alignment.TopEnd)
                            .background(
                                color = Color.Black.copy(alpha = 0.72f),
                                shape = RoundedCornerShape(bottomStart = 4.dp),
                            )
                            .padding(2.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bookmark),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            text = "${page + 1}",
            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * 阅读页强调色（胶片高亮描边、滑杆进度填充）：跟随主题种子， 但保证在黑底上够亮。
 *
 * 不能直接用 primary：浅色模式下它是深色调（tone 40，为白底设计）， 压在阅读页的黑色工具栏上对比度不够。inversePrimary 正是 M3 为 "反色表面"准备的亮色调
 * primary（tone 80）。取两者中更亮的一个， 深浅模式下都能落到适合黑底的那档。
 */
@Composable
private fun readerAccentColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return readerColorOnDarkSurface(scheme.primary, scheme.inversePrimary)
}

private fun readerColorOnDarkSurface(first: Color, second: Color): Color =
    if (first.luminance() >= second.luminance()) first else second

@Composable
private fun ComicPage(
    volume: ComicVolume,
    page: Int,
    contentColor: Color,
    pageStateKey: ReaderPageStateKey,
    currentPageStateKey: ReaderPageStateKey?,
    cacheKeyPrefix: String,
    thumbnail: ImageBitmap?,
    zoomToggleRequest: Int,
    zoomResetRequest: Int,
    zoomTogglePage: ReaderPageStateKey?,
    zoomResetPage: ReaderPageStateKey?,
    zoomToggleAnchor: Offset,
    onZoomChanged: (Boolean) -> Unit,
    onVolumeTaskStarted: (ComicVolume) -> Boolean,
    onVolumeTaskFinished: (ComicVolume) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var scale by remember(pageStateKey) { mutableFloatStateOf(MIN_ZOOM_SCALE) }
    var offset by remember(pageStateKey) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(pageStateKey) { mutableStateOf(IntSize.Zero) }
    var useZoomedPdfRender by remember(pageStateKey) { mutableStateOf(false) }
    var decodeRetryRequest by remember(pageStateKey) { mutableIntStateOf(0) }
    var decodeFailureMessage by remember(pageStateKey) { mutableStateOf<String?>(null) }
    val isCurrentPage = pageStateKey == currentPageStateKey
    fun resetZoom() {
        scale = MIN_ZOOM_SCALE
        offset = Offset.Zero
    }

    fun toggleZoom(anchor: Offset) {
        if (scale > ZOOMED_THRESHOLD) {
            resetZoom()
        } else {
            scale = DEFAULT_ZOOM_SCALE
            val center = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
            offset =
                if (anchor != Offset.Unspecified) {
                    val requested = (center - anchor) * (DEFAULT_ZOOM_SCALE - 1f)
                    val maxX = viewportSize.width * (DEFAULT_ZOOM_SCALE - 1f) / 2f
                    val maxY = viewportSize.height * (DEFAULT_ZOOM_SCALE - 1f) / 2f
                    Offset(
                        x = requested.x.coerceIn(-maxX, maxX),
                        y = requested.y.coerceIn(-maxY, maxY),
                    )
                } else {
                    Offset.Zero
                }
        }
    }

    val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
        if (nextScale <= ZOOMED_THRESHOLD) {
            resetZoom()
            return@rememberTransformableState
        }
        val center = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        val maxX = viewportSize.width * (nextScale - 1f) / 2f
        val maxY = viewportSize.height * (nextScale - 1f) / 2f
        // Scale the existing translation with the zoom delta so the content under
        // the gesture centroid stays anchored while the viewport magnifies.
        val requested = offset * zoomChange + (centroid - center) * (1f - zoomChange) + panChange
        scale = nextScale
        offset =
            Offset(
                x = requested.x.coerceIn(-maxX, maxX),
                y = requested.y.coerceIn(-maxY, maxY),
            )
    }

    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) resetZoom()
    }

    LaunchedEffect(zoomToggleRequest, zoomTogglePage) {
        if (zoomTogglePage == pageStateKey) toggleZoom(zoomToggleAnchor)
    }

    LaunchedEffect(zoomResetRequest, zoomResetPage) {
        if (zoomResetPage == pageStateKey) resetZoom()
    }

    LaunchedEffect(scale, pageStateKey) {
        onZoomChanged(scale > ZOOMED_THRESHOLD)
    }

    LaunchedEffect(isCurrentPage, scale > ZOOMED_THRESHOLD) {
        if (!isCurrentPage || scale <= ZOOMED_THRESHOLD) {
            useZoomedPdfRender = false
        } else {
            delay(PDF_ZOOM_RENDER_DEBOUNCE_MS.milliseconds)
            useZoomedPdfRender = true
        }
    }

    val pageRenderRequest =
        targetedPageRenderRequest(
            supportsTargetedPageBitmap = volume.supportsTargetedPageBitmap,
            viewportSize = viewportSize,
            zoomed = useZoomedPdfRender && isCurrentPage,
        )

    // Keep page loading on the ordinary PDF/ZIP/album renderer.
    val contentKeys =
        readerPageContentKeys(
            volumeToken = volume,
            page = page,
            cacheKeyPrefix = cacheKeyPrefix,
            pageRenderRequest = pageRenderRequest,
        )
    val content by
        key(contentKeys.stateReset) {
            produceState<PageContent>(
                initialValue = PageContent.Loading,
                key1 = contentKeys.producerRestart,
                key2 = decodeRetryRequest,
            ) {
                if (!onVolumeTaskStarted(volume)) {
                    value = PageContent.Error("本页资源已释放")
                    return@produceState
                }
                value =
                    try {
                        if (volume.supportsTargetedPageBitmap && pageRenderRequest == null) {
                            PageContent.Loading
                        } else {
                            loadOriginalPageContent(volume, page, pageRenderRequest)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: ComicOpenException) {
                        PageContent.Error(e.message ?: "本页无法打开")
                    } catch (_: Exception) {
                        PageContent.Error("本页无法打开")
                    } finally {
                        onVolumeTaskFinished(volume)
                    }
            }
        }

    Box(
        modifier =
            modifier.fillMaxSize().clipToBounds().onSizeChanged { size ->
                if (viewportSize != IntSize.Zero && viewportSize != size) resetZoom()
                viewportSize = size
            },
        contentAlignment = Alignment.Center,
    ) {
        var imageReady by remember(page, content) { mutableStateOf(false) }

        Box(
            modifier =
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .transformable(
                        state = transformState,
                        canPan = { scale > ZOOMED_THRESHOLD },
                        enabled = isCurrentPage,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            // 垫底层：远跳到未加载页时，原图要经历 zip 解压 + 解码（几十到
            // 几百毫秒），期间这层保证屏幕有内容、消除黑屏闪烁。
            // 关键是重度模糊：直接放大的低清图"看得出是糊图"，很难看；
            // 模糊成只剩色调和明暗的色彩氛围，读作有意的过渡效果。
            // 原图加载完成后移除垫底层，避免在 Fit 留白处形成持久光晕。
            // blur 依赖 RenderEffect（API 31+），更低版本宁可黑屏也不展示糊图
            val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val showBlurPlaceholder =
                !imageReady && thumbnail != null && canBlur && content !is PageContent.Error
            if (showBlurPlaceholder) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().blur(24.dp),
                )
            }

            val pagePainter: Painter? =
                when (val c = content) {
                    is PageContent.Bitmap -> {
                        LaunchedEffect(c.bitmap) { imageReady = true }
                        remember(c.bitmap) { BitmapPainter(c.bitmap) }
                    }

                    is PageContent.Bytes -> {
                        if (viewportSize == IntSize.Zero) {
                            null
                        } else {
                            val imageRequest =
                                remember(
                                    context,
                                    c.bytes,
                                    cacheKeyPrefix,
                                    page,
                                    viewportSize,
                                ) {
                                    ImageRequest.Builder(context)
                                        .data(c.bytes)
                                        .memoryCacheKey(
                                            ReaderPageCacheKey.forPage(
                                                cacheKeyPrefix,
                                                page,
                                                volume.pageIdentity(page),
                                            )
                                        )
                                        .size(viewportSize.width, viewportSize.height)
                                        // 原图短淡入，避免加载完成时硬切；
                                        // 内存缓存命中时 Coil 自动跳过淡入，翻回已读页无延迟感
                                        .crossfade(150)
                                        .build()
                                }
                            rememberAsyncImagePainter(
                                model = imageRequest,
                                onSuccess = { imageReady = true },
                                onError = {
                                    if (decodeRetryRequest == 0 && volume.invalidatePage(page)) {
                                        decodeRetryRequest += 1
                                    } else {
                                        decodeFailureMessage = "页面图片无法解码"
                                        imageReady = true
                                    }
                                },
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }

                    else -> null
                }

            val pageArtwork: @Composable () -> Unit = {
                when (val c = content) {
                    is PageContent.Error -> {
                        Text(
                            text = c.message,
                            color = contentColor,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp),
                        )
                    }

                    PageContent.Loading -> {
                        // 没有模糊垫底（预热未到/系统不支持）才显示转圈
                        if (!showBlurPlaceholder) {
                            DelayedSpinner(showDelay = 200.milliseconds, color = contentColor)
                        }
                    }

                    is PageContent.Bitmap,
                    is PageContent.Bytes -> {
                        if (decodeFailureMessage != null) {
                            Text(
                                text = requireNotNull(decodeFailureMessage),
                                color = contentColor,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(32.dp),
                            )
                        } else if (pagePainter == null) {
                            DelayedSpinner(showDelay = 200.milliseconds, color = contentColor)
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painter = pagePainter,
                                    contentDescription = "第 ${page + 1} 页",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }

            pageArtwork()
        }
    }
}

@Composable
private fun ReaderPageStatus(
    pageLabel: String,
    contentColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clearAndSetSemantics { contentDescription = pageLabel }
                .background(containerColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = pageLabel,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val MIN_ZOOM_SCALE = 1f
private const val ZOOMED_THRESHOLD = 1.01f
private const val DEFAULT_ZOOM_SCALE = 2f
private const val MAX_ZOOM_SCALE = 3f
private const val PDF_ZOOM_RENDER_DEBOUNCE_MS = 180L
private const val PDF_ZOOM_RENDER_MULTIPLIER = 3
private const val MAX_PDF_RENDER_PIXELS = 8_000_000L
private const val MAX_PDF_RENDER_DIMENSION = 4096

internal fun pdfPageRenderRequest(viewportSize: IntSize, zoomed: Boolean): PageRenderRequest {
    val multiplier = if (zoomed) PDF_ZOOM_RENDER_MULTIPLIER else 1
    return PageRenderRequest(
        maxWidthPx =
            (viewportSize.width.toLong() * multiplier)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
                .coerceAtLeast(1),
        maxHeightPx =
            (viewportSize.height.toLong() * multiplier)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
                .coerceAtLeast(1),
        maxPixels = MAX_PDF_RENDER_PIXELS,
        maxDimensionPx = MAX_PDF_RENDER_DIMENSION,
    )
}

/** Builds the targeted fallback request once the viewport is known. */
internal fun targetedPageRenderRequest(
    supportsTargetedPageBitmap: Boolean,
    viewportSize: IntSize,
    zoomed: Boolean,
): PageRenderRequest? {
    if (!supportsTargetedPageBitmap || viewportSize.width <= 0 || viewportSize.height <= 0) {
        return null
    }
    return pdfPageRenderRequest(viewportSize, zoomed)
}

/**
 * 单页加载结果。优先走 [ComicVolume.loadPageBitmap]（PDF 直接返回渲染好的 ImageBitmap，跳过"渲染→PNG 压缩→Coil 解码"往返）；返回 null
 * 时 fallback 到 [ComicVolume.loadPageBytes]（zip/cbz 的压缩图片字节，交给 Coil 解码）。
 */
private sealed interface PageContent {
    data object Loading : PageContent

    data class Bitmap(val bitmap: ImageBitmap) : PageContent

    class Bytes(val bytes: ByteArray) : PageContent

    data class Error(val message: String) : PageContent
}

private suspend fun loadOriginalPageContent(
    volume: ComicVolume,
    page: Int,
    pageRenderRequest: PageRenderRequest? = null,
): PageContent {
    val bitmap = volume.loadPageBitmap(page, pageRenderRequest)
    return if (bitmap != null) {
        PageContent.Bitmap(bitmap)
    } else {
        PageContent.Bytes(volume.loadPageBytes(page))
    }
}

/**
 * 延迟显示的转圈：加载在 delayMillis 内完成就全程不显示。 "出现即消失的转圈"是闪烁感的主要来源——宁可短暂黑屏也不闪转圈， 这是 delayed spinner 的标准模式。
 */
@Composable
private fun DelayedSpinner(
    showDelay: Duration,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(showDelay)
        visible = true
    }
    if (visible) {
        CircularProgressIndicator(modifier = modifier, color = color)
    }
}

@Composable
private fun LoadingView(
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 400ms 内打开完成（小文件/缓存命中）就只是一段平滑的 Stage 过场
        DelayedSpinner(showDelay = 400.milliseconds, color = contentColor)
    }
}

@Composable
private fun ErrorView(
    message: String,
    onBack: () -> Unit,
    backLabel: String,
    onRemove: (() -> Unit)?,
    removeLabel: String?,
    backgroundColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = message, color = contentColor, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onBack,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = contentColor,
                    contentColor = backgroundColor,
                ),
        ) {
            Text(backLabel)
        }
        if (onRemove != null && removeLabel != null) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRemove,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
            ) {
                Text(removeLabel)
            }
        }
    }
}
