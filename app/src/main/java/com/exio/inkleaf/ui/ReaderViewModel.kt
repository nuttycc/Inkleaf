package com.exio.inkleaf.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.data.ComicBook
import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.Covers
import com.exio.inkleaf.data.FavoriteRepository
import com.exio.inkleaf.data.ReaderCache
import com.exio.inkleaf.data.db.ComicEntity
import com.exio.inkleaf.data.db.FavoritePageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
        val book: ComicBook,
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
    private val favoriteRepo = FavoriteRepository(app)

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
    val favoritePages = mutableStateMapOf<Int, FavoritePageEntity>()

    var readerMessage by mutableStateOf<String?>(null)
        private set

    /** 正在加载中的页码集合，配合 Mutex 实现去重 */
    private val thumbInFlight = mutableSetOf<Int>()
    private val thumbMutex = Mutex()
    private val favoriteInFlight = mutableSetOf<Int>()
    private var coverInFlight = false

    /** 待落库的最新阅读页；null = 没有待写的进度（见 saveProgress 的节流说明） */
    private var pendingProgressPage: Int? = null
    private var progressWriteJob: Job? = null

    private var book: ComicBook? = null
    private var comic: ComicEntity? = null
    private var observedFavoriteSource: String? = null

    init {
        viewModelScope.launch {
            state = try {
                val comic = repo.getComic(comicId)
                    ?: throw ComicOpenException("书架记录不存在")
                this@ReaderViewModel.comic = comic
                observeFavorites(comic.fileKey)
                val opened = repo.openBook(comic)
                book = opened
                // 首次打开回填页数和封面；Room Flow 会自动刷新书架
                repo.backfillMetadata(comic, opened)
                val startPage = (initialPageOverride ?: comic.lastReadPage)
                    .coerceIn(0, opened.pageCount - 1)
                // 后台预热胶片缩略图：呼出工具栏时大概率已全部就绪
                prewarmThumbnails(opened, startPage)
                ReaderUiState.Ready(
                    book = opened,
                    // 原文件可能被换成页数更少的版本，夹紧防止越界
                    startPage = startPage,
                    title = comic.title,
                )
            } catch (e: ComicOpenException) {
                ReaderUiState.Error(e.message ?: "打开失败")
            }
        }
    }

    /**
     * 进度写库按 trailing 节流：快速连翻/拖滑杆跳页时不逐页写——每次写库
     * 都会让书架侧仍在订阅的 Room Flow 在后台重查一轮。窗口内只记最新页，
     * 到期写一次；退出阅读页取消协程时由 finally + NonCancellable
     * 保证最后的进度必然落库。
     */
    fun saveProgress(page: Int) {
        pendingProgressPage = page
        if (progressWriteJob?.isActive == true) return
        progressWriteJob = viewModelScope.launch {
            try {
                while (true) {
                    delay(PROGRESS_WRITE_INTERVAL_MS)
                    val latest = pendingProgressPage ?: break
                    pendingProgressPage = null
                    withContext(NonCancellable) { repo.saveProgress(comicId, latest) }
                }
            } finally {
                withContext(NonCancellable) {
                    pendingProgressPage?.let { latest ->
                        pendingProgressPage = null
                        repo.saveProgress(comicId, latest)
                    }
                }
            }
        }
    }

    fun toggleFavorite(page: Int) {
        if (page in favoriteInFlight) return
        val opened = book ?: return
        val source = comic ?: return
        if (page !in 0 until opened.pageCount) return

        viewModelScope.launch {
            favoriteInFlight += page
            try {
                val existing = favoritePages[page]
                if (existing != null) {
                    favoriteRepo.remove(existing)
                    readerMessage = "已取消收藏"
                } else {
                    val bytes = opened.loadPageBytes(page)
                    favoriteRepo.addSnapshot(source, page, opened.pageCount, bytes)
                    readerMessage = "已收藏"
                }
            } catch (e: Exception) {
                readerMessage = e.message?.let { "收藏失败：$it" } ?: "收藏失败"
            } finally {
                favoriteInFlight -= page
            }
        }
    }

    fun setCurrentPageAsCover(page: Int) {
        if (coverInFlight) return
        val opened = book ?: return
        if (page !in 0 until opened.pageCount) return

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
    private fun prewarmThumbnails(book: ComicBook, startPage: Int) {
        // 超大书全量预热会吃掉过多内存（每张约 80KB），只走按需加载。
        // 这是个有意的覆盖上限：手动浏览仍然能加载任何页
        if (book.pageCount > PREWARM_MAX_PAGES) return
        viewModelScope.launch {
            for (page in startPage until book.pageCount) loadThumbnail(page)
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

    private suspend fun loadThumbnail(page: Int) {
        val opened = book ?: return
        if (page !in 0 until opened.pageCount) return
        thumbMutex.withLock {
            if (thumbnails.containsKey(page) || page in thumbInFlight) return
            thumbInFlight += page
        }
        try {
            val app = getApplication<Application>()

            // 一级：磁盘缓存（上次开书时落盘的小 JPEG，读取+解码不到 1ms 级）
            val fromDisk = ReaderCache.readThumbnail(app, comicId, page)
            if (fromDisk != null) {
                thumbnails[page] = fromDisk.asImageBitmap()
                return
            }

            // 二级：从 zip 解压原图并降采样解码，然后落盘供下次开书复用。
            // RGB_565 每像素 2 字节，缩略图场景画质无损感、内存减半
            val bytes = opened.loadThumbnailPageBytes(page)
            val decoded = withContext(Dispatchers.Default) {
                Covers.decodeSampled(bytes, THUMB_TARGET_WIDTH, Bitmap.Config.RGB_565)
            } ?: return
            thumbnails[page] = decoded.asImageBitmap()
            ReaderCache.writeThumbnail(app, comicId, page, decoded)
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
        book?.close()
    }

    companion object {
        /** 缩略图解码目标宽度（px）：56dp 格子在 3x 屏上约 168px */
        private const val THUMB_TARGET_WIDTH = 168

        /** 全量预热的页数上限，超过则只按需加载（内存保险） */
        private const val PREWARM_MAX_PAGES = 400

        /** 进度写库的节流窗口 */
        private const val PROGRESS_WRITE_INTERVAL_MS = 500L
    }
}
