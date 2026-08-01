package com.exio.inkleaf.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.ComicVolume
import com.exio.inkleaf.data.OnlineChapterIdentity
import com.exio.inkleaf.data.OnlineContentIdentity
import com.exio.inkleaf.data.OnlinePageCacheIdentity
import com.exio.inkleaf.data.OnlinePageIdentity
import com.exio.inkleaf.data.OnlinePageLocation
import com.exio.inkleaf.data.ReadingSessionRules
import com.exio.inkleaf.data.ReaderCache
import com.exio.inkleaf.data.saveImageBytesToGallery
import com.exio.inkleaf.data.sanitizeFileName
import com.exio.inkleaf.plugin.ChapterSummary
import com.exio.inkleaf.plugin.OnlineAvailability
import com.exio.inkleaf.plugin.OnlineChapterRefresh
import com.exio.inkleaf.plugin.OnlineChapterVolume
import com.exio.inkleaf.plugin.OnlinePageBookmark
import com.exio.inkleaf.plugin.OnlineReadingSessionRecord
import com.exio.inkleaf.plugin.PluginContentCodec
import com.exio.inkleaf.plugin.PluginPagesRequest
import com.exio.inkleaf.plugin.PluginPagesResponse
import com.exio.inkleaf.plugin.onlinePageIdentities
import com.exio.inkleaf.plugin.resolveOnlineChapterRevision
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.TimeZone
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

internal class OnlineReaderViewModel(
    app: Application,
    private val pluginId: String,
    private val sourceId: String,
    chapterId: String,
    requestedRevision: String?,
    opaqueContextJson: String?,
    private val initialPageId: String?,
    private val initialPageIndex: Int?,
) : AndroidViewModel(app) {
    private val application = getApplication<InkleafApplication>()
    private val repository = application.onlineContentRepository
    private val applicationScope = application.applicationScope
    private val contentIdentity = OnlineContentIdentity(pluginId, sourceId)
    private val initialChapterId = chapterId
    private val routeOpaqueContext = opaqueContextJson?.let {
        runCatching { PluginContentCodec.json.parseToJsonElement(it) }.getOrNull()
    }

    private var activeChapterId: String = chapterId
    private var activeRequestedRevision: String? = requestedRevision
    private var activeOpaqueContext: JsonElement? = routeOpaqueContext
    private var contentOpaqueContext: JsonElement? = routeOpaqueContext
    private var chapterSummaries: List<ChapterSummary> = emptyList()
    private val chapterIdentity: OnlineChapterIdentity
        get() = OnlineChapterIdentity(contentIdentity, activeChapterId)

    var state by mutableStateOf<ReaderPresentationState>(ReaderPresentationState.Loading)
        private set

    val thumbnails = mutableStateMapOf<Int, ImageBitmap>()
    val thumbnailsByKey = mutableStateMapOf<ReaderPageStateKey, ImageBitmap>()
    val bookmarkPages = mutableStateMapOf<Int, Unit>()
    val bookmarkPageKeys = mutableStateMapOf<ReaderPageStateKey, Unit>()
    val bookmarks = mutableStateListOf<ReaderBookmarkItem>()
    val favoritePages = mutableStateMapOf<Int, Unit>()
    val favoritePageKeys = mutableStateMapOf<ReaderPageStateKey, Unit>()
    var readerChapters by mutableStateOf<List<ReaderChapterItem>?>(null)
        private set

    var currentChapterIndex by mutableIntStateOf(-1)
        private set

    var readerMessage by mutableStateOf<String?>(null)
        private set

    private var volume: OnlineChapterVolume? = null
    private val volumeUses = ReaderVolumeUseRegistry<ComicVolume>(ComicVolume::close)
    private var currentRevision: String? = requestedRevision
    private var currentChapterTitle: String = chapterId
    private var currentTitle: String = "在线漫画"
    private var currentPage by mutableIntStateOf(0)
    private var bookmarkEntriesByKey = emptyMap<String, OnlinePageBookmark>()
    private val thumbnailInFlight = mutableSetOf<Int>()
    private val thumbnailJobs = mutableSetOf<Job>()
    private val thumbnailMutex = Mutex()
    private val bookmarkMutationMutex = Mutex()
    private val favoriteMutationMutex = Mutex()
    private var galleryExportInFlight = false

    private var pendingProgressPage: Int? = null
    private var progressWriteJob: Job? = null
    private var chapterLoadJob: Job? = null
    private var descriptorRefreshJob: Job? = null
    private val pagePrefetchJobs = mutableSetOf<Job>()
    private var pagePrefetchDirection = 0
    private var pagePrefetchMetered = true
    private val transitionLoadJobs = mutableMapOf<ReaderTransitionDirection, Job>()
    private val transitionStatuses = mutableMapOf<ReaderTransitionDirection, ReaderTransitionStatus>()
    private val revealedAdjacentChapters = mutableSetOf<ReaderTransitionDirection>()
    private val lastPageByChapterId = mutableMapOf<String, Int>()

    // Adjacent volumes survive boundary commits so Pager can preserve settled page identity.
    private var preloadedNext: PreloadedChapter? = null
    private var preloadedPrevious: PreloadedChapter? = null

    private var sessionId: String? = null
    private var sessionStartedAtMs: Long = 0L
    private var sessionStartLocation: OnlinePageLocation? = null
    private var sessionLatestLocation: OnlinePageLocation? = null
    private var activeReadingMillis: Long = 0L
    private var activeSegmentStartedElapsedMs: Long? = null
    private var chapterReady = false
    private var processLifecycleAttached = false
    private var sessionEnded = false

    private val processLifecycleObserver =
        object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                startActiveSegment()
            }

            override fun onPause(owner: LifecycleOwner) {
                pauseActiveSegment()
            }
        }

    init {
        startChapterLoad()
    }

    fun reload() {
        startChapterLoad()
    }

    fun selectChapter(index: Int) {
        val chapter =
            selectableOnlineChapter(chapterSummaries, currentChapterIndex, index) ?: return
        if (chapterLoadJob?.isActive == true) return
        chapterLoadJob = viewModelScope.launch { prepareAndCommitChapter(chapter, index) }
    }

    fun onBoundarySettled(direction: ReaderTransitionDirection) {
        revealedAdjacentChapters += direction
        prepareAdjacentChapter(direction)
    }

    fun retryBoundary(direction: ReaderTransitionDirection) {
        if (transitionStatuses[direction] != ReaderTransitionStatus.Error) return
        transitionStatuses[direction] = ReaderTransitionStatus.Loading
        publishReaderWindow()
        loadTransitionChapter(direction)
    }

    private fun prepareAdjacentChapter(direction: ReaderTransitionDirection) {
        val target = adjacentChapter(direction)
        if (target == null) {
            transitionStatuses[direction] = ReaderTransitionStatus.Boundary
            publishReaderWindow()
            return
        }
        if (preloadedChapter(direction)?.index == target.second) {
            transitionStatuses[direction] = ReaderTransitionStatus.Ready
        } else {
            transitionStatuses[direction] = ReaderTransitionStatus.Loading
            loadTransitionChapter(direction)
        }
        publishReaderWindow()
    }

    fun onWindowPageSettled(pageKey: ReaderChapterPageKey) {
        val ready = state as? ReaderPresentationState.Ready ?: return
        val windowItem =
            ready.chapterWindow?.items
                ?.firstOrNull {
                    it is ReaderChapterWindowItem.Page<*> && it.pageKey == pageKey
                }
                ?: return
        @Suppress("UNCHECKED_CAST")
        val page =
            windowItem as ReaderChapterWindowItem.Page<ReaderWindowChapterContent>
        val settledEffect = readerSettledPageEffect(activeChapterId, page)
        if (settledEffect == ReaderSettledPageEffect.None) {
            saveProgress(page.chapter.payload.volume, page.pageIndex)
            return
        }
        val commit = settledEffect as? ReaderSettledPageEffect.CommitChapter ?: return
        if (chapterLoadJob?.isActive == true) return
        val direction =
            if (commit.chapterIndex > currentChapterIndex) {
                ChapterSwitchDirection.NEXT
            } else {
                ChapterSwitchDirection.PREVIOUS
            }
        val prepared =
            preloadedChapter(
                if (direction == ChapterSwitchDirection.NEXT) {
                    ReaderTransitionDirection.NEXT
                } else {
                    ReaderTransitionDirection.PREVIOUS
                }
            )?.takeIf { it.chapter.chapterId == commit.chapterId } ?: return
        chapterLoadJob =
            viewModelScope.launch {
                prepareAndCommitChapter(
                    chapter = prepared.chapter,
                    index = prepared.index,
                    direction = direction,
                    prebuilt = prepared,
                    settledPage = commit.pageIndex,
                )
            }
    }

    private fun loadTransitionChapter(direction: ReaderTransitionDirection) {
        if (transitionLoadJobs[direction]?.isActive == true) return
        val target = adjacentChapter(direction) ?: return
        val targetIndex = target.second
        val existing = preloadedChapter(direction)
        if (existing?.index == targetIndex) {
            scheduleAdjacentPagePrefetch(existing, direction)
            transitionStatuses[direction] = ReaderTransitionStatus.Ready
            publishReaderWindow()
            return
        }
        val loadJob =
            viewModelScope.launch {
                try {
                    val cached =
                        if (direction == ReaderTransitionDirection.NEXT) preloadedNext
                        else preloadedPrevious
                    val prepared =
                        cached?.takeIf { it.index == targetIndex }
                            ?: fetchChapterVolume(target.first).let { (volume, revision) ->
                                PreloadedChapter(volume, targetIndex, target.first, revision)
                            }
                    if (direction == ReaderTransitionDirection.NEXT) {
                        preloadedNext
                            ?.takeIf { it.volume !== prepared.volume }
                            ?.volume
                            ?.let(::closeVolumeWhenUnused)
                        preloadedNext = prepared
                    } else {
                        preloadedPrevious
                            ?.takeIf { it.volume !== prepared.volume }
                            ?.volume
                            ?.let(::closeVolumeWhenUnused)
                        preloadedPrevious = prepared
                    }
                    scheduleAdjacentPagePrefetch(prepared, direction)
                    transitionStatuses[direction] = ReaderTransitionStatus.Ready
                    publishReaderWindow()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    transitionStatuses[direction] = ReaderTransitionStatus.Error
                    publishReaderWindow()
                }
            }
        transitionLoadJobs[direction] = loadJob
        loadJob.invokeOnCompletion {
            if (transitionLoadJobs[direction] === loadJob) {
                transitionLoadJobs.remove(direction)
            }
        }
    }

    fun preloadNextChapter() {
        prepareAdjacentChapter(ReaderTransitionDirection.NEXT)
    }

    fun preloadPreviousChapter() {
        prepareAdjacentChapter(ReaderTransitionDirection.PREVIOUS)
    }

    fun requestThumbnail(page: Int) {
        launchThumbnailJob { loadThumbnail(page) }
    }

    private fun schedulePagePrefetch(
        opened: OnlineChapterVolume,
        page: Int,
        direction: Int,
    ) {
        val normalizedDirection = if (direction < 0) -1 else 1
        val metered = isMeteredNetwork()
        val distanceToBoundary =
            if (normalizedDirection > 0) opened.totalPageCount - 1 - page else page
        if (distanceToBoundary <= ADJACENT_PREFETCH_DISTANCE) {
            cancelPagePrefetch()
            prepareAdjacentChapter(
                if (normalizedDirection > 0) {
                    ReaderTransitionDirection.NEXT
                } else {
                    ReaderTransitionDirection.PREVIOUS
                }
            )
            return
        }
        val count = if (metered) METERED_PREFETCH_PAGES else UNMETERED_PREFETCH_PAGES
        val targets =
            onlinePagePrefetchOrder(
                currentPage = page,
                pageCount = opened.totalPageCount,
                direction = normalizedDirection,
                count = count,
            )
        launchPagePrefetch(opened, targets, normalizedDirection, metered)
    }

    private fun scheduleAdjacentPagePrefetch(
        prepared: PreloadedChapter,
        direction: ReaderTransitionDirection,
    ) {
        val prefetchDirection = if (direction == ReaderTransitionDirection.NEXT) 1 else -1
        val targets =
            adjacentOnlinePagePrefetchOrder(
                pageCount = prepared.volume.totalPageCount,
                direction = prefetchDirection,
            )
        launchPagePrefetch(
            prepared.volume,
            targets,
            prefetchDirection,
            isMeteredNetwork(),
        )
    }

    private fun launchPagePrefetch(
        opened: OnlineChapterVolume,
        targets: List<Int>,
        direction: Int,
        metered: Boolean,
    ) {
        cancelPagePrefetch()
        pagePrefetchDirection = direction
        pagePrefetchMetered = metered
        targets
            .withIndex()
            .groupBy { it.index % SPECULATIVE_DOWNLOADS }
            .values
            .map { lane -> lane.map { it.value } }
            .forEach { lane ->
                val job =
                    viewModelScope.launch(start = CoroutineStart.LAZY) {
                        for (target in lane) {
                            if (
                                pagePrefetchDirection != direction ||
                                    pagePrefetchMetered != isMeteredNetwork()
                            ) {
                                break
                            }
                            try {
                                opened.prefetchPage(target)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }
                synchronized(pagePrefetchJobs) { pagePrefetchJobs += job }
                job.invokeOnCompletion {
                    synchronized(pagePrefetchJobs) { pagePrefetchJobs -= job }
                }
                job.start()
            }
    }

    private fun cancelPagePrefetch() {
        val jobs =
            synchronized(pagePrefetchJobs) {
                pagePrefetchJobs.toList().also { pagePrefetchJobs.clear() }
            }
        jobs.forEach { it.cancel() }
    }

    private fun isMeteredNetwork(): Boolean =
        application.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered
            ?: true

    fun saveProgress(source: ComicVolume, page: Int) {
        val opened = volume ?: return
        if (source !== opened) return
        if (page !in 0 until opened.totalPageCount) return
        val direction = page.compareTo(currentPage).takeIf { it != 0 } ?: pagePrefetchDirection
        currentPage = page
        schedulePagePrefetch(opened, page, direction)
        lastPageByChapterId[activeChapterId] = page
        sessionLatestLocation = locationFor(page)
        pendingProgressPage = page
        if (progressWriteJob?.isActive == true) return
        progressWriteJob = viewModelScope.launch {
            try {
                while (true) {
                    delay(PROGRESS_WRITE_INTERVAL_MS.milliseconds)
                    val latest = pendingProgressPage ?: break
                    pendingProgressPage = null
                    persistPosition(latest)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    pendingProgressPage?.let { latest ->
                        pendingProgressPage = null
                        persistPositionOnIo(latest)
                    }
                }
            }
        }
    }

    fun releaseInactiveVolume(disposed: ComicVolume) {
        val retained =
            disposed === volume ||
                disposed === preloadedNext?.volume ||
                disposed === preloadedPrevious?.volume
        if (!retained) closeVolumeWhenUnused(disposed)
    }

    fun acquireVolumeTask(candidate: ComicVolume): Boolean = volumeUses.acquire(candidate)

    fun releaseVolumeTask(candidate: ComicVolume) {
        volumeUses.release(candidate)
    }

    private fun closeVolumeWhenUnused(candidate: ComicVolume) {
        volumeUses.closeWhenUnused(candidate)
    }

    fun isActiveVolume(candidate: ComicVolume): Boolean = candidate === volume

    fun toggleBookmark(page: Int) {
        val location = locationForOrNull(page) ?: return
        val chapterTitle = currentChapterTitle
        viewModelScope.launch {
            try {
                var added = false
                bookmarkMutationMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val existing =
                            repository.get(pluginId, sourceId)?.pageBookmarks?.firstOrNull {
                                it.location.identity == location.identity
                            }
                        if (existing == null) {
                            repository.addPageBookmark(location, chapterTitle)
                            added = true
                        } else {
                            repository.removePageBookmark(existing.location.identity)
                        }
                    }
                    refreshUserRecords()
                }
                if (location.identity.chapter == chapterIdentity) {
                    readerMessage = if (added) "已添加书签" else "已移除书签"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (location.identity.chapter == chapterIdentity) {
                    readerMessage = error.message?.let { "书签操作失败：$it" } ?: "书签操作失败"
                }
            }
        }
    }

    suspend fun removeBookmark(item: ReaderBookmarkItem): ReaderBookmarkUndo {
        val removed = requireNotNull(bookmarkEntriesByKey[item.key]) { "Unknown bookmark" }
        bookmarkMutationMutex.withLock {
            withContext(Dispatchers.IO) {
                check(repository.removePageBookmark(removed.location.identity)) {
                    "Bookmark no longer exists"
                }
            }
            refreshUserRecords()
        }
        return ReaderBookmarkUndo {
            bookmarkMutationMutex.withLock {
                withContext(Dispatchers.IO) {
                    repository.restorePageBookmark(removed)
                }
                refreshUserRecords()
            }
        }
    }

    fun toggleFavorite(page: Int) {
        val location = locationForOrNull(page) ?: return
        val opened = volume ?: return
        val chapterTitle = currentChapterTitle
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!acquireVolumeTask(opened)) return@launch
            try {
                var added = false
                favoriteMutationMutex.withLock {
                    val existing =
                        withContext(Dispatchers.IO) {
                            repository.get(pluginId, sourceId)?.pageFavorites?.firstOrNull {
                                it.location.identity == location.identity
                            }
                        }
                    if (existing != null) {
                        withContext(Dispatchers.IO) {
                            repository.removePageFavorite(existing.location.identity)
                        }
                    } else {
                        createFavoriteSnapshot(page, location, opened, chapterTitle)
                        added = true
                    }
                    refreshUserRecords()
                }
                if (location.identity.chapter == chapterIdentity) {
                    readerMessage = if (added) "已收藏" else "已取消收藏"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (location.identity.chapter == chapterIdentity) {
                    readerMessage = error.message?.let { "收藏失败：$it" } ?: "收藏失败"
                }
            } finally {
                releaseVolumeTask(opened)
            }
        }
    }

    fun consumeReaderMessage() {
        readerMessage = null
    }

    fun saveCurrentPageToGallery(page: Int) {
        val opened = volume ?: return
        if (page !in 0 until opened.totalPageCount) return
        if (galleryExportInFlight) return
        galleryExportInFlight = true
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!acquireVolumeTask(opened)) return@launch
            try {
                val bytes = opened.loadPageBytes(page)
                saveImageBytesToGallery(
                    application,
                    bytes,
                    "${sanitizeFileName(currentTitle)}_${sanitizeFileName(currentChapterTitle)}_p${page + 1}",
                )
                readerMessage = "已保存到相册"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                readerMessage = error.message?.let { "保存到相册失败：$it" } ?: "保存到相册失败"
            } finally {
                releaseVolumeTask(opened)
                galleryExportInFlight = false
            }
        }
    }

    fun endReadingSession() {
        finishSession()
    }

    private fun startChapterLoad() {
        if (chapterLoadJob?.isActive == true) return
        cancelPagePrefetch()
        descriptorRefreshJob?.cancel()
        descriptorRefreshJob = null
        discardPreloadedChapters()
        chapterLoadJob = viewModelScope.launch { loadActiveChapter() }
    }

    private suspend fun loadActiveChapter() {
        val previousOpened = volume
        state = ReaderPresentationState.Loading
        try {
            val snapshot = withContext(Dispatchers.IO) { repository.get(pluginId, sourceId) }
            updateChapterNavigation(snapshot)
            val chapter =
                ChapterSummary(
                    chapterId = activeChapterId,
                    title = currentChapterTitle,
                    revision = activeRequestedRevision,
                    opaqueContext = activeOpaqueContext,
                )
            val (opened, revision) = fetchChapterVolume(chapter)
            currentRevision = revision
            volume = opened
            previousOpened?.takeIf { it !== opened }?.let(::closeVolumeWhenUnused)
            cancelThumbnailJobs()
            clearChapterPresentation()
            val restored = restorePage(snapshot?.position, opened)
            val startPage = restored.page
            opened.loadPageBytes(startPage)
            currentPage = startPage
            lastPageByChapterId[activeChapterId] = startPage
            val initialLocation = locationFor(startPage)
            if (sessionId == null) {
                beginReadingSession(initialLocation)
            } else {
                sessionLatestLocation = initialLocation
            }
            withContext(Dispatchers.IO) {
                repository.setAvailability(pluginId, sourceId, OnlineAvailability.AVAILABLE)
            }
            refreshUserRecords()
            state =
                ReaderPresentationState.Ready(
                    volume = opened,
                    startPage = startPage,
                    title = currentTitle,
                    cacheKeyPrefix = opened.cacheKeyPrefix,
                )
            transitionStatuses.clear()
            revealedAdjacentChapters.clear()
            publishReaderWindow()
            chapterReady = true
            resumeActiveSegmentIfProcessResumed()
            prewarmThumbnails(opened, startPage)
            scheduleDescriptorRevalidation(chapter, opened)
            schedulePagePrefetch(opened, startPage, direction = 1)
            if (restored.stale) {
                readerMessage = "源内容已变化，已打开最接近的页面"
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            chapterReady = false
            volume?.let(::closeVolumeWhenUnused)
            volume = null
            withContext(Dispatchers.IO) {
                runCatching {
                    repository.setAvailability(
                        pluginId,
                        sourceId,
                        error.toOnlineAvailability(),
                    )
                }
            }
            state = ReaderPresentationState.Error(error.message ?: "加载页面失败")
        }
    }

    private suspend fun prepareAndCommitChapter(
        chapter: ChapterSummary,
        index: Int,
        direction: ChapterSwitchDirection = ChapterSwitchDirection.MANUAL,
        prebuilt: PreloadedChapter? = null,
        settledPage: Int? = null,
    ) {
        val previousChapterId = activeChapterId
        val previousRevision = currentRevision
        val previousIndex = currentChapterIndex
        val previousOpened = volume
        val previousSummary =
            chapterSummaries.getOrNull(previousIndex)
                ?: ChapterSummary(
                    chapterId = activeChapterId,
                    title = currentChapterTitle,
                    revision = activeRequestedRevision,
                    opaqueContext = activeOpaqueContext,
                )
        val savedPosition =
            if (direction == ChapterSwitchDirection.MANUAL) {
                withContext(Dispatchers.IO) {
                    runCatching { repository.get(pluginId, sourceId)?.position }.getOrNull()
                }
            } else {
                null
            }
        var prepared: OnlineChapterVolume? = null
        var committed = false
        readerMessage =
            when (direction) {
                ChapterSwitchDirection.MANUAL -> "正在切换章节"
                ChapterSwitchDirection.NEXT,
                ChapterSwitchDirection.PREVIOUS,
                ChapterSwitchDirection.REFRESH -> null
            }
        try {
            val candidate: OnlineChapterVolume
            val revision: String
            if (prebuilt != null && prebuilt.index == index) {
                candidate = prebuilt.volume
                revision = prebuilt.revision
            } else {
                val (vol, rev) = fetchChapterVolume(chapter)
                candidate = vol
                revision = rev
            }
            prepared = candidate
            val startPage =
                settledPage?.takeIf { it in candidate.pages.indices }
                    ?: when (direction) {
                        ChapterSwitchDirection.NEXT -> 0
                        // Enter a previous chapter on its last page to preserve reading direction.
                        ChapterSwitchDirection.PREVIOUS ->
                            (candidate.totalPageCount - 1).coerceAtLeast(0)
                        ChapterSwitchDirection.MANUAL ->
                            restorePageForChapter(
                                chapter.chapterId,
                                revision,
                                savedPosition,
                                candidate,
                            )
                        ChapterSwitchDirection.REFRESH ->
                            currentPage.coerceIn(0, candidate.totalPageCount - 1)
                    }
            candidate.loadPageBytes(startPage)
            if (activeChapterId != previousChapterId || currentRevision != previousRevision) {
                closeVolumeWhenUnused(candidate)
                prepared = null
                return
            }

            pauseActiveSegment()
            chapterReady = false
            flushCurrentProgress()
            lastPageByChapterId[activeChapterId] = currentPage
            cancelPagePrefetch()
            descriptorRefreshJob?.cancel()
            descriptorRefreshJob = null
            cancelThumbnailJobs()
            clearChapterPresentation()
            activeChapterId = chapter.chapterId
            activeRequestedRevision = chapter.revision
            activeOpaqueContext = chapter.opaqueContext ?: contentOpaqueContext
            currentChapterTitle = chapter.title.takeIf(String::isNotBlank) ?: chapter.chapterId
            currentChapterIndex = index
            currentRevision = revision
            if (
                direction == ChapterSwitchDirection.NEXT ||
                    direction == ChapterSwitchDirection.PREVIOUS
            ) {
                val oldWindowChapter =
                    if (previousOpened != null && previousRevision != null && previousIndex >= 0) {
                        PreloadedChapter(
                            volume = previousOpened,
                            index = previousIndex,
                            chapter = previousSummary,
                            revision = previousRevision,
                        )
                    } else {
                        null
                    }
                retainWindowAfterCommit(direction, oldWindowChapter, candidate)
            } else {
                discardPreloadedChapters(except = candidate)
            }
            volume = candidate
            currentPage = startPage
            lastPageByChapterId[activeChapterId] = startPage
            sessionLatestLocation = locationFor(startPage)
            state =
                ReaderPresentationState.Ready(
                    volume = candidate,
                    startPage = startPage,
                    title = currentTitle,
                    cacheKeyPrefix = candidate.cacheKeyPrefix,
                )
            publishReaderWindow()
            committed = true
            chapterReady = true
            resumeActiveSegmentIfProcessResumed()
            prewarmThumbnails(candidate, startPage)
            scheduleDescriptorRevalidation(chapter, candidate)
            schedulePagePrefetch(
                candidate,
                startPage,
                direction = if (direction == ChapterSwitchDirection.PREVIOUS) -1 else 1,
            )
            try {
                refreshUserRecords()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // User-record refresh never invalidates an otherwise readable prepared chapter.
            }
            if (direction == ChapterSwitchDirection.MANUAL) {
                readerMessage = null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!committed) {
                chapterReady = volume != null
                resumeActiveSegmentIfProcessResumed()
            }
            if (direction == ChapterSwitchDirection.NEXT ||
                direction == ChapterSwitchDirection.PREVIOUS
            ) {
                val transitionDirection =
                    if (direction == ChapterSwitchDirection.NEXT) {
                        ReaderTransitionDirection.NEXT
                    } else {
                        ReaderTransitionDirection.PREVIOUS
                    }
                transitionStatuses[transitionDirection] = ReaderTransitionStatus.Error
                revealedAdjacentChapters -= transitionDirection
                publishReaderWindow()
            } else if (direction == ChapterSwitchDirection.MANUAL) {
                readerMessage =
                    error.message?.let { "无法切换章节：$it" } ?: "无法切换章节"
            }
        } finally {
            if (!committed) prepared?.let(::closeVolumeWhenUnused)
        }
    }

    private suspend fun fetchChapterVolume(
        chapter: ChapterSummary
    ): Pair<OnlineChapterVolume, String> {
        val request = pagesRequest(chapter)
        val fetched = fetchPages(request)
        return createChapterVolume(chapter, request, fetched)
    }

    private suspend fun createChapterVolume(
        chapter: ChapterSummary,
        request: PluginPagesRequest,
        fetched: VersionedPages,
    ): Pair<OnlineChapterVolume, String> {
        val response = fetched.response
        if (response.pages.isEmpty()) throw ComicOpenException("目标章节没有可阅读页面")
        val revision = resolveOnlineChapterRevision(chapter.chapterId, chapter.revision, response)
        val identity = cacheIdentity(fetched.pluginVersion, response, chapter.chapterId, revision)
        val stablePageIdentities = onlinePageIdentities(revision, response.pages)
        val volume =
            OnlineChapterVolume(
                chapterId = chapter.chapterId,
                title = chapter.title.takeIf(String::isNotBlank) ?: chapter.chapterId,
                sourceRevision = revision,
                pages = response.pages,
                client = application.onlineImageCallFactory,
                cache = application.onlinePageCache,
                initialCacheIdentity = identity,
                refreshChapter = {
                    refreshChapterDescriptors(
                        request = request,
                        chapterId = chapter.chapterId,
                        expectedRevision = revision,
                        expectedPageIdentities = stablePageIdentities,
                    )
                },
            )
        try {
            application.onlinePageCache.writeManifest(
                identity = identity,
                pageIdentities = stablePageIdentities,
                fetchedAtMs = fetched.fetchedAtMs,
            )
        } catch (error: CancellationException) {
            volume.close()
            throw error
        }
        return volume to revision
    }

    private suspend fun refreshChapterDescriptors(
        request: PluginPagesRequest,
        chapterId: String,
        expectedRevision: String,
        expectedPageIdentities: List<String>,
    ): OnlineChapterRefresh? {
        val fetched = fetchPages(request)
        val response = fetched.response
        if (response.pages.isEmpty()) return null
        val revision = resolveOnlineChapterRevision(chapterId, request.revision, response)
        if (revision != expectedRevision) return null
        val pageIdentities = onlinePageIdentities(revision, response.pages)
        if (pageIdentities != expectedPageIdentities) return null
        val identity = cacheIdentity(fetched.pluginVersion, response, chapterId, revision)
        application.onlinePageCache.writeManifest(
            identity = identity,
            pageIdentities = pageIdentities,
            fetchedAtMs = fetched.fetchedAtMs,
        )
        return OnlineChapterRefresh(response.pages, identity)
    }

    private suspend fun fetchPages(request: PluginPagesRequest): VersionedPages {
        repeat(2) {
            val before = activePluginVersion()
            val response = application.pluginCatalog.pages(pluginId, request)
            val after = activePluginVersion()
            if (before == after) {
                return VersionedPages(response, after, System.currentTimeMillis())
            }
        }
        throw ComicOpenException("插件在读取章节时已更新，请重试")
    }

    private suspend fun activePluginVersion(): String =
        withContext(Dispatchers.IO) {
            application.pluginManager.installed()
                .firstOrNull { it.state.pluginId == pluginId }
                ?.let { installed ->
                    installed.state.activeVersion ?: installed.manifest?.version
                }
        } ?: throw ComicOpenException("插件版本不可用")

    private fun cacheIdentity(
        pluginVersion: String,
        response: PluginPagesResponse,
        chapterId: String,
        revision: String,
    ): OnlinePageCacheIdentity =
        OnlinePageCacheIdentity.create(
            pluginId = pluginId,
            pluginVersion = pluginVersion,
            accessScope = response.accessScope ?: LEGACY_ACCESS_SCOPE,
            sourceId = sourceId,
            chapterId = chapterId,
            revision = revision,
        )

    private fun pagesRequest(chapter: ChapterSummary): PluginPagesRequest =
        PluginPagesRequest(
            sourceId = sourceId,
            chapterId = chapter.chapterId,
            revision = chapter.revision,
            opaqueContext = chapter.opaqueContext ?: contentOpaqueContext,
        )

    private fun scheduleDescriptorRevalidation(
        chapter: ChapterSummary,
        opened: OnlineChapterVolume,
    ) {
        descriptorRefreshJob?.cancel()
        descriptorRefreshJob =
            viewModelScope.launch {
                delay(MANIFEST_TTL)
                if (volume !== opened || activeChapterId != chapter.chapterId) return@launch
                try {
                    val request = pagesRequest(chapter)
                    val fetched = fetchPages(request)
                    val response = fetched.response
                    if (response.pages.isEmpty()) return@launch
                    val revision =
                        resolveOnlineChapterRevision(chapter.chapterId, chapter.revision, response)
                    if (volume !== opened || currentRevision != opened.sourceRevision) return@launch
                    if (revision == opened.sourceRevision) {
                        val pageIdentities = onlinePageIdentities(revision, response.pages)
                        val identity =
                            cacheIdentity(
                                fetched.pluginVersion,
                                response,
                                chapter.chapterId,
                                revision,
                            )
                        val previousCacheKeyPrefix = opened.cacheKeyPrefix
                        if (opened.replaceDescriptors(OnlineChapterRefresh(response.pages, identity))) {
                            application.onlinePageCache.writeManifest(
                                identity = identity,
                                pageIdentities = pageIdentities,
                                fetchedAtMs = fetched.fetchedAtMs,
                            )
                            if (opened.cacheKeyPrefix != previousCacheKeyPrefix) {
                                cancelThumbnailJobs()
                                thumbnails.clear()
                                thumbnailsByKey.clear()
                                prewarmThumbnails(opened, currentPage)
                            }
                            publishReaderWindow()
                        }
                        return@launch
                    }

                    val chapterIndex = currentChapterIndex
                    if (chapterIndex < 0) return@launch
                    val refreshedChapter = chapter.copy(revision = revision)
                    val refreshedRequest = request.copy(revision = revision)
                    val (candidate, candidateRevision) =
                        createChapterVolume(refreshedChapter, refreshedRequest, fetched)
                    if (volume !== opened || activeChapterId != chapter.chapterId) {
                        closeVolumeWhenUnused(candidate)
                        return@launch
                    }
                    val targetPage =
                        candidate.findPageByIdentity(opened.pageIdentity(currentPage))
                            ?: currentPage.coerceIn(0, candidate.totalPageCount - 1)
                    descriptorRefreshJob = null
                    // Serialize the commit with chapter switches: while a chapter load is in
                    // flight the refresh must not commit on top of it, and the commit itself
                    // must claim chapterLoadJob so switches cannot enter prepareAndCommitChapter
                    // concurrently. coroutineScope keeps the commit a structured child of this
                    // descriptor job: cancelling the revalidation also cancels an in-flight
                    // commit, and async/await keeps commit failures in this try/catch.
                    if (chapterLoadJob?.isActive == true) {
                        closeVolumeWhenUnused(candidate)
                        return@launch
                    }
                    coroutineScope {
                        val commitJob =
                            async(start = CoroutineStart.LAZY) {
                                prepareAndCommitChapter(
                                    chapter = refreshedChapter,
                                    index = chapterIndex,
                                    direction = ChapterSwitchDirection.REFRESH,
                                    prebuilt =
                                        PreloadedChapter(
                                            candidate,
                                            chapterIndex,
                                            refreshedChapter,
                                            candidateRevision,
                                        ),
                                    settledPage = targetPage,
                                )
                            }
                        chapterLoadJob = commitJob
                        commitJob.start()
                        commitJob.await()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Stale descriptors and already cached pages remain readable after refresh failure.
                } finally {
                    if (coroutineContext[Job]?.isActive == true &&
                        volume === opened &&
                        activeChapterId == chapter.chapterId &&
                        currentRevision == opened.sourceRevision
                    ) {
                        descriptorRefreshJob = null
                        scheduleDescriptorRevalidation(chapter, opened)
                    }
                }
            }
    }

    private fun adjacentChapter(
        direction: ReaderTransitionDirection
    ): Pair<ChapterSummary, Int>? =
        when (direction) {
            ReaderTransitionDirection.NEXT ->
                nextReadableOnlineChapter(chapterSummaries, currentChapterIndex)
            ReaderTransitionDirection.PREVIOUS ->
                previousReadableOnlineChapter(chapterSummaries, currentChapterIndex)
        }

    private fun preloadedChapter(direction: ReaderTransitionDirection): PreloadedChapter? =
        if (direction == ReaderTransitionDirection.NEXT) preloadedNext else preloadedPrevious

    private fun publishReaderWindow() {
        val ready = state as? ReaderPresentationState.Ready ?: return
        val opened = volume ?: return
        val revision = currentRevision ?: return
        val active =
            ReaderWindowChapter(
                chapterId = activeChapterId,
                chapterIndex = currentChapterIndex.coerceAtLeast(0),
                chapterRevision = revision,
                pageIdentities = opened.pages.indices.map(opened::pageIdentity),
                payload =
                    ReaderWindowChapterContent(
                        volume = opened,
                        cacheKeyPrefix = opened.cacheKeyPrefix,
                    ),
            )
        state =
            ready.copy(
                volume = opened,
                cacheKeyPrefix = active.payload.cacheKeyPrefix,
                chapterWindow =
                    buildReaderChapterWindow(
                        active = active,
                        previous = readerWindowAdjacent(ReaderTransitionDirection.PREVIOUS),
                        next = readerWindowAdjacent(ReaderTransitionDirection.NEXT),
                    ),
            )
    }

    private fun readerWindowAdjacent(
        direction: ReaderTransitionDirection
    ): ReaderWindowAdjacent<ReaderWindowChapterContent> {
        val target = adjacentChapter(direction)
        val cached = preloadedChapter(direction)?.takeIf { it.index == target?.second }
        val status =
            when {
                target == null -> ReaderTransitionStatus.Boundary
                cached != null -> transitionStatuses[direction] ?: ReaderTransitionStatus.Ready
                else -> transitionStatuses[direction] ?: ReaderTransitionStatus.Loading
            }
        val index = target?.second
        return ReaderWindowAdjacent(
            targetChapterId = target?.first?.chapterId,
            transition =
                ReaderChapterTransition(
                    direction = direction,
                    chapterIndex = index,
                    title = target?.first?.title?.takeIf(String::isNotBlank).orEmpty(),
                    status = status,
                ),
            preparedChapter =
                cached
                    ?.takeIf { direction in revealedAdjacentChapters }
                    ?.toReaderWindowChapter(),
        )
    }

    private fun PreloadedChapter.toReaderWindowChapter():
        ReaderWindowChapter<ReaderWindowChapterContent> =
        ReaderWindowChapter(
            chapterId = chapter.chapterId,
            chapterIndex = index,
            chapterRevision = revision,
            pageIdentities = volume.pages.indices.map(volume::pageIdentity),
            payload =
                ReaderWindowChapterContent(
                    volume = volume,
                    cacheKeyPrefix = volume.cacheKeyPrefix,
                ),
        )

    private fun retainWindowAfterCommit(
        direction: ChapterSwitchDirection,
        previousActive: PreloadedChapter?,
        committed: OnlineChapterVolume,
    ) {
        transitionLoadJobs.values.toList().forEach { it.cancel() }
        transitionLoadJobs.clear()
        val retainedVolumes = setOfNotNull(previousActive?.volume, committed)
        preloadedPrevious
            ?.takeIf {
                it.volume !in retainedVolumes &&
                    ReaderTransitionDirection.PREVIOUS !in revealedAdjacentChapters
            }
            ?.volume
            ?.let(::closeVolumeWhenUnused)
        preloadedNext
            ?.takeIf {
                it.volume !in retainedVolumes &&
                    ReaderTransitionDirection.NEXT !in revealedAdjacentChapters
            }
            ?.volume
            ?.let(::closeVolumeWhenUnused)
        when (direction) {
            ChapterSwitchDirection.NEXT -> {
                preloadedPrevious = previousActive
                preloadedNext = null
                revealedAdjacentChapters.clear()
                if (previousActive != null) {
                    revealedAdjacentChapters += ReaderTransitionDirection.PREVIOUS
                }
            }
            ChapterSwitchDirection.PREVIOUS -> {
                preloadedPrevious = null
                preloadedNext = previousActive
                revealedAdjacentChapters.clear()
                if (previousActive != null) {
                    revealedAdjacentChapters += ReaderTransitionDirection.NEXT
                }
            }
            ChapterSwitchDirection.MANUAL,
            ChapterSwitchDirection.REFRESH ->
                error("Only adjacent chapter changes retain a window")
        }
        transitionStatuses.clear()
    }

    /** Drops preload slots while retaining [except] and any volumes still visible in Pager. */
    private fun discardPreloadedChapters(
        except: OnlineChapterVolume? = null,
        deferRevealed: Boolean = true,
    ) {
        transitionLoadJobs.values.toList().forEach { it.cancel() }
        transitionLoadJobs.clear()
        val next = preloadedNext
        if (
            next != null &&
                next.volume !== except &&
                (!deferRevealed ||
                    ReaderTransitionDirection.NEXT !in revealedAdjacentChapters)
        ) {
            closeVolumeWhenUnused(next.volume)
        }
        val prev = preloadedPrevious
        if (
            prev != null &&
                prev.volume !== except &&
                (!deferRevealed ||
                    ReaderTransitionDirection.PREVIOUS !in revealedAdjacentChapters)
        ) {
            closeVolumeWhenUnused(prev.volume)
        }
        preloadedNext = null
        preloadedPrevious = null
        transitionStatuses.clear()
        revealedAdjacentChapters.clear()
    }

    private fun updateChapterNavigation(snapshot: com.exio.inkleaf.plugin.OnlineComicRecord?) {
        currentTitle = snapshot?.detail?.title?.takeIf(String::isNotBlank) ?: currentTitle
        contentOpaqueContext = snapshot?.detail?.opaqueContext ?: contentOpaqueContext
        if (snapshot != null && snapshot.chapters.isNotEmpty()) {
            chapterSummaries = snapshot.chapters
            readerChapters = buildOnlineReaderChapterItems(chapterSummaries)
        }
        currentChapterIndex = chapterSummaries.indexOfFirst { it.chapterId == activeChapterId }
        val chapter = chapterSummaries.getOrNull(currentChapterIndex)
        currentChapterTitle = chapter?.title?.takeIf(String::isNotBlank) ?: activeChapterId
        if (activeRequestedRevision == null) activeRequestedRevision = chapter?.revision
        if (activeOpaqueContext == null) {
            activeOpaqueContext = chapter?.opaqueContext ?: contentOpaqueContext
        }
    }

    private suspend fun flushCurrentProgress() {
        val page = pendingProgressPage ?: currentPage
        pendingProgressPage = null
        progressWriteJob?.cancelAndJoin()
        progressWriteJob = null
        if (volume != null) {
            try {
                withContext(Dispatchers.IO) { persistPositionOnIo(page) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A position write failure must not prevent the requested chapter from opening.
            }
        }
    }

    private fun clearChapterPresentation() {
        thumbnails.clear()
        thumbnailsByKey.clear()
        bookmarkPages.clear()
        bookmarkPageKeys.clear()
        bookmarks.clear()
        favoritePages.clear()
        favoritePageKeys.clear()
        bookmarkEntriesByKey = emptyMap()
        // Chapter switching owns readerMessage; clearing it here would erase an in-flight message.
    }

    private fun beginReadingSession(initialLocation: OnlinePageLocation) {
        sessionId = UUID.randomUUID().toString()
        sessionStartedAtMs = System.currentTimeMillis()
        sessionStartLocation = initialLocation
        sessionLatestLocation = initialLocation
        activeReadingMillis = 0L
        sessionEnded = false
        attachProcessLifecycle()
    }

    private fun restorePage(
        saved: com.exio.inkleaf.plugin.OnlineReadingPosition?,
        opened: OnlineChapterVolume,
    ): RestoredOnlinePage {
        val rememberedPage = lastPageByChapterId[activeChapterId]
        if (sessionId != null && rememberedPage != null && rememberedPage in opened.pages.indices) {
            return RestoredOnlinePage(rememberedPage, stale = false)
        }
        initialPageId
            ?.takeIf { sessionId == null && activeChapterId == initialChapterId }
            ?.let { pageId ->
                val exact = opened.pages.indexOfFirst { it.pageId == pageId }
                if (exact >= 0) return RestoredOnlinePage(exact, stale = false)
                initialPageIndex
                    ?.takeIf { it in opened.pages.indices }
                    ?.let {
                        return RestoredOnlinePage(it, stale = true)
                    }
            }
        initialPageIndex
            ?.takeIf {
                sessionId == null &&
                    activeChapterId == initialChapterId &&
                    it in opened.pages.indices
            }
            ?.let { page ->
                return RestoredOnlinePage(
                    page = page,
                    stale = activeRequestedRevision != currentRevision,
                )
            }
        if (saved == null || saved.chapterId != activeChapterId) return RestoredOnlinePage(0, false)
        saved.pageId?.let { pageId ->
            opened.pages
                .indexOfFirst { it.pageId == pageId }
                .takeIf { it >= 0 }
                ?.let {
                    return RestoredOnlinePage(it, stale = false)
                }
        }
        val restored =
            saved.pageIndex.takeIf {
                saved.chapterRevision == currentRevision && it in opened.pages.indices
            } ?: 0
        return RestoredOnlinePage(restored, stale = false)
    }

    private fun restorePageForChapter(
        chapterId: String,
        revision: String,
        saved: com.exio.inkleaf.plugin.OnlineReadingPosition?,
        opened: OnlineChapterVolume,
    ): Int {
        lastPageByChapterId[chapterId]
            ?.takeIf { it in opened.pages.indices }
            ?.let { return it }
        if (saved?.chapterId != chapterId) return 0
        saved.pageId?.let { pageId ->
            opened.pages
                .indexOfFirst { it.pageId == pageId }
                .takeIf { it >= 0 }
                ?.let { return it }
        }
        return saved.pageIndex.takeIf {
            saved.chapterRevision == revision && it in opened.pages.indices
        } ?: 0
    }

    private suspend fun refreshUserRecords() {
        val opened = volume ?: return
        val record = withContext(Dispatchers.IO) { repository.get(pluginId, sourceId) }
        val chapterBookmarks =
            record?.pageBookmarks.orEmpty().filter {
                it.location.identity.chapter == chapterIdentity
            }
        val resolvedBookmarks = chapterBookmarks.map { bookmark ->
            val resolution = resolvePage(bookmark.location, opened)
            val key = bookmarkKey(bookmark.location.identity)
            key to
                ReaderBookmarkItem(
                    key = key,
                    globalPage = resolution.page,
                    chapterIndex = currentChapterIndex.coerceAtLeast(0),
                    pageIndex = bookmark.location.pageIndex,
                    chapterTitle = bookmark.chapterTitleSnapshot ?: currentChapterTitle,
                    stale = resolution.stale,
                )
        }
        bookmarkEntriesByKey = chapterBookmarks.associateBy { bookmarkKey(it.location.identity) }
        bookmarks.clear()
        bookmarks.addAll(resolvedBookmarks.map { it.second }.sortedBy { it.globalPage })
        bookmarkPages.clear()
        bookmarkPageKeys.clear()
        resolvedBookmarks
            .filterNot { it.second.stale }
            .forEach {
                bookmarkPages[it.second.globalPage] = Unit
                bookmarkPageKeys[opened.readerPageStateKey(it.second.globalPage)] = Unit
            }

        val chapterFavorites =
            record?.pageFavorites.orEmpty().filter {
                it.location.identity.chapter == chapterIdentity
            }
        favoritePages.clear()
        favoritePageKeys.clear()
        chapterFavorites.forEach { favorite ->
            val resolution = resolvePage(favorite.location, opened)
            if (!resolution.stale) {
                favoritePages[resolution.page] = Unit
                favoritePageKeys[opened.readerPageStateKey(resolution.page)] = Unit
            }
        }
    }

    private fun resolvePage(
        location: OnlinePageLocation,
        opened: OnlineChapterVolume,
    ): ResolvedOnlinePage {
        location.identity.pageId?.let { pageId ->
            val exact = opened.pages.indexOfFirst { it.pageId == pageId }
            if (exact >= 0) return ResolvedOnlinePage(exact, stale = false)
        }
        val fallback = location.identity.fallback
        val exactFallback =
            fallback != null &&
                fallback.chapterRevision == currentRevision &&
                fallback.pageIndex in opened.pages.indices
        if (exactFallback)
            return ResolvedOnlinePage(requireNotNull(fallback).pageIndex, stale = false)
        return ResolvedOnlinePage(
            page = location.pageIndex.coerceIn(0, opened.totalPageCount - 1),
            stale = true,
        )
    }

    private suspend fun createFavoriteSnapshot(
        page: Int,
        location: OnlinePageLocation,
        opened: OnlineChapterVolume,
        chapterTitle: String,
    ) {
        withContext(Dispatchers.IO) {
            val bytes = opened.loadPageBytes(page)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                throw ComicOpenException("本页图像无法解码")
            }
            val mimeType = options.outMimeType ?: "application/octet-stream"
            val destination =
                repository.pageFavoriteSnapshotFile(
                    location.identity,
                    snapshotExtension(mimeType),
                )
            val temporary =
                destination.resolveSibling("${destination.name}.tmp-${UUID.randomUUID()}")
            var published = false
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                moveSnapshot(temporary, destination)
                repository.recordPageFavoriteSnapshot(
                    location = location,
                    snapshotFile = destination,
                    mimeType = mimeType,
                    width = options.outWidth,
                    height = options.outHeight,
                    chapterTitleSnapshot = chapterTitle,
                )
                published = true
            } finally {
                if (temporary.exists()) temporary.delete()
                if (!published && destination.exists()) destination.delete()
            }
        }
    }

    private suspend fun loadThumbnail(page: Int) {
        val opened = volume ?: return
        if (page !in 0 until opened.totalPageCount) return
        thumbnailMutex.withLock {
            if (page in thumbnails || !thumbnailInFlight.add(page)) return
        }
        if (!acquireVolumeTask(opened)) {
            thumbnailMutex.withLock { thumbnailInFlight -= page }
            return
        }
        try {
            val namespace = opened.cacheKeyPrefix
            val pageIdentity = opened.pageIdentity(page)
            val cached =
                ReaderCache.readOnlineThumbnail(
                    application,
                    namespace,
                    page,
                    pageIdentity,
                )
            if (cached != null) {
                val image = cached.asImageBitmap()
                thumbnails[page] = image
                thumbnailsByKey[opened.readerPageStateKey(page)] = image
                return
            }
            val rendered =
                withContext(Dispatchers.IO) {
                    opened.renderThumbnail(page, THUMB_TARGET_WIDTH)
                } ?: return
            thumbnails[page] = rendered
            thumbnailsByKey[opened.readerPageStateKey(page)] = rendered
            ReaderCache.writeOnlineThumbnail(
                application,
                namespace,
                page,
                pageIdentity,
                rendered.asAndroidBitmap(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A failed thumbnail remains retryable and never blocks the full-size page.
        } finally {
            thumbnailMutex.withLock { thumbnailInFlight -= page }
            releaseVolumeTask(opened)
        }
    }

    private fun prewarmThumbnails(opened: OnlineChapterVolume, startPage: Int) {
        launchThumbnailJob {
            // The prewarm is bound to the volume that requested it; a chapter switch may have
            // replaced the active volume before the job runs.
            if (volume === opened) loadThumbnail(startPage)
        }
    }

    private fun launchThumbnailJob(block: suspend () -> Unit) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) { block() }
        synchronized(thumbnailJobs) { thumbnailJobs += job }
        job.invokeOnCompletion { synchronized(thumbnailJobs) { thumbnailJobs -= job } }
        job.start()
    }

    private suspend fun cancelThumbnailJobs() {
        val jobs =
            synchronized(thumbnailJobs) {
                thumbnailJobs.toList().also { thumbnailJobs.clear() }
            }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        thumbnailMutex.withLock { thumbnailInFlight.clear() }
    }

    private fun locationForOrNull(page: Int): OnlinePageLocation? {
        val opened = volume ?: return null
        if (page !in opened.pages.indices) return null
        return locationFor(page)
    }

    private fun locationFor(page: Int): OnlinePageLocation {
        val opened = requireNotNull(volume) { "Reader is not ready" }
        val descriptor = opened.pages[page]
        return OnlinePageLocation.create(
            chapter = chapterIdentity,
            pageId = descriptor.pageId,
            pageIndex = page,
            chapterRevision = currentRevision,
        )
    }

    private suspend fun persistPosition(page: Int) {
        withContext(Dispatchers.IO) { persistPositionOnIo(page) }
    }

    private fun persistPositionOnIo(page: Int) {
        val location = locationForOrNull(page) ?: return
        repository.recordPosition(
            pluginId = pluginId,
            sourceId = sourceId,
            chapterId = activeChapterId,
            pageId = location.identity.pageId,
            pageIndex = location.pageIndex,
            chapterRevision = location.chapterRevision,
        )
    }

    private fun attachProcessLifecycle() {
        if (processLifecycleAttached || sessionEnded) return
        processLifecycleAttached = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        resumeActiveSegmentIfProcessResumed()
    }

    private fun resumeActiveSegmentIfProcessResumed() {
        if (
            ProcessLifecycleOwner.get()
                .lifecycle
                .currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        ) {
            startActiveSegment()
        }
    }

    private fun detachProcessLifecycle() {
        if (!processLifecycleAttached) return
        processLifecycleAttached = false
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
    }

    private fun startActiveSegment() {
        if (!chapterReady || sessionEnded || activeSegmentStartedElapsedMs != null) return
        activeSegmentStartedElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun pauseActiveSegment() {
        val started = activeSegmentStartedElapsedMs ?: return
        val now = SystemClock.elapsedRealtime()
        activeReadingMillis += ReadingSessionRules.segmentDurationMillis(started, now)
        activeSegmentStartedElapsedMs = null
    }

    private fun finishSession() {
        if (sessionEnded) return
        sessionEnded = true
        pauseActiveSegment()
        detachProcessLifecycle()
        val finalPage = pendingProgressPage ?: currentPage
        pendingProgressPage = null
        progressWriteJob?.cancel()
        progressWriteJob = null
        val id = sessionId
        val start = sessionStartLocation
        val end = locationForOrNull(finalPage) ?: sessionLatestLocation
        val endedAtMs = System.currentTimeMillis().coerceAtLeast(sessionStartedAtMs)
        applicationScope.launch {
            if (end != null) {
                runCatching {
                    repository.recordPosition(
                        pluginId = pluginId,
                        sourceId = sourceId,
                        chapterId = end.identity.chapter.chapterId,
                        pageId = end.identity.pageId,
                        pageIndex = end.pageIndex,
                        chapterRevision = end.chapterRevision,
                    )
                }
            }
            if (
                id != null &&
                    start != null &&
                    end != null &&
                    (activeReadingMillis >= ReadingSessionRules.MIN_ACTIVE_READING_MS ||
                        start != end)
            ) {
                runCatching {
                    repository.recordReadingSession(
                        OnlineReadingSessionRecord(
                            sessionId = id,
                            content = contentIdentity,
                            titleSnapshot = currentTitle,
                            startedAtMs = sessionStartedAtMs,
                            endedAtMs = endedAtMs,
                            activeReadingMillis = activeReadingMillis,
                            timeZoneId = TimeZone.getDefault().id,
                            start = start,
                            end = end,
                        )
                    )
                }
            }
        }
    }

    private fun OnlineChapterVolume.readerPageStateKey(page: Int): ReaderPageStateKey =
        ReaderChapterPageKey(
            chapterId = activeChapterId,
            chapterRevision = sourceRevision,
            pageIdentity = pageIdentity(page),
        ).toReaderPageStateKey()

    private fun bookmarkKey(identity: OnlinePageIdentity): String =
        "online:${PluginContentCodec.json.encodeToString(identity)}"

    override fun onCleared() {
        descriptorRefreshJob?.cancel()
        descriptorRefreshJob = null
        cancelPagePrefetch()
        finishSession()
        discardPreloadedChapters(deferRevealed = false)
        volume?.let(::closeVolumeWhenUnused)
        volume = null
    }

    private data class ResolvedOnlinePage(val page: Int, val stale: Boolean)

    private data class RestoredOnlinePage(val page: Int, val stale: Boolean)

    private data class VersionedPages(
        val response: PluginPagesResponse,
        val pluginVersion: String,
        val fetchedAtMs: Long,
    )

    private enum class ChapterSwitchDirection { MANUAL, NEXT, PREVIOUS, REFRESH }

    private class PreloadedChapter(
        val volume: OnlineChapterVolume,
        val index: Int,
        val chapter: ChapterSummary,
        val revision: String,
    )

    private companion object {
        val MANIFEST_TTL = 15.minutes
        const val LEGACY_ACCESS_SCOPE = "legacy"
        const val METERED_PREFETCH_PAGES = 2
        const val UNMETERED_PREFETCH_PAGES = 5
        const val ADJACENT_PREFETCH_DISTANCE = 2
        const val SPECULATIVE_DOWNLOADS = 2
        const val THUMB_TARGET_WIDTH = 168
        const val PROGRESS_WRITE_INTERVAL_MS = 500L

        fun snapshotExtension(mimeType: String): String =
            when (mimeType.lowercase()) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/avif" -> "avif"
                else -> "img"
            }

        fun moveSnapshot(source: File, destination: File) {
            try {
                Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), destination.toPath())
            }
        }
    }
}

internal fun buildOnlineReaderChapterItems(
    chapters: List<ChapterSummary>
): List<ReaderChapterItem> = chapters.mapIndexed { index, chapter ->
    ReaderChapterItem(
        index = index,
        title = chapter.title.ifBlank { "第 ${index + 1} 章" },
        pageCount = null,
        isReadable = chapter.available,
    )
}

internal fun selectableOnlineChapter(
    chapters: List<ChapterSummary>,
    currentChapterIndex: Int,
    targetChapterIndex: Int,
): ChapterSummary? =
    chapters.getOrNull(targetChapterIndex)?.takeIf {
        targetChapterIndex != currentChapterIndex && it.available
    }

internal fun nextReadableOnlineChapter(
    chapters: List<ChapterSummary>,
    currentChapterIndex: Int,
): Pair<ChapterSummary, Int>? {
    if (currentChapterIndex !in chapters.indices) return null
    val index = (currentChapterIndex + 1 until chapters.size).firstOrNull { chapters[it].available }
        ?: return null
    return chapters[index] to index
}

internal fun previousReadableOnlineChapter(
    chapters: List<ChapterSummary>,
    currentChapterIndex: Int,
): Pair<ChapterSummary, Int>? {
    if (currentChapterIndex !in chapters.indices) return null
    val index = (currentChapterIndex - 1 downTo 0).firstOrNull { chapters[it].available }
        ?: return null
    return chapters[index] to index
}
