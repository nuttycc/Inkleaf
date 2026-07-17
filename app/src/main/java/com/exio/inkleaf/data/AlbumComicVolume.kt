package com.exio.inkleaf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.exio.inkleaf.data.db.AlbumPageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reads a user-created album directly from app-private page files. */
class AlbumComicVolume(
    context: Context,
    private val pages: List<AlbumPageEntity>,
    private val title: String,
) : ComicVolume {
    private val filesDir = context.applicationContext.filesDir

    override val totalPageCount: Int = pages.size
    override val sourceRevision: String = ReaderPageCacheKey.sourceRevision(
        buildList {
            add("album")
            pages.forEach { page ->
                val file = resolveAlbumPageFile(filesDir, page.relativePath)
                add(page.id)
                add(page.relativePath)
                add(file.length().toString())
                add(file.lastModified().toString())
            }
        }
    )
    override val chapterCount: Int = 1

    override fun chapterTitle(chapterIndex: Int): String = title

    override fun chapterStartPage(chapterIndex: Int): Int = 0

    override fun chapterPageCount(chapterIndex: Int): Int = pages.size

    override fun globalToChapterPage(globalPage: Int): ChapterProgress =
        ChapterProgress(0, globalPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))

    override fun chapterPageToGlobal(chapterIndex: Int, pageIndex: Int): Int =
        pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

    override fun pageIdentity(globalPage: Int): String? = pages.getOrNull(globalPage)?.id

    override suspend fun loadPageBytes(globalPage: Int): ByteArray = withContext(Dispatchers.IO) {
        pageFile(globalPage).readBytes()
    }

    override suspend fun loadThumbnailPageBytes(globalPage: Int): ByteArray =
        loadPageBytes(globalPage)

    override suspend fun renderThumbnail(globalPage: Int, targetWidth: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val file = pageFile(globalPage)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return@withContext null

            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= targetWidth) sampleSize *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            )?.asImageBitmap()
        }

    override fun close() = Unit

    private fun pageFile(globalPage: Int): File {
        check(pages.isNotEmpty()) { "图册中没有可读取的页面" }
        val page = pages[globalPage.coerceIn(0, pages.lastIndex)]
        return resolveAlbumPageFile(filesDir, page.relativePath).also { file ->
            check(file.isFile) { "图册页面不存在：${page.displayName}" }
        }
    }
}
