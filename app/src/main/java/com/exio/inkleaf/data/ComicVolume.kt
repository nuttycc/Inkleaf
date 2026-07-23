package com.exio.inkleaf.data

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Physical-pixel bounds for formats, such as PDF, that can render at a requested resolution. */
data class PageRenderRequest(
    val maxWidthPx: Int,
    val maxHeightPx: Int,
    val maxPixels: Long,
    val maxDimensionPx: Int,
) {
    init {
        require(maxWidthPx > 0 && maxHeightPx > 0)
        require(maxPixels > 0)
        require(maxDimensionPx > 0)
    }
}

data class PagePixelSize(val width: Int, val height: Int)

/**
 * 一本已打开的漫画，对 UI 层隐藏底层是 zip/cbz 还是 PDF 目录。
 *
 * 调用约定：close() 之后不可再调用读取方法。
 */
interface ComicVolume {
    /** 全书总页数（跨所有章节） */
    val totalPageCount: Int

    /** Stable token that changes whenever page content or ordering may have changed. */
    val sourceRevision: String

    /** 章节数量 */
    val chapterCount: Int

    /** 获取第 [chapterIndex] 章的标题 */
    fun chapterTitle(chapterIndex: Int): String

    /** 获取第 [chapterIndex] 章在全书中的起始全局页码 */
    fun chapterStartPage(chapterIndex: Int): Int

    /** 获取第 [chapterIndex] 章的页数 */
    fun chapterPageCount(chapterIndex: Int): Int

    /** 将全局页码转换为 (章节索引, 章节内页码) */
    fun globalToChapterPage(globalPage: Int): ChapterProgress

    /** 将 (章节索引, 章节内页码) 转换为全局页码 */
    fun chapterPageToGlobal(chapterIndex: Int, pageIndex: Int): Int

    /**
     * Stable identity for a page when the underlying book can be edited.
     * Immutable volumes can use the default null value and fall back to the page index.
     */
    fun pageIdentity(globalPage: Int): String? = null

    /** Finds the current global position of a stable page identity after source edits. */
    fun findPageByIdentity(pageIdentity: String): Int? {
        if (pageIdentity.isBlank()) return null
        return (0 until totalPageCount).firstOrNull { this.pageIdentity(it) == pageIdentity }
    }

    /** 读取第 [globalPage] 页的原始图片字节（zip/cbz 是压缩图片数据，PDF 是渲染后的 PNG） */
    suspend fun loadPageBytes(globalPage: Int): ByteArray

    /** Whether this volume can rasterize a page for the reader's current physical viewport. */
    val supportsTargetedPageBitmap: Boolean
        get() = false

    /**
     * 直接读取第 [globalPage] 页的位图，跳过 [loadPageBytes] 的"渲染→压缩→UI 再解码"往返。
     *
     * 默认返回 null：zip/cbz 走 [loadPageBytes] + Coil 解码即可（本来就是压缩图片字节，
     * 不存在往返）；PdfComicVolume 覆盖此方法直接返回 PdfiumCore 渲染好的 ImageBitmap，
     * 省一次 PNG 压缩 + 一次解码，翻页更跟手。
     *
     * 返回 null 时调用方应 fallback 到 [loadPageBytes]。
     */
    suspend fun loadPageBitmap(
        globalPage: Int,
        request: PageRenderRequest? = null,
    ): ImageBitmap? = null

    /** Returns the page size used by a source's region-based OCR renderer, when supported. */
    suspend fun ocrPageSize(globalPage: Int): PagePixelSize? = null

    /** Renders one OCR region without allocating the complete page, when supported. */
    suspend fun loadOcrPageRegion(
        globalPage: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Bitmap? = null

    /** 缩略图专用读取通道 */
    suspend fun loadThumbnailPageBytes(globalPage: Int): ByteArray

    /**
     * 生成第 [globalPage] 页的缩略图位图。默认实现走 [loadThumbnailPageBytes] +
     * [Covers.decodeSampled] 通用通道，zip/cbz 与 PDF 都适用。
     */
    suspend fun renderThumbnail(globalPage: Int, targetWidth: Int): ImageBitmap? {
        val bytes = loadThumbnailPageBytes(globalPage)
        val decoded = Covers.decodeSampled(bytes, targetWidth, Bitmap.Config.RGB_565) ?: return null
        return decoded.asImageBitmap()
    }

    /** 关闭所有底层资源 */
    fun close()
}
