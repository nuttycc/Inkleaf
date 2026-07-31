package com.exio.inkleaf.ui

import android.app.Application
import android.graphics.BitmapFactory
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
import com.exio.inkleaf.data.OnlinePageIdentity
import com.exio.inkleaf.data.OnlinePageLocation
import com.exio.inkleaf.data.ReadingSessionRules
import com.exio.inkleaf.data.ReaderCache
import com.exio.inkleaf.plugin.ChapterSummary
import com.exio.inkleaf.plugin.OnlineAvailability
import com.exio.inkleaf.plugin.OnlineChapterVolume
import com.exio.inkleaf.plugin.OnlinePageBookmark
import com.exio.inkleaf.plugin.OnlineReadingSessionRecord
import com.exio.inkleaf.plugin.PluginContentCodec
import com.exio.inkleaf.plugin.PluginPagesRequest
import com.exio.inkleaf.plugin.resolveOnlineChapterRevision
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.TimeZone
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
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
    val bookmarkPages = mutableStateMapOf<Int, Unit>()
    val bookmarks = mutableStateListOf<ReaderBookmarkItem>()
    val favoritePages = mutableStateMapOf<Int, Unit>()
    var readerChapters by mutableStateOf<List<ReaderChapterItem>?>(null)
        private set

    var currentChapterIndex by mutableIntStateOf(-1)
        private set

    var readerMessage by mutableStateOf<String?>(null)
        private set

    var chapterTransition by mutableStateOf<ReaderChapterTransition?>(null)
        private set

    private var volume: OnlineChapterVolume? = null
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

    private var pendingProgressPage: Int? = null
    private var progressWriteJob: Job? = null
    private var chapterLoadJob: Job? = null
    private val transitionLoadJobs = mutableMapOf<ReaderTransitionDirection, Job>()
    private val lastPageByChapterId = mutableMapOf<String, Int>()

    // 相邻章节预加载缓存：到达首/末页时后台预取，越界切换时直接复用，消除网络等待。
    // 每次章节切换后整体丢弃（邻接关系已变），由到达首/末页时再次预热。
    private var preloadedNext: PreloadedChapter? = null
    private var preloadedPrevious: PreloadedChapter? = null
    private var preloadNextJob: Job? = null
    private var preloadPreviousJob: Job? = null

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
        chapterTransition = null
        chapterLoadJob = viewModelScope.launch { prepareAndCommitChapter(chapter, index) }
    }

    fun continueToNextChapter() {
        enterChapterTransition(ReaderTransitionDirection.NEXT)
    }

    fun continueToPreviousChapter() {
        enterChapterTransition(ReaderTransitionDirection.PREVIOUS)
    }

    fun onTransitionEntered() {
        val transition = chapterTransition ?: return
        if (transition.status == ReaderTransitionStatus.Loading) {
            loadTransitionChapter(transition.direction)
        }
    }

    fun continueFromTransition(direction: ReaderTransitionDirection) {
        val transition = chapterTransition ?: return
        if (transition.direction != direction) return
        if (transition.status == ReaderTransitionStatus.Ready) {
            commitTransition(transition)
        } else if (transition.status == ReaderTransitionStatus.Error) {
            retryTransition()
        }
    }

    fun returnFromTransition() {
        chapterTransition = null
    }

    fun retryTransition() {
        val transition = chapterTransition ?: return
        if (transition.direction == ReaderTransitionDirection.NEXT ||
            transition.direction == ReaderTransitionDirection.PREVIOUS
        ) {
            chapterTransition = transition.copy(status = ReaderTransitionStatus.Loading)
            loadTransitionChapter(transition.direction)
        }
    }

    private fun enterChapterTransition(direction: ReaderTransitionDirection) {
        if (chapterLoadJob?.isActive == true) return
        val target =
            when (direction) {
                ReaderTransitionDirection.NEXT ->
                    nextReadableOnlineChapter(chapterSummaries, currentChapterIndex)
                ReaderTransitionDirection.PREVIOUS ->
                    previousReadableOnlineChapter(chapterSummaries, currentChapterIndex)
            }
        if (target == null) {
            chapterTransition =
                ReaderChapterTransition(
                    direction = direction,
                    chapterIndex = null,
                    chapterLabel = "",
                    title = "",
                    status = ReaderTransitionStatus.Boundary,
                )
            return
        }
        val (chapter, index) = target
        chapterTransition =
            ReaderChapterTransition(
                direction = direction,
                chapterIndex = index,
                chapterLabel = "第 ${index + 1} 章",
                title = chapter.title.takeIf(String::isNotBlank) ?: "",
                status = ReaderTransitionStatus.Loading,
            )
        loadTransitionChapter(direction)
    }

    private fun loadTransitionChapter(direction: ReaderTransitionDirection) {
        if (transitionLoadJobs[direction]?.isActive == true) return
        val target =
            when (direction) {
                ReaderTransitionDirection.NEXT ->
                    nextReadableOnlineChapter(chapterSummaries, currentChapterIndex)
                ReaderTransitionDirection.PREVIOUS ->
                    previousReadableOnlineChapter(chapterSummaries, currentChapterIndex)
            } ?: return
        val targetIndex = target.second
        val existing =
            if (direction == ReaderTransitionDirection.NEXT) preloadedNext else preloadedPrevious
        if (existing?.index == targetIndex) {
            chapterTransition = chapterTransition?.copy(status = ReaderTransitionStatus.Ready)
            return
        }
        val loadJob =
            viewModelScope.launch {
                try {
                    val preloadJob =
                        if (direction == ReaderTransitionDirection.NEXT) preloadNextJob
                        else preloadPreviousJob
                    preloadJob?.join()
                    val cached =
                        if (direction == ReaderTransitionDirection.NEXT) preloadedNext
                        else preloadedPrevious
                    val prepared =
                        cached?.takeIf { it.index == targetIndex }
                            ?: fetchChapterVolume(target.first).let { (volume, revision) ->
                                PreloadedChapter(volume, targetIndex, target.first, revision)
                            }
                    if (direction == ReaderTransitionDirection.NEXT) {
                        preloadedNext?.takeIf { it.volume !== prepared.volume }?.volume?.close()
                        preloadedNext = prepared
                    } else {
                        preloadedPrevious?.takeIf { it.volume !== prepared.volume }?.volume?.close()
                        preloadedPrevious = prepared
                    }
                    if (chapterTransition?.direction == direction &&
                        chapterTransition?.chapterIndex == targetIndex
                    ) {
                        chapterTransition = chapterTransition?.copy(status = ReaderTransitionStatus.Ready)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (chapterTransition?.direction == direction &&
                        chapterTransition?.chapterIndex == targetIndex
                    ) {
                        chapterTransition = chapterTransition?.copy(status = ReaderTransitionStatus.Error)
                    }
                }
            }
        transitionLoadJobs[direction] = loadJob
        loadJob.invokeOnCompletion {
            if (transitionLoadJobs[direction] === loadJob) {
                transitionLoadJobs.remove(direction)
            }
        }
    }

    private fun commitTransition(transition: ReaderChapterTransition) {
        val targetIndex = transition.chapterIndex ?: return
        val target = chapterSummaries.getOrNull(targetIndex) ?: return
        if (chapterLoadJob?.isActive == true) return
        val cached =
            if (transition.direction == ReaderTransitionDirection.NEXT) preloadedNext
            else preloadedPrevious
        val selected = cached?.takeIf { it.index == targetIndex } ?: return
        if (transition.direction == ReaderTransitionDirection.NEXT) preloadedNext = null
        else preloadedPrevious = null
        chapterLoadJob =
            viewModelScope.launch {
                prepareAndCommitChapter(
                    target,
                    targetIndex,
                    direction =
                        if (transition.direction == ReaderTransitionDirection.NEXT) {
                            ChapterSwitchDirection.NEXT
                        } else {
                            ChapterSwitchDirection.PREVIOUS
                        },
                    prebuilt = selected,
                )
            }
    }

    /** 到达末页时预热下一章，使随后的越界切换无网络等待。无下一章时静默返回。 */
    fun preloadNextChapter() {
        if (preloadedNext != null || preloadNextJob?.isActive == true) return
        val next = nextReadableOnlineChapter(chapterSummaries, currentChapterIndex) ?: return
        preloadNextJob =
            viewModelScope.launch {
                try {
                    val (vol, rev) = fetchChapterVolume(next.first)
                    preloadedNext = PreloadedChapter(vol, next.second, next.first, rev)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 预加载失败保持静默；越界时 continueToNextChapter 会按需重试
                }
            }
    }

    /** 到达首页时预热上一章。无上一章时静默返回。 */
    fun preloadPreviousChapter() {
        if (preloadedPrevious != null || preloadPreviousJob?.isActive == true) return
        val prev = previousReadableOnlineChapter(chapterSummaries, currentChapterIndex) ?: return
        preloadPreviousJob =
            viewModelScope.launch {
                try {
                    val (vol, rev) = fetchChapterVolume(prev.first)
                    preloadedPrevious = PreloadedChapter(vol, prev.second, prev.first, rev)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 预加载失败保持静默；越界时 continueToPreviousChapter 会按需重试
                }
            }
    }

    fun requestThumbnail(page: Int) {
        launchThumbnailJob { loadThumbnail(page) }
    }

    fun saveProgress(source: ComicVolume, page: Int) {
        val opened = volume ?: return
        if (source !== opened) return
        if (page !in 0 until opened.totalPageCount) return
        currentPage = page
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
        if (disposed !== volume) disposed.close()
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
        viewModelScope.launch {
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
            }
        }
    }

    fun consumeReaderMessage() {
        readerMessage = null
    }

    fun endReadingSession() {
        finishSession()
    }

    private fun startChapterLoad() {
        if (chapterLoadJob?.isActive == true) return
        discardPreloadedChapters()
        chapterLoadJob = viewModelScope.launch { loadActiveChapter() }
    }

    private suspend fun loadActiveChapter() {
        state = ReaderPresentationState.Loading
        try {
            val snapshot = withContext(Dispatchers.IO) { repository.get(pluginId, sourceId) }
            updateChapterNavigation(snapshot)
            val response =
                application.pluginCatalog.pages(
                    pluginId,
                    PluginPagesRequest(
                        sourceId = sourceId,
                        chapterId = activeChapterId,
                        revision = activeRequestedRevision,
                        opaqueContext = activeOpaqueContext,
                    ),
                )
            if (response.pages.isEmpty()) throw ComicOpenException("本章节没有可阅读页面")
            val revision =
                resolveOnlineChapterRevision(activeChapterId, activeRequestedRevision, response)
            currentRevision = revision
            val opened =
                OnlineChapterVolume(
                    chapterId = activeChapterId,
                    title = currentChapterTitle,
                    sourceRevision = revision,
                    pages = response.pages,
                    client = application.onlineImageCallFactory,
                )
            volume = opened
            val restored = restorePage(snapshot?.position, opened)
            val startPage = restored.page
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
                    cacheKeyPrefix = cacheKeyPrefix(revision),
                )
            chapterReady = true
            resumeActiveSegmentIfProcessResumed()
            prewarmThumbnails(opened, startPage)
            if (restored.stale) {
                readerMessage = "源内容已变化，已打开最接近的页面"
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            chapterReady = false
            volume?.close()
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
    ) {
        val previousChapterId = activeChapterId
        val previousRevision = currentRevision
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
                ChapterSwitchDirection.PREVIOUS -> null
            }
        try {
            // 命中预加载缓存则直接复用，跳过网络请求以实现无缝切换
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
            val startPage =
                when (direction) {
                    ChapterSwitchDirection.NEXT -> 0
                    // 回到上一章从其末页开始，保持向后阅读方向连续
                    ChapterSwitchDirection.PREVIOUS ->
                        (candidate.totalPageCount - 1).coerceAtLeast(0)
                    ChapterSwitchDirection.MANUAL ->
                        restorePageForChapter(chapter.chapterId, revision, savedPosition, candidate)
                }
            prepared = candidate
            if (activeChapterId != previousChapterId || currentRevision != previousRevision) {
                candidate.close()
                prepared = null
                return
            }

            pauseActiveSegment()
            chapterReady = false
            flushCurrentProgress()
            lastPageByChapterId[activeChapterId] = currentPage
            cancelThumbnailJobs()
            clearChapterPresentation()
            activeChapterId = chapter.chapterId
            activeRequestedRevision = chapter.revision
            activeOpaqueContext = chapter.opaqueContext ?: contentOpaqueContext
            currentChapterTitle = chapter.title.takeIf(String::isNotBlank) ?: chapter.chapterId
            currentChapterIndex = index
            currentRevision = revision
            // 章节切换后邻接关系已变，丢弃旧的预加载缓存（关闭非复用项）
            discardPreloadedChapters(except = candidate)
            volume = candidate
            currentPage = startPage
            lastPageByChapterId[activeChapterId] = startPage
            sessionLatestLocation = locationFor(startPage)
            state =
                ReaderPresentationState.Ready(
                    volume = candidate,
                    startPage = startPage,
                    title = currentTitle,
                    cacheKeyPrefix = cacheKeyPrefix(revision),
                )
            chapterTransition = null
            committed = true
            chapterReady = true
            resumeActiveSegmentIfProcessResumed()
            prewarmThumbnails(candidate, startPage)
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
                chapterTransition =
                    chapterTransition?.copy(status = ReaderTransitionStatus.Error)
            } else {
                readerMessage =
                    error.message?.let { "无法切换章节：$it" } ?: "无法切换章节"
            }
        } finally {
            if (!committed) prepared?.close()
        }
    }

    /** 拉取单章节页面并构建 Volume，供直接加载与预加载共用。 */
    private suspend fun fetchChapterVolume(
        chapter: ChapterSummary
    ): Pair<OnlineChapterVolume, String> {
        val response =
            application.pluginCatalog.pages(
                pluginId,
                PluginPagesRequest(
                    sourceId = sourceId,
                    chapterId = chapter.chapterId,
                    revision = chapter.revision,
                    opaqueContext = chapter.opaqueContext ?: contentOpaqueContext,
                ),
            )
        if (response.pages.isEmpty()) throw ComicOpenException("目标章节没有可阅读页面")
        val revision = resolveOnlineChapterRevision(chapter.chapterId, chapter.revision, response)
        val volume =
            OnlineChapterVolume(
                chapterId = chapter.chapterId,
                title = chapter.title.takeIf(String::isNotBlank) ?: chapter.chapterId,
                sourceRevision = revision,
                pages = response.pages,
                client = application.onlineImageCallFactory,
            )
        return volume to revision
    }

    /** 章节切换后丢弃预加载缓存；[except] 指向本次将激活的 Volume 时不关闭（避免关闭正要使用的资源）。 */
    private fun discardPreloadedChapters(except: OnlineChapterVolume? = null) {
        transitionLoadJobs.values.toList().forEach { it.cancel() }
        transitionLoadJobs.clear()
        val next = preloadedNext
        if (next != null && next.volume !== except) next.volume.close()
        val prev = preloadedPrevious
        if (prev != null && prev.volume !== except) prev.volume.close()
        preloadedNext = null
        preloadedPrevious = null
        preloadNextJob?.cancel()
        preloadPreviousJob?.cancel()
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
        bookmarkPages.clear()
        bookmarks.clear()
        favoritePages.clear()
        bookmarkEntriesByKey = emptyMap()
        // 注意：不在此清空 readerMessage。章节切换时 prepareAndCommitChapter 会先设
        // “正在进入下一章/上一章”、切换完成后清空；若这里也清空会导致消息刚设即被
        // 覆盖，在 UI 上表现为 Snackbar 闪烁消失。消息生命周期由切换流程统一管理。
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
        resolvedBookmarks
            .filterNot { it.second.stale }
            .forEach { bookmarkPages[it.second.globalPage] = Unit }

        val chapterFavorites =
            record?.pageFavorites.orEmpty().filter {
                it.location.identity.chapter == chapterIdentity
            }
        favoritePages.clear()
        chapterFavorites.forEach { favorite ->
            val resolution = resolvePage(favorite.location, opened)
            if (!resolution.stale) favoritePages[resolution.page] = Unit
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
        try {
            val namespace = cacheKeyPrefix(opened.sourceRevision)
            val pageIdentity = opened.pageIdentity(page)
            val cached =
                ReaderCache.readOnlineThumbnail(
                    application,
                    namespace,
                    page,
                    pageIdentity,
                )
            if (cached != null) {
                thumbnails[page] = cached.asImageBitmap()
                return
            }
            val rendered =
                withContext(Dispatchers.IO) {
                    opened.renderThumbnail(page, THUMB_TARGET_WIDTH)
                } ?: return
            thumbnails[page] = rendered
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
        }
    }

    private fun prewarmThumbnails(opened: OnlineChapterVolume, startPage: Int) {
        val pages = thumbnailPrewarmOrder(startPage, opened.totalPageCount, THUMB_PREWARM_RADIUS)
        launchThumbnailJob {
            for (page in pages) loadThumbnail(page)
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

    private fun cacheKeyPrefix(revision: String): String {
        val value = "$pluginId\u0000$sourceId\u0000$activeChapterId\u0000$revision"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val key =
            digest.take(12).joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
        return "online-$key"
    }

    private fun bookmarkKey(identity: OnlinePageIdentity): String =
        "online:${PluginContentCodec.json.encodeToString(identity)}"

    override fun onCleared() {
        finishSession()
        discardPreloadedChapters()
        volume?.close()
        volume = null
    }

    private data class ResolvedOnlinePage(val page: Int, val stale: Boolean)

    private data class RestoredOnlinePage(val page: Int, val stale: Boolean)

    private enum class ChapterSwitchDirection { MANUAL, NEXT, PREVIOUS }

    /** 预加载缓存项：已构建的 Volume 与其修订号，供越界切换时直接复用。 */
    private class PreloadedChapter(
        val volume: OnlineChapterVolume,
        val index: Int,
        val chapter: ChapterSummary,
        val revision: String,
    )

    private companion object {
        const val THUMB_TARGET_WIDTH = 168
        const val THUMB_PREWARM_RADIUS = 2
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
