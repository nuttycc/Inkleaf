package com.exio.inkleaf.data

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.ahmer.pdfium.PdfiumCore
import com.exio.inkleaf.data.PdfComicVolume.Companion.pdfiumLock
import com.exio.inkleaf.data.db.ChapterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

private const val OCR_PDF_RENDER_SCALE = 4.0

/**
 * 把包含多个 PDF 文件的目录作为一本书来阅读。
 *
 * 每个 PDF 对应一个章节，按文件名自然排序，阅读时跨章节连续翻页。
 * 每个章节使用独立的 [PdfiumCore] 实例，避免单核多文档状态切换的复杂度。
 */
class PdfComicVolume(
    context: Context,
    private val chapters: List<ChapterEntity>,
    private val pfdResolver: (String) -> ParcelFileDescriptor?,
) : ComicVolume {

    private val appContext = context.applicationContext

    private var closed = false

    /** Access-order cache bounds native documents independently of the chapter count. */
    private val openChapters = LinkedHashMap<Int, OpenChapter>(OPEN_CHAPTER_LIMIT, 0.75f, true)

    /**
     * Atomically published chapter metadata. Native PDF work stays behind [pdfiumLock], while
     * Compose can read an immutable layout without waiting for an unrelated page render.
     */
    @Volatile
    private var chapterLayout = PdfChapterLayout(
        IntArray(chapters.size) { index ->
            if (chapters[index].isMissing) {
                0
            } else {
                chapters[index].pageCount.takeIf { it > 0 } ?: UNKNOWN_PAGE_COUNT
            }
        },
    )

    init {
        require(chapters.isNotEmpty()) { "PdfComicVolume 至少需要一章" }
    }

    override val chapterCount: Int get() = chapters.size

    override val sourceRevision: String = ReaderPageCacheKey.sourceRevision(
        buildList {
            add("pdf-series")
            chapters.forEach { chapter ->
                add(chapter.fileKey)
                add(chapter.uri)
                add((chapter.size ?: -1L).toString())
                add((chapter.lastModified ?: -1L).toString())
                add(chapter.isMissing.toString())
            }
        }
    )

    override val totalPageCount: Int
        get() = resolvedChapterLayout().totalPageCount

    override fun chapterTitle(chapterIndex: Int): String =
        chapters.getOrNull(chapterIndex)?.title ?: ""

    override fun chapterStartPage(chapterIndex: Int): Int {
        val layout = resolvedChapterLayout()
        return layout.startPages.getOrElse(chapterIndex) { layout.startPages.lastOrNull() ?: 0 }
    }

    override fun chapterPageCount(chapterIndex: Int): Int {
        if (chapterIndex !in chapters.indices) return 0
        val cached = chapterLayout.pageCounts[chapterIndex]
        if (cached >= 0) return cached
        return pdfiumLock.withLock {
            val current = chapterLayout.pageCounts[chapterIndex]
            if (current >= 0) {
                current
            } else {
                val discovered = discoverPageCountLocked(chapterIndex)
                publishPageCountLocked(chapterIndex, discovered)
                discovered
            }
        }
    }

    override fun isChapterReadable(chapterIndex: Int): Boolean {
        if (chapterIndex !in chapters.indices || chapters[chapterIndex].isMissing) return false
        return pdfiumLock.withLock {
            if (closed) return@withLock false
            val opened = openDocumentLocked(chapterIndex) ?: run {
                publishPageCountLocked(chapterIndex, 0)
                return@withLock false
            }
            // A stored positive page count may belong to an older file revision; probe the
            // current document before exposing the chapter as selectable.
            val actualPageCount = runCatching { opened.document.totalPages }
                .getOrNull()
                ?.coerceAtLeast(0)
            if (actualPageCount == null) {
                discardOpenChapterLocked(chapterIndex)
                publishPageCountLocked(chapterIndex, 0)
                return@withLock false
            }
            publishPageCountLocked(chapterIndex, actualPageCount)
            actualPageCount > 0
        }
    }

    override fun globalToChapterPage(globalPage: Int): ChapterProgress {
        // Cover generation may ask for page zero before the complete layout is known. Avoid
        // opening every chapter when the first chapter count already answers that mapping.
        if (!chapterLayout.isResolved && chapterCount > 0 && globalPage >= 0) {
            val firstPages = chapterPageCount(0)
            if (firstPages > 0 && globalPage < firstPages) {
                return ChapterProgress(0, globalPage)
            }
        }
        val layout = resolvedChapterLayout()
        if (layout.startPages.isEmpty()) return ChapterProgress(0, 0)
        val chapter = (0 until chapterCount)
            .lastOrNull { layout.startPages[it] <= globalPage }
            ?: 0
        val pages = layout.pageCounts.getOrElse(chapter) { 0 }.coerceAtLeast(0)
        // An unreadable chapter has zero pages. Keep its local index at zero so the render path
        // can surface the existing chapter-open error without producing a negative page index.
        val pageInChapter = if (pages <= 0) 0
            else (globalPage - layout.startPages[chapter]).coerceIn(0, pages - 1)
        return ChapterProgress(chapter, pageInChapter)
    }

    override fun chapterPageToGlobal(chapterIndex: Int, pageIndex: Int): Int {
        val layout = resolvedChapterLayout()
        val start = layout.startPages.getOrElse(chapterIndex) {
            layout.startPages.lastOrNull() ?: 0
        }
        val pageCount = layout.pageCounts.getOrElse(chapterIndex) { 0 }
        return start + pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }

    override fun pageIdentity(globalPage: Int): String? {
        val location = globalToChapterPage(globalPage)
        val chapter = chapters.getOrNull(location.chapterIndex) ?: return null
        return BookmarkPageIdentity.pdf(chapter.fileKey, location.pageIndex)
    }

    override fun findPageByIdentity(pageIdentity: String): Int? {
        val pageIndex = BookmarkPageIdentity.pdfLocalPage(pageIdentity) ?: return null
        val chapterIndex = chapters.indexOfFirst { chapter ->
            BookmarkPageIdentity.pdf(chapter.fileKey, pageIndex) == pageIdentity
        }
        if (chapterIndex < 0 || pageIndex !in 0 until chapterPageCount(chapterIndex)) return null
        return chapterPageToGlobal(chapterIndex, pageIndex)
    }

    override suspend fun loadPageBytes(globalPage: Int): ByteArray = withContext(Dispatchers.IO) {
        val (chapter, page) = globalToChapterPage(globalPage)
        renderPageToPng(chapter, page, fullQuality = true)
    }

    /**
     * 直接返回渲染好的 [ImageBitmap]，跳过 [loadPageBytes] 的
     * "Bitmap→PNG 压缩→Coil 解码回 Bitmap" 往返。PdfiumCore 渲染一次，
     * 我们直接拿来用，省一次压缩 + 一次解码，翻页更跟手。
     *
     * 章节打不开时返回 null，调用方 fallback 到 [loadPageBytes] 会抛
     * ComicOpenException 给出清晰提示。
     */
    override val supportsTargetedPageBitmap: Boolean = true

    override suspend fun loadPageBitmap(
        globalPage: Int,
        request: PageRenderRequest?,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val (chapter, page) = globalToChapterPage(globalPage)
        renderPageBitmap(
            chapterIndex = chapter,
            pageIndex = page,
            qualityScale = 1.0,
            request = request,
        )?.asImageBitmap()
    }

    override suspend fun ocrPageSize(globalPage: Int): PagePixelSize? =
        withContext(Dispatchers.IO) {
            val (chapter, page) = globalToChapterPage(globalPage)
            pdfiumLock.withLock {
                val opened = openDocumentLocked(chapter) ?: return@withLock null
                opened.document.openPage(page)
                calculateOcrPdfPageSize(
                    pageWidthPoints = opened.core.getPageWidthPoint(page),
                    pageHeightPoints = opened.core.getPageHeightPoint(page),
                )
            }
        }

    override suspend fun loadOcrPageRegion(
        globalPage: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val (chapter, page) = globalToChapterPage(globalPage)
        pdfiumLock.withLock {
            val opened = openDocumentLocked(chapter) ?: return@withLock null
            opened.document.openPage(page)
            val pageSize = calculateOcrPdfPageSize(
                pageWidthPoints = opened.core.getPageWidthPoint(page),
                pageHeightPoints = opened.core.getPageHeightPoint(page),
            )
            require(left >= 0 && top >= 0 && width > 0 && height > 0)
            require(left + width <= pageSize.width && top + height <= pageSize.height)
            createBitmap(width, height).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                opened.core.renderPageBitmap(
                    page,
                    bitmap,
                    -left,
                    -top,
                    pageSize.width,
                    pageSize.height,
                    true,
                )
            }
        }
    }

    override suspend fun loadThumbnailPageBytes(globalPage: Int): ByteArray = withContext(Dispatchers.IO) {
        val (chapter, page) = globalToChapterPage(globalPage)
        renderPageToPng(chapter, page, fullQuality = false)
    }

    override fun close() {
        pdfiumLock.withLock {
            if (closed) return
            closed = true
            // 显式按"核心 → PFD"顺序释放。PdfiumCore.close() 关 native 文档，
            // 但 ParcelFileDescriptor 是我们开的、不会被他代关——不显式 close 会
            // 泄漏文件描述符，多本 PDF 书反复打开最终会撞系统 fd 上限。
            openChapters.values.forEach(::closeOpenChapter)
            openChapters.clear()
        }
    }

    private fun resolvedChapterLayout(): PdfChapterLayout {
        chapterLayout.takeIf { it.isResolved }?.let { return it }
        return pdfiumLock.withLock {
            val current = chapterLayout
            if (current.isResolved) return@withLock current

            val discoveredCounts = current.pageCounts.copyOf()
            for (index in chapters.indices) {
                if (discoveredCounts[index] < 0) {
                    discoveredCounts[index] = discoverPageCountLocked(index)
                }
            }
            publishChapterLayoutLocked(discoveredCounts)
        }
    }

    /** Must be called while holding [pdfiumLock]. */
    private fun discoverPageCountLocked(chapterIndex: Int): Int {
        if (chapters[chapterIndex].isMissing || closed) return 0
        val cached = openChapters[chapterIndex]
        if (cached != null) {
            return runCatching { cached.document.totalPages }
                .getOrElse {
                    discardOpenChapterLocked(chapterIndex)
                    0
                }
                .coerceAtLeast(0)
        }
        val temporary = createOpenChapter(chapterIndex) ?: return 0
        return try {
            runCatching { temporary.document.totalPages }.getOrDefault(0).coerceAtLeast(0)
        } finally {
            // Metadata discovery must not turn chapter count into open file-descriptor count.
            closeOpenChapter(temporary)
        }
    }

    /** Must be called while holding [pdfiumLock]. */
    private fun publishPageCountLocked(chapterIndex: Int, pageCount: Int): PdfChapterLayout {
        val current = chapterLayout
        val normalized = pageCount.coerceAtLeast(0)
        if (current.pageCounts[chapterIndex] == normalized) return current
        val updatedCounts = current.pageCounts.copyOf()
        updatedCounts[chapterIndex] = normalized
        return publishChapterLayoutLocked(updatedCounts)
    }

    /** Must be called while holding [pdfiumLock]. */
    private fun publishChapterLayoutLocked(pageCounts: IntArray): PdfChapterLayout =
        PdfChapterLayout(pageCounts).also { chapterLayout = it }

    private fun openDocumentLocked(chapterIndex: Int): OpenChapter? {
        if (closed) return null
        openChapters[chapterIndex]?.let { return it }
        val opened = createOpenChapter(chapterIndex) ?: return null
        openChapters[chapterIndex] = opened
        trimOpenChaptersLocked()
        return opened
    }

    private fun discardOpenChapterLocked(chapterIndex: Int) {
        openChapters.remove(chapterIndex)?.let(::closeOpenChapter)
    }

    private fun createOpenChapter(chapterIndex: Int): OpenChapter? {
        val chapter = chapters.getOrNull(chapterIndex) ?: return null
        val pfd = pfdResolver(chapter.uri) ?: return null
        return try {
            val core = PdfiumCore(appContext)
            val doc = core.newDocument(pfd)
            OpenChapter(core = core, document = doc, pfd = pfd)
        } catch (e: Exception) {
            // 打开失败（损坏/加密/权限）：关掉 pfd，让调用方将章节标记为不可读。
            runCatching { pfd.close() }
            null
        }
    }

    private fun trimOpenChaptersLocked() {
        while (openChapters.size > OPEN_CHAPTER_LIMIT) {
            val eldest = openChapters.entries.iterator().next()
            openChapters.remove(eldest.key)
            closeOpenChapter(eldest.value)
        }
    }

    private fun closeOpenChapter(opened: OpenChapter) {
        runCatching { opened.core.close() }
        runCatching { opened.pfd.close() }
    }

    private suspend fun renderPageToPng(
        chapterIndex: Int,
        pageIndex: Int,
        fullQuality: Boolean,
    ): ByteArray {
        val callerContext = currentCoroutineContext()
        val bitmap = pdfiumLock.withLock {
            callerContext.ensureActive()
            renderPageBitmapLocked(
                chapterIndex = chapterIndex,
                pageIndex = pageIndex,
                qualityScale = if (fullQuality) 1.0 else 0.5,
                request = null,
            )
                ?: throw ComicOpenException("无法打开章节 PDF: ${chapters[chapterIndex].title}")
        }

        val stream = java.io.ByteArrayOutputStream()
        val format = if (fullQuality) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val quality = if (fullQuality) 100 else 85
        bitmap.compress(format, quality, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    /**
     * 渲染一页到 [Bitmap]，不压缩。供 [loadPageBitmap] 直接使用（无往返），
     * 也供 [renderPageToPng] 复用渲染逻辑。返回 null 表示章节打不开。
     *
     * 必须在 [pdfiumLock] 下调用：这个依赖版本的 PdfiumCore.close() 未完全使用其
     * 内部全局锁，因此所有 volume 的 open/render/close 都必须由应用进程级锁串行化。
     */
    private suspend fun renderPageBitmap(
        chapterIndex: Int,
        pageIndex: Int,
        qualityScale: Double,
        request: PageRenderRequest?,
    ): Bitmap? {
        val callerContext = currentCoroutineContext()
        return pdfiumLock.withLock {
            callerContext.ensureActive()
            renderPageBitmapLocked(chapterIndex, pageIndex, qualityScale, request)
        }
    }

    private fun renderPageBitmapLocked(
        chapterIndex: Int,
        pageIndex: Int,
        qualityScale: Double,
        request: PageRenderRequest?,
    ): Bitmap? {
        if (closed) return null
        val opened = openDocumentLocked(chapterIndex) ?: return null
        val core = opened.core
        val doc = opened.document

        doc.openPage(pageIndex)
        val width = core.getPageWidthPoint(pageIndex)
        val height = core.getPageHeightPoint(pageIndex)
        val size = calculatePdfRenderSize(
            pageWidthPoints = width,
            pageHeightPoints = height,
            qualityScale = qualityScale,
            request = request,
        )

        val bitmap = createBitmap(size.width, size.height)
        bitmap.eraseColor(android.graphics.Color.WHITE)

        core.renderPageBitmap(
            pageIndex,
            bitmap,
            0,
            0,
            size.width,
            size.height,
            true,
        )
        return bitmap
    }

    private companion object {
        val pdfiumLock = ReentrantLock()
        const val OPEN_CHAPTER_LIMIT = 3
        const val UNKNOWN_PAGE_COUNT = -1
    }

    private data class OpenChapter(
        val core: PdfiumCore,
        val document: com.ahmer.pdfium.PdfDocument,
        val pfd: ParcelFileDescriptor,
    )
}

private class PdfChapterLayout(pageCounts: IntArray) {
    val pageCounts: IntArray = pageCounts.copyOf()
    val startPages: IntArray = calculateChapterStartPages(this.pageCounts)
    val totalPageCount: Int = startPages.lastOrNull()?.let { lastStart ->
        lastStart + this.pageCounts.last().coerceAtLeast(0)
    } ?: 0
    val isResolved: Boolean = this.pageCounts.all { it >= 0 }
}

internal fun calculateChapterStartPages(pageCounts: IntArray): IntArray {
    val starts = IntArray(pageCounts.size)
    var accumulatedPages = 0
    for (index in pageCounts.indices) {
        starts[index] = accumulatedPages
        accumulatedPages += pageCounts[index].coerceAtLeast(0)
    }
    return starts
}

internal fun calculateOcrPdfPageSize(pageWidthPoints: Int, pageHeightPoints: Int): PagePixelSize =
    PagePixelSize(
        width = (pageWidthPoints * OCR_PDF_RENDER_SCALE).toInt().coerceAtLeast(1),
        height = (pageHeightPoints * OCR_PDF_RENDER_SCALE).toInt().coerceAtLeast(1),
    )

internal data class PdfRenderSize(val width: Int, val height: Int)

internal fun calculatePdfRenderSize(
    pageWidthPoints: Int,
    pageHeightPoints: Int,
    qualityScale: Double = 1.0,
    request: PageRenderRequest? = null,
): PdfRenderSize {
    val pageWidth = pageWidthPoints.coerceAtLeast(1).toDouble()
    val pageHeight = pageHeightPoints.coerceAtLeast(1).toDouble()
    var scale = request?.let {
        minOf(it.maxWidthPx / pageWidth, it.maxHeightPx / pageHeight)
    } ?: qualityScale

    request?.let {
        scale = minOf(scale, sqrt(it.maxPixels.toDouble() / (pageWidth * pageHeight)))
        scale = minOf(scale, it.maxDimensionPx / maxOf(pageWidth, pageHeight))
    }
    scale = scale.coerceAtLeast(1.0 / maxOf(pageWidth, pageHeight))

    return PdfRenderSize(
        width = (pageWidth * scale).toInt().coerceAtLeast(1),
        height = (pageHeight * scale).toInt().coerceAtLeast(1),
    )
}
