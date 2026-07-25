package com.exio.inkleaf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 阅读相关磁盘缓存的唯一管理者：zip 副本（books/）和页缩略图（thumbs/）。
 *
 * 两类缓存的失效是联动的——zip 副本因原文件变化而重建时， 这本书的缩略图也一并作废（它们派生自旧内容）。把路径和清理 集中在一处，调用方不可能只清一半。
 *
 * 都放 cacheDir：系统存储紧张时可整体回收，回收后行为退化为 "重新复制/重新解码"，不丢任何用户数据。
 */
object ReaderCache {
    private const val BOOKS_DIR = "books"
    private const val THUMBS_DIR = "thumbs"

    /** 旧版固定文件名副本，升级后冷启动时清一次 */
    private const val LEGACY_CACHE_FILE = "current_comic.zip"

    /** 半成品 .tmp 的保留期：太新的可能正被别的协程写入，不碰 */
    private const val TMP_STALE_MS = 60 * 60 * 1000L

    fun booksDir(context: Context): File = File(context.cacheDir, BOOKS_DIR).apply { mkdirs() }

    /** 某本书的 zip 副本文件。文件名内嵌原文件的"大小+修改时间"作为 校验键：原文件被替换后键变化，旧副本自然失配（由 wipeBook 清走）， 不需要额外的元数据文件 */
    fun cachedZipFile(context: Context, comicId: Long, sourceSize: Long, sourceMtime: Long): File =
        File(booksDir(context), "$comicId-$sourceSize-$sourceMtime.zip")

    fun thumbsDir(context: Context, comicId: Long): File =
        File(File(context.cacheDir, THUMBS_DIR), comicId.toString())

    private fun thumbFile(
        context: Context,
        comicId: Long,
        page: Int,
        pageIdentity: String?,
    ): File =
        File(
            thumbsDir(context, comicId),
            ReaderPageCacheKey.thumbnailFileName(page, pageIdentity),
        )

    /** 读某页缩略图的磁盘缓存；从未落盘过返回 null。RGB_565：缩略图无透明需求、内存减半 */
    suspend fun readThumbnail(
        context: Context,
        comicId: Long,
        page: Int,
        pageIdentity: String? = null,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            val file = thumbFile(context, comicId, page, pageIdentity)
            if (file.exists()) {
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    },
                )
            } else {
                null
            }
        }

    /** 缩略图落盘供下次开书复用。失败无所谓（磁盘满等）：缓存只是加速，下次重新解码 */
    suspend fun writeThumbnail(
        context: Context,
        comicId: Long,
        page: Int,
        pageIdentity: String? = null,
        bitmap: Bitmap,
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                val file = thumbFile(context, comicId, page, pageIdentity)
                file.parentFile?.mkdirs()
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }
        }
    }

    /** 作废一本书的全部派生缓存：zip 副本（任意校验键）+ 缩略图目录 */
    fun wipeBook(context: Context, comicId: Long) {
        booksDir(context).listFiles()?.forEach { f ->
            if (f.name.startsWith("$comicId-")) f.delete()
        }
        thumbsDir(context, comicId).deleteRecursively()
    }

    /** zip 副本当前总占用（缩略图和封面很小，不计入，与预算口径一致） */
    fun usageBytes(context: Context): Long =
        booksDir(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /**
     * 副本总量超预算（用户设置的档位或自动推荐值）时按 lastModified（每次打开会刷新， 即"最近阅读时间"）从旧到新淘汰，连带删掉对应书的缩略图。 keep
     * 是当前正打开的副本，任何情况下不淘汰它；设置页触发的清理 没有"当前书"概念，传 null——即使误删了后台阅读页正持有的副本 也安全：已打开的文件句柄在 unlink
     * 后仍然有效，下次打开重新复制。
     */
    suspend fun enforceBudget(context: Context, keep: File?) {
        val budget = CacheSettingsRepository(context).limit.first().bytes(context)
        val files = booksDir(context).listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= budget) return

        for (f in files.filter { it != keep }.sortedBy { it.lastModified() }) {
            if (total <= budget) break
            total -= f.length()
            val comicId = f.name.substringBefore('-').toLongOrNull()
            f.delete()
            comicId?.let { thumbsDir(context, it).deleteRecursively() }
        }
    }

    /** 冷启动清理：旧版固定名副本 + 上次进程被杀留下的过期半成品 */
    fun cleanupOnColdStart(context: Context) {
        File(context.cacheDir, LEGACY_CACHE_FILE).delete()
        val staleBefore = System.currentTimeMillis() - TMP_STALE_MS
        booksDir(context).listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp") && f.lastModified() < staleBefore) f.delete()
        }
    }
}
