package com.exio.inkleaf.ui

import android.app.Application
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
import com.exio.inkleaf.data.BookmarkRepository
import com.exio.inkleaf.data.BookmarkToggleResult
import com.exio.inkleaf.data.ChapterProgress
import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.ComicVolume
import com.exio.inkleaf.data.FavoriteRepository
import com.exio.inkleaf.data.ReaderCache
import com.exio.inkleaf.data.ReadingPositionSnapshot
import com.exio.inkleaf.data.ReadingSessionComicRef
import com.exio.inkleaf.data.ReadingSessionEndReason
import com.exio.inkleaf.data.ReadingSessionEvent
import com.exio.inkleaf.data.ReadingSessionRepository
import com.exio.inkleaf.data.ReadingSessionRules
import com.exio.inkleaf.data.db.BookSourceType
import com.exio.inkleaf.data.db.BookmarkEntity
import com.exio.inkleaf.data.db.ComicEntity
import com.exio.inkleaf.data.db.FavoritePageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 阅读页状态机：打开中 / 失败 / 就绪（带恢复的起始页码） */
sealed interface ReaderUiState {
    data object Loading : ReaderUiState
    data class Error(val message: String) : ReaderUiState
    data class Ready(
        val volume: ComicVolume,
        val startPage: Int,
        val title: String,
    ) : ReaderUiState
}
/**
 * 阅读页的状态与资源持有者。
 *
 * 为什么引入 ViewModel：它存活于 Activity 重建（旋转屏幕、深色模式切换等
 * "配置变更"）之外。之前 ComicBook 由 Composable 持有，旋转一次就要
 * 重新复制整个 zip；现在搬进 ViewModel，旋转时 state 原封不动，
 * 界面瞬间恢复。
 */
class ReaderViewModel(
    app: Application,
    private val comicId: Long,
    private val initialPageOverride: Int? = null,
) : AndroidViewModel(app) {
    private val repo = ComicRepository(app)
    private val bookmarkRepo = BookmarkRepository(app)
    private val favoriteRepo = FavoriteRepository(app)
    private val sessionRepo = ReadingSessionRepository.getInstance(app)

    var state by mutableStateOf<ReaderUiState>(ReaderUiState.Loading)
        private set

    /**
     * 胶片条缩略图缓存（页码 → 已解码的迷你位图）。
     *
     * 放在 ViewModel 而不是 UI 层的三个理由：
     * 1. 旋转屏幕缓存不丢（与 book 同生命周期）；
     * 2. 预热协程由 viewModelScope 托管，退出阅读页自动取消；
     * 3. 预热与按需加载在同一处去重，不会重复解码。
     *
     * 占用账：RGB_565 下每张约 80KB，400 页约 32MB，可接受；
     * 超过 PREWARM_MAX_PAGES 的书不做全量预热（见 prewarmThumbnails）。
     */
    val thumbnails = mutableStateMapOf<Int, ImageBitmap>()
    val bookmarkPages = mutableStateMapOf<Int, BookmarkEntity>()
    internal val resolvedBookmarks = mutableStateListOf<ResolvedReaderBookmark>()
    val staleBookmarkIds = mutableStateMapOf<Long, Unit>()
    val favoritePages = mutableStateMapOf<Int, FavoritePageEntity>()

    var readerMessage by mutableStateOf<String?>(null)
        private set

    var currentPage by mutableIntStateOf(0)
        private set

    /** 正在加载中的页码集合，配合 Mutex 实现去重 */
    private val thumbInFlight = mutableSetOf<Int>()
    private val thumbMutex = Mutex()
    private val bookmarkInFlight = mutableSetOf<Int>()
    private val bookmarkMutationMutex = Mutex()
    private val favoriteInFlight = mutableSetOf<Int>()
    private var coverInFlight = false

    /** 待落库的最新阅读进度；null = 没有待写的进度（见 saveProgress 的节流说明） */
    private var pendingProgress: ChapterProgress? = null
    private var progressWriteJob: Job? = null

    private var volume: ComicVolume? = null
    private var comic: ComicEntity? = null
    private var observedFavoriteSource: String? = null

    /** True after LeaveReader was dispatched; prevents double-complete on dispose. */
    private var sessionEnded = false
    private var checkpointJob: Job? = null
    private var processLifecycleAttached = false

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            dispatchSessionEvent(ReadingSessionEvent.EnteredInteractiveForeground)
            startCheckpointLoop()
        }

        override fun onPause(owner: LifecycleOwner) {
            stopCheckpointLoop()
            dispatchSessionEvent(ReadingSessionEvent.LeftInteractiveForeground)
        }
    }

    init {
        viewModelScope.launch {
            state = try {
                val comic = repo.getComic(comicId)
                    ?: throw ComicOpenException("书架记录不存在")
                this@ReaderViewModel.comic = comic
                // Identify the comic before open; source revision arrives on Ready.
                dispatchSessionEvent(
                    ReadingSessionEvent.OpenComic(
                        ReadingSessionComicRef(
                            fileKey = comic.fileKey,
                            titleSnapshot = comic.title.ifBlank { "未命名漫画" },
                            sourceType = comic.sourceType,
                        ),
                    ),
                )
                observeFavorites(comic.fileKey)
                val opened = withContext(Dispatchers.IO) { repo.openBook(comic) }
                volume = opened
                observeBookmarks(opened)
                // 首次打开回填页数和封面；Room Flow 会自动刷新书架。
                // openBook 内部 PdfComicVolume 的 PDF 解析走 IO，这里包一层
                // withContext 兜底，避免某些路径在主线程做 native 打开。
                withContext(Dispatchers.IO) { repo.backfillMetadata(comic, opened) }
                // 全章都打不开（损坏/加密/权限全失效）→ 给清晰提示而非崩在
                // coerceIn(0, -1)。spec：corrupt/encrypted PDF 不崩溃。
                if (opened.totalPageCount <= 0) {
                    opened.close()
                    throw ComicOpenException("无法打开任何章节，PDF 文件可能已损坏或加密")
                }
                val startPage = initialPageOverride ?: opened.chapterPageToGlobal(
                    comic.lastReadChapterIndex,
                    comic.lastReadPage,
                )
                val safeStartPage = startPage.coerceIn(0, opened.totalPageCount - 1)
                currentPage = safeStartPage
                dispatchSessionEvent(
                    ReadingSessionEvent.ReaderReady(positionSnapshot(opened, safeStartPage)),
                )
                attachProcessLifecycle()
                // 后台预热胶片缩略图：呼出工具栏时大概率已全部就绪
                prewarmThumbnails(opened, safeStartPage)
                ReaderUiState.Ready(
                    volume = opened,
                    // 原文件可能被换成页数更少的版本，夹紧防止越界
                    startPage = safeStartPage,
                    title = comic.title,
                )
            } catch (e: ComicOpenException) {
                ReaderUiState.Error(e.message ?: "打开失败")
            }
        }
    }

    /**
     * 进度写库按 trailing 节流：快速连翻/拖滑杆跳页时不逐页写——每次写库
     * 都会让书架侧仍在订阅的 Room Flow 在后台重查一轮。窗口内只记最新进度，
     * 到期写一次；退出阅读页取消协程时由 finally + NonCancellable
     * 保证最后的进度必然落库。
     *
     * UI 仍使用全局页码；内部按 (章节, 页) 落库。
     */
    fun saveProgress(globalPage: Int) {
        currentPage = globalPage
        val opened = volume
        if (opened != null && globalPage in 0 until opened.totalPageCount) {
            dispatchSessionEvent(
                ReadingSessionEvent.PageVisible(positionSnapshot(opened, globalPage)),
            )
        }
        val progress = opened?.globalToChapterPage(globalPage) ?: ChapterProgress(0, globalPage)
        pendingProgress = progress
        if (progressWriteJob?.isActive == true) return
        progressWriteJob = viewModelScope.launch {
            try {
                while (true) {
                    delay(PROGRESS_WRITE_INTERVAL_MS.milliseconds)
                    val latest = pendingProgress ?: break
                    pendingProgress = null
                    withContext(NonCancellable) {
                        repo.saveProgress(
                            comicId,
                            comic?.sourceType ?: BookSourceType.EXTERNAL_ARCHIVE,
                            latest.chapterIndex,
                            latest.pageIndex,
                        )
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    pendingProgress?.let { latest ->
                        pendingProgress = null
                        repo.saveProgress(
                            comicId,
                            comic?.sourceType ?: BookSourceType.EXTERNAL_ARCHIVE,
                            latest.chapterIndex,
                            latest.pageIndex,
                        )
                    }
                }
            }
        }
    }

    /**
     * Explicit Reader leave (toolbar Back / system Back). Idempotent so
     * BackHandler + dispose cannot complete the same session twice.
     */
    fun endReadingSession(
        reason: ReadingSessionEndReason = ReadingSessionEndReason.LEFT_READER,
    ) {
        if (sessionEnded) return
        sessionEnded = true
        stopCheckpointLoop()
        detachProcessLifecycle()
        viewModelScope.launch(NonCancellable) {
            sessionRepo.dispatch(ReadingSessionEvent.LeaveReader(reason))
        }
    }

    fun toggleFavorite(page: Int) {
        if (page in favoriteInFlight) return
        val opened = volume ?: return
        val source = comic ?: return
        if (page !in 0 until opened.totalPageCount) return

        viewModelScope.launch {
            favoriteInFlight += page
            try {
                val existing = favoritePages[page]
                if (existing != null) {
                    favoriteRepo.remove(existing)
                    readerMessage = "已取消收藏"
                } else {
                    val bytes = opened.loadPageBytes(page)
                    favoriteRepo.addSnapshot(source, page, opened.totalPageCount, bytes)
                    readerMessage = "已收藏"
                }
            } catch (e: Exception) {
                readerMessage = e.message?.let { "收藏失败：$it" } ?: "收藏失败"
            } finally {
                favoriteInFlight -= page
            }
        }
    }

    fun toggleBookmark(page: Int) {
        if (!bookmarkInFlight.add(page)) return
        val opened = volume
        val source = comic
        if (opened == null || source == null || page !in 0 until opened.totalPageCount) {
            bookmarkInFlight -= page
            return
        }

        viewModelScope.launch {
            try {
                val result = bookmarkMutationMutex.withLock {
                    bookmarkRepo.toggle(source, opened, page)
                }
                when (result) {
                    is BookmarkToggleResult.Added -> {
                        loadThumbnail(page)
                        readerMessage = "已添加书签"
                    }

                    is BookmarkToggleResult.Removed -> {
                        readerMessage = "已移除书签"
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                readerMessage = e.message?.let { "书签操作失败：$it" } ?: "书签操作失败"
            } finally {
                bookmarkInFlight -= page
            }
        }
    }

    suspend fun removeBookmark(bookmark: BookmarkEntity) {
        bookmarkMutationMutex.withLock {
            bookmarkRepo.remove(bookmark)
        }
    }

    suspend fun restoreBookmark(bookmark: BookmarkEntity) {
        bookmarkMutationMutex.withLock {
            bookmarkRepo.restore(bookmark)
        }
    }

    fun setCurrentPageAsCover(page: Int) {
        if (coverInFlight) return
        val opened = volume ?: return
        if (page !in 0 until opened.totalPageCount) return

        coverInFlight = true
        viewModelScope.launch {
            try {
                repo.setCoverFromPage(comicId, opened, page)
                readerMessage = "已设为封面"
            } catch (e: Exception) {
                readerMessage = e.message?.let { "设置封面失败：$it" } ?: "设置封面失败"
            } finally {
                coverInFlight = false
            }
        }
    }

    fun consumeReaderMessage() {
        readerMessage = null
    }

    /** 胶片格子按需请求缩略图：缓存已有或正在加载则直接返回（去重） */
    fun requestThumbnail(page: Int) {
        viewModelScope.launch { loadThumbnail(page) }
    }

    /**
     * 后台预热：从当前页向后到结尾、再从当前页向前到开头。
     * 顺序串行——loadThumbnail 逐个 suspend 完成，天然不会挤爆 IO；
     * 用户滑到未预热区域时 requestThumbnail 并发插队，zip 锁自动排队。
     */
    private fun prewarmThumbnails(volume: ComicVolume, startPage: Int) {
        // 超大书全量预热会吃掉过多内存（每张约 80KB），只走按需加载。
        // 这是个有意的覆盖上限：手动浏览仍然能加载任何页
        if (volume.totalPageCount > PREWARM_MAX_PAGES) return
        viewModelScope.launch {
            for (page in startPage until volume.totalPageCount) loadThumbnail(page)
            for (page in startPage - 1 downTo 0) loadThumbnail(page)
        }
    }

    private fun observeFavorites(sourceFileKey: String) {
        if (observedFavoriteSource == sourceFileKey) return
        observedFavoriteSource = sourceFileKey
        viewModelScope.launch {
            favoriteRepo.observeForSource(sourceFileKey).collect { favorites ->
                favoritePages.clear()
                favorites.forEach { favoritePages[it.pageIndex] = it }
            }
        }
    }

    private fun observeBookmarks(opened: ComicVolume) {
        viewModelScope.launch {
            bookmarkRepo.observeForComic(comicId).collect { bookmarks ->
                refreshBookmarkPages(opened, bookmarks)
            }
        }
    }

    private suspend fun refreshBookmarkPages(
        opened: ComicVolume,
        bookmarks: List<BookmarkEntity>,
    ) {
        val resolutions = withContext(Dispatchers.IO) {
            bookmarks.mapNotNull { bookmark ->
                resolveBookmarkPage(opened, bookmark)?.let { resolution ->
                    ResolvedReaderBookmark(
                        bookmark = bookmark,
                        globalPage = resolution.globalPage,
                        stale = resolution.stale,
                    )
                }
            }
        }

        bookmarkPages.clear()
        resolvedBookmarks.clear()
        staleBookmarkIds.clear()
        resolvedBookmarks.addAll(resolutions.sortedBy { it.globalPage })
        resolvedBookmarks.forEach { item ->
            if (item.stale) {
                staleBookmarkIds[item.bookmark.id] = Unit
            } else {
                bookmarkPages[item.globalPage] = item.bookmark
            }
        }
    }

    private fun resolveBookmarkPage(
        opened: ComicVolume,
        bookmark: BookmarkEntity,
    ): ReaderBookmarkResolution? {
        if (opened.totalPageCount <= 0) return null
        val approximatePage = bookmark.globalPageIndex.coerceIn(0, opened.totalPageCount - 1)
        if (bookmark.sourceRevision == opened.sourceRevision) {
            return ReaderBookmarkResolution(approximatePage, stale = false)
        }
        val remappedPage = bookmark.pageIdentity
            ?.takeIf { it.isNotBlank() }
            ?.let(opened::findPageByIdentity)
            ?.takeIf { it in 0 until opened.totalPageCount }
        val sourceType = comic?.sourceType
        return ReaderBookmarkResolution(
            globalPage = remappedPage ?: approximatePage,
            stale = sourceType == BookSourceType.PDF_SERIES || remappedPage == null,
        )
    }

    private suspend fun loadThumbnail(page: Int) {
        val opened = volume ?: return
        if (page !in 0 until opened.totalPageCount) return
        thumbMutex.withLock {
            if (thumbnails.containsKey(page) || page in thumbInFlight) return
            thumbInFlight += page
        }
        try {
            val app = getApplication<Application>()
            val pageIdentity = opened.pageIdentity(page)

            // 一级：磁盘缓存（上次开书时落盘的小 JPEG，读取+解码不到 1ms 级）
            val fromDisk = ReaderCache.readThumbnail(app, comicId, page, pageIdentity)
            if (fromDisk != null) {
                thumbnails[page] = fromDisk.asImageBitmap()
                return
            }

            // Let each volume choose its cheapest thumbnail path. Albums can
            // sample local files directly without loading full images into byte arrays.
            val rendered = opened.renderThumbnail(page, THUMB_TARGET_WIDTH) ?: return
            thumbnails[page] = rendered
            ReaderCache.writeThumbnail(
                app,
                comicId,
                page,
                pageIdentity,
                rendered.asAndroidBitmap(),
            )
        } catch (_: Exception) {
            // 单页缩略图失败只影响胶片上一个格子，静默跳过；
            // 不缓存失败结果，下次该格子可见时会自动重试
        } finally {
            thumbMutex.withLock { thumbInFlight -= page }
        }
    }

    /** Error 态的出口：从书架移除这条打不开的记录，完成后回调返回书架 */
    fun removeFromShelf(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.getComic(comicId)?.let { repo.deleteComic(it) }
            onDone()
        }
    }

    /**
     * ViewModel 真正销毁时调用（返回书架弹出导航栈、或 App 退出）。
     * 注意旋转屏幕不会走到这里——这正是资源不被重复释放/创建的关键。
     */
    override fun onCleared() {
        stopCheckpointLoop()
        detachProcessLifecycle()
        // Unexpected disposal pauses the reading session; explicit Back already completed it.
        if (!sessionEnded) {
            volumeCleanupScope.launch(NonCancellable) {
                sessionRepo.dispatch(ReadingSessionEvent.LeftInteractiveForeground)
            }
        }
        val closingVolume = volume
        volume = null
        volumeCleanupScope.launch {
            closingVolume?.close()
        }
    }

    private fun attachProcessLifecycle() {
        if (processLifecycleAttached || sessionEnded) return
        processLifecycleAttached = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        // ProcessLifecycleOwner replays current state, but default machine
        // foreground is false — still emit enter when already resumed.
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.RESUMED,
            )
        ) {
            dispatchSessionEvent(ReadingSessionEvent.EnteredInteractiveForeground)
            startCheckpointLoop()
        }
    }

    private fun detachProcessLifecycle() {
        if (!processLifecycleAttached) return
        processLifecycleAttached = false
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
    }

    private fun startCheckpointLoop() {
        if (checkpointJob?.isActive == true || sessionEnded) return
        checkpointJob = viewModelScope.launch {
            while (true) {
                delay(ReadingSessionRules.CHECKPOINT_INTERVAL_MS.milliseconds)
                if (sessionEnded) break
                sessionRepo.dispatch(ReadingSessionEvent.CheckpointTick)
            }
        }
    }

    private fun stopCheckpointLoop() {
        checkpointJob?.cancel()
        checkpointJob = null
    }

    private fun dispatchSessionEvent(event: ReadingSessionEvent) {
        if (sessionEnded && event !is ReadingSessionEvent.LeaveReader) return
        viewModelScope.launch {
            try {
                sessionRepo.dispatch(event)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Session tracking must not break reading.
            }
        }
    }

    private fun positionSnapshot(
        opened: ComicVolume,
        globalPage: Int,
    ): ReadingPositionSnapshot {
        val page = globalPage.coerceIn(0, (opened.totalPageCount - 1).coerceAtLeast(0))
        val location = opened.globalToChapterPage(page)
        return ReadingPositionSnapshot(
            pageIdentity = opened.pageIdentity(page),
            globalPageIndex = page,
            chapterIndex = location.chapterIndex,
            pageIndex = location.pageIndex,
            chapterTitle = opened.chapterTitle(location.chapterIndex),
            sourceRevision = opened.sourceRevision,
        )
    }

    companion object {
        private val volumeCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 缩略图解码目标宽度（px）：56dp 格子在 3x 屏上约 168px */
        private const val THUMB_TARGET_WIDTH = 168

        /** 全量预热的页数上限，超过则只按需加载（内存保险） */
        private const val PREWARM_MAX_PAGES = 400

        /** 进度写库的节流窗口 */
        private const val PROGRESS_WRITE_INTERVAL_MS = 500L
    }
}

private data class ReaderBookmarkResolution(
    val globalPage: Int,
    val stale: Boolean,
)

internal data class ResolvedReaderBookmark(
    val bookmark: BookmarkEntity,
    val globalPage: Int,
    val stale: Boolean,
)
