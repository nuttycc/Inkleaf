package com.exio.inkleaf.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把现有的 zip/cbz 单文件漫画包装成 [ComicVolume]。
 *
 * 单文件漫画逻辑上只有一个章节，chapterIndex 固定为 0。
 */
class ZipComicVolume(private val book: ComicBook, private val title: String) : ComicVolume {
    override val totalPageCount: Int get() = book.pageCount
    override val sourceRevision: String get() = book.sourceRevision
    override val chapterCount: Int get() = 1
    override val supportsFastRasterEnhancement: Boolean = true
    override val supportsPageRegionLoad: Boolean = true

    override fun chapterTitle(chapterIndex: Int): String = title
    override fun chapterStartPage(chapterIndex: Int): Int = 0
    override fun chapterPageCount(chapterIndex: Int): Int = book.pageCount

    override fun globalToChapterPage(globalPage: Int): ChapterProgress =
        ChapterProgress(0, globalPage.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0)))

    override fun chapterPageToGlobal(chapterIndex: Int, pageIndex: Int): Int =
        pageIndex.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0))

    override fun pageIdentity(globalPage: Int): String? = book.pageIdentity(globalPage)

    override fun findPageByIdentity(pageIdentity: String): Int? =
        book.findPageByIdentity(pageIdentity)

    override suspend fun loadPageBytes(globalPage: Int): ByteArray =
        book.loadPageBytes(globalPage.coerceIn(0, book.pageCount - 1))

    override suspend fun loadPageRasterSize(globalPage: Int): PagePixelSize? =
        withContext(Dispatchers.IO) {
            val bytes = loadPageBytes(globalPage)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                PagePixelSize(bounds.outWidth, bounds.outHeight)
            }
        }

    override suspend fun loadPageRegion(
        globalPage: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = loadPageBytes(globalPage)
        val decoder = try {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        try {
            decoder.decodeRegion(
                Rect(left, top, left + width, top + height),
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        } finally {
            decoder.recycle()
        }
    }

    override suspend fun loadThumbnailPageBytes(globalPage: Int): ByteArray =
        book.loadThumbnailPageBytes(globalPage.coerceIn(0, book.pageCount - 1))

    override fun close() = book.close()
}
