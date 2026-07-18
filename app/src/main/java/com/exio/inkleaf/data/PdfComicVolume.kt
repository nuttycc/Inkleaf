package com.exio.inkleaf.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.documentfile.provider.DocumentFile
import com.ahmer.pdfium.PdfiumCore
import com.exio.inkleaf.data.db.ChapterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.coroutineContext
import kotlin.concurrent.withLock
import kotlin.math.sqrt

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

    /** 每个章节对应的已打开 PDF 渲染核心 */
    private val cores = mutableMapOf<Int, PdfiumCore>()

    /** 每个章节对应的已打开 PDF 文档句柄 */
    private val documents = mutableMapOf<Int, com.ahmer.pdfium.PdfDocument>()

    /** 每个章节对应的 ParcelFileDescriptor，需独立关闭（PdfiumCore.close 不会替我们关） */
    private val pfds = mutableMapOf<Int, ParcelFileDescriptor>()

    /** 每个章节的页数；index 与 chapters 一致 */
    private val pageCounts = IntArray(chapters.size) { -1 }

    /** 章节起始全局页号缓存 */
    private var startPages: IntArray? = null

    init {
        require(chapters.isNotEmpty()) { "PdfComicVolume 至少需要一章" }
    }

    override val chapterCount: Int get() = chapters.size

    override val sourceRevision: String = ReaderPageCacheKey.sourceRevision(
        buildList {
            add("pdf-series")
            chapters.forEach { chapter ->
                val document = runCatching {
                    DocumentFile.fromSingleUri(appContext, Uri.parse(chapter.uri))
                }.getOrNull()
                add(chapter.fileKey)
                add(chapter.uri)
                add((document?.length() ?: -1L).toString())
                add((document?.lastModified() ?: -1L).toString())
            }
        }
    )

    override val totalPageCount: Int
        get() {
            val starts = startPages ?: computeStartPages()
            if (starts.isEmpty()) return 0
            val last = starts.lastIndex
            return starts[last] + pageCounts[last].coerceAtLeast(0)
        }

    override fun chapterTitle(chapterIndex: Int): String =
        chapters.getOrNull(chapterIndex)?.title ?: ""

    override fun chapterStartPage(chapterIndex: Int): Int {
        val starts = startPages ?: computeStartPages()
        return starts.getOrElse(chapterIndex) { starts.lastOrNull() ?: 0 }
    }

    override fun chapterPageCount(chapterIndex: Int): Int {
        ensurePageCount(chapterIndex)
        return pageCounts.getOrElse(chapterIndex) { 0 }.coerceAtLeast(0)
    }

    override fun globalToChapterPage(globalPage: Int): ChapterProgress {
        // 封面回填等早期路径只会读第 0 页：在 startPages 还没算出来时，只要
        // 第 0 章本身能打开，就直接落在 (0, globalPage)，避免触发 computeStartPages
        // 把整本书所有章节都打开一遍。
        if (startPages == null && chapterCount > 0 && globalPage >= 0) {
            val firstPages = chapterPageCount(0)
            if (firstPages > 0 && globalPage < firstPages) {
                return ChapterProgress(0, globalPage)
            }
        }
        val starts = startPages ?: computeStartPages()
        if (starts.isEmpty()) return ChapterProgress(0, 0)
        val chapter = (0 until chapterCount).lastOrNull { starts[it] <= globalPage } ?: 0
        val pages = pageCounts.getOrElse(chapter) { 0 }.coerceAtLeast(0)
        // 章节打不开时 pageCounts[chapter] 为 -1（被 coerce 成 0）。
        // pages <= 0 时直接落在第 0 页，渲染层会再给出"无法打开章节"的清晰提示。
        val pageInChapter = if (pages <= 0) 0
            else (globalPage - starts[chapter]).coerceIn(0, pages - 1)
        return ChapterProgress(chapter, pageInChapter)
    }

    override fun chapterPageToGlobal(chapterIndex: Int, pageIndex: Int): Int {
        val starts = startPages ?: computeStartPages()
        val start = starts.getOrElse(chapterIndex) { starts.lastOrNull() ?: 0 }
        return start + pageIndex.coerceIn(0, (pageCounts.getOrElse(chapterIndex) { 0 } - 1).coerceAtLeast(0))
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
            maxPixels = null,
        )?.asImageBitmap()
    }

    override suspend fun loadPageBitmapForInference(
        globalPage: Int,
        maxPixels: Long,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val (chapter, page) = globalToChapterPage(globalPage)
        renderPageBitmap(
            chapterIndex = chapter,
            pageIndex = page,
            qualityScale = 1.0,
            request = null,
            maxPixels = maxPixels,
        )?.asImageBitmap()
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
            cores.values.forEach { runCatching { it.close() } }
            pfds.values.forEach { runCatching { it.close() } }
            cores.clear()
            documents.clear()
            pfds.clear()
        }
    }

    private fun computeStartPages(): IntArray {
        val starts = IntArray(chapterCount)
        var acc = 0
        for (i in chapters.indices) {
            ensurePageCount(i)
            starts[i] = acc
            acc += pageCounts[i].coerceAtLeast(0)
        }
        startPages = starts
        return starts
    }

    private fun ensurePageCount(chapterIndex: Int) {
        pdfiumLock.withLock {
            if (pageCounts[chapterIndex] >= 0 || closed) return
            val doc = openDocumentLocked(chapterIndex) ?: return
            pageCounts[chapterIndex] = doc.totalPages
        }
    }

    private fun openDocumentLocked(chapterIndex: Int): com.ahmer.pdfium.PdfDocument? {
        if (closed) return null
        documents[chapterIndex]?.let { return it }
        val chapter = chapters.getOrNull(chapterIndex) ?: return null
        val pfd = pfdResolver(chapter.uri) ?: return null
        return try {
            val core = PdfiumCore(appContext)
            val doc = core.newDocument(pfd)
            cores[chapterIndex] = core
            documents[chapterIndex] = doc
            pfds[chapterIndex] = pfd
            doc
        } catch (e: Exception) {
            // 打开失败（损坏/加密/权限）：关掉 pfd，pageCounts 留 -1。
            // 后续 chapterPageCount 会返回 0，globalToChapterPage 落在第 0 页，
            // 渲染层抛 ComicOpenException 给出清晰提示——不崩。
            runCatching { pfd.close() }
            null
        }
    }

    private suspend fun renderPageToPng(
        chapterIndex: Int,
        pageIndex: Int,
        fullQuality: Boolean,
    ): ByteArray {
        val bitmap = pdfiumLock.withLock {
            coroutineContext.ensureActive()
            renderPageBitmapLocked(
                chapterIndex = chapterIndex,
                pageIndex = pageIndex,
                qualityScale = if (fullQuality) 1.0 else 0.5,
                request = null,
                maxPixels = null,
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
        maxPixels: Long?,
    ): Bitmap? = pdfiumLock.withLock {
        coroutineContext.ensureActive()
        renderPageBitmapLocked(chapterIndex, pageIndex, qualityScale, request, maxPixels)
    }

    private fun renderPageBitmapLocked(
        chapterIndex: Int,
        pageIndex: Int,
        qualityScale: Double,
        request: PageRenderRequest?,
        maxPixels: Long?,
    ): Bitmap? {
        if (closed) return null
        val core = cores[chapterIndex] ?: return null
        val doc = documents[chapterIndex] ?: return null

        doc.openPage(pageIndex)
        val width = core.getPageWidthPoint(pageIndex)
        val height = core.getPageHeightPoint(pageIndex)
        val size = calculatePdfRenderSize(
            pageWidthPoints = width,
            pageHeightPoints = height,
            qualityScale = qualityScale,
            request = request,
            legacyMaxPixels = maxPixels,
        )

        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
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
    }
}

internal data class PdfRenderSize(val width: Int, val height: Int)

internal fun calculatePdfRenderSize(
    pageWidthPoints: Int,
    pageHeightPoints: Int,
    qualityScale: Double = 1.0,
    request: PageRenderRequest? = null,
    legacyMaxPixels: Long? = null,
): PdfRenderSize {
    val pageWidth = pageWidthPoints.coerceAtLeast(1).toDouble()
    val pageHeight = pageHeightPoints.coerceAtLeast(1).toDouble()
    var scale = request?.let {
        minOf(it.maxWidthPx / pageWidth, it.maxHeightPx / pageHeight)
    } ?: qualityScale

    val pixelBudget = request?.maxPixels ?: legacyMaxPixels
    if (pixelBudget != null) {
        scale = minOf(scale, sqrt(pixelBudget.toDouble() / (pageWidth * pageHeight)))
    }
    request?.let {
        scale = minOf(scale, it.maxDimensionPx / maxOf(pageWidth, pageHeight))
    }
    scale = scale.coerceAtLeast(1.0 / maxOf(pageWidth, pageHeight))

    return PdfRenderSize(
        width = (pageWidth * scale).toInt().coerceAtLeast(1),
        height = (pageHeight * scale).toInt().coerceAtLeast(1),
    )
}
