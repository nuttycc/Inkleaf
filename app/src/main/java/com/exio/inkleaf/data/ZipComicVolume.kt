package com.exio.inkleaf.data

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 把现有的 zip/cbz 单文件漫画包装成 [ComicVolume]。
 *
 * 单文件漫画逻辑上只有一个章节，chapterIndex 固定为 0。
 */
class ZipComicVolume(private val book: ComicBook, private val title: String) : ComicVolume {
    override val totalPageCount: Int get() = book.pageCount
    override val chapterCount: Int get() = 1

    override fun chapterTitle(chapterIndex: Int): String = title
    override fun chapterStartPage(chapterIndex: Int): Int = 0
    override fun chapterPageCount(chapterIndex: Int): Int = book.pageCount

    override fun globalToChapterPage(globalPage: Int): ChapterProgress =
        ChapterProgress(0, globalPage.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0)))

    override fun chapterPageToGlobal(chapterIndex: Int, pageIndex: Int): Int =
        pageIndex.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0))

    override suspend fun loadPageBytes(globalPage: Int): ByteArray =
        book.loadPageBytes(globalPage.coerceIn(0, book.pageCount - 1))

    override suspend fun loadThumbnailPageBytes(globalPage: Int): ByteArray =
        book.loadThumbnailPageBytes(globalPage.coerceIn(0, book.pageCount - 1))

    override suspend fun renderThumbnail(globalPage: Int, targetWidth: Int): ImageBitmap? {
        val bytes = loadThumbnailPageBytes(globalPage)
        val decoded = Covers.decodeSampled(bytes, targetWidth, Bitmap.Config.RGB_565) ?: return null
        return decoded.asImageBitmap()
    }

    override fun close() = book.close()
}
