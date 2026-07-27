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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.OnlineChapterIdentity
import com.exio.inkleaf.data.OnlineContentIdentity
import com.exio.inkleaf.data.OnlinePageIdentity
import com.exio.inkleaf.data.OnlinePageLocation
import com.exio.inkleaf.data.ReadingSessionRules
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
    private val lastPageByChapterId = mutableMapOf<String, Int>()

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
        chapterLoadJob = viewModelScope.launch {
            prepareChapterSwitch(chapter, index)
            loadActiveChapter()
        }
    }

    fun requestThumbnail(page: Int) {
        launchThumbnailJob { loadThumbnail(page) }
    }

    fun saveProgress(page: Int) {
        val opened = volume ?: return
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

    private suspend fun prepareChapterSwitch(chapter: ChapterSummary, index: Int) {
        pauseActiveSegment()
        chapterReady = false
        flushCurrentProgress()
        lastPageByChapterId[activeChapterId] = currentPage
        cancelThumbnailJobs()
        volume?.close()
        volume = null
        clearChapterPresentation()
        activeChapterId = chapter.chapterId
        activeRequestedRevision = chapter.revision
        activeOpaqueContext = chapter.opaqueContext
        currentChapterTitle = chapter.title.takeIf(String::isNotBlank) ?: chapter.chapterId
        currentChapterIndex = index
        currentRevision = chapter.revision
        state = ReaderPresentationState.Loading
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
        readerMessage = null
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
            withContext(Dispatchers.IO) {
                    opened.renderThumbnail(page, THUMB_TARGET_WIDTH)
                }
                ?.let { thumbnails[page] = it }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A failed thumbnail remains retryable and never blocks the full-size page.
        } finally {
            thumbnailMutex.withLock { thumbnailInFlight -= page }
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
        volume?.close()
        volume = null
    }

    private data class ResolvedOnlinePage(val page: Int, val stale: Boolean)

    private data class RestoredOnlinePage(val page: Int, val stale: Boolean)

    private companion object {
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
