package com.exio.inkleaf.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.exio.inkleaf.data.db.AppDatabase
import com.exio.inkleaf.data.db.ComicEntity
import com.exio.inkleaf.data.db.FavoritePageEntity
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class FavoriteRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val favoriteDao = db.favoriteDao()
    private val comicDao = db.comicDao()

    fun observeAll(): Flow<List<FavoritePageEntity>> = favoriteDao.observeAll()

    fun observeForSource(sourceFileKey: String): Flow<List<FavoritePageEntity>> =
        favoriteDao.observeForSource(sourceFileKey)

    suspend fun addSnapshot(
        comic: ComicEntity,
        pageIndex: Int,
        pageCount: Int,
        pageBytes: ByteArray,
    ): FavoritePageEntity =
        withContext(Dispatchers.IO) {
            favoriteDao.getBySourcePage(comic.fileKey, pageIndex)?.let {
                return@withContext it
            }

            val key = stableKey(comic.fileKey, pageIndex)
            val extension = imageExtension(pageBytes)
            val imageFile = File(pagesDir(), "$key.$extension")
            atomicWrite(imageFile, "收藏图片写入失败") { it.write(pageBytes) }

            val thumbnailFile = createThumbnail(key, pageBytes)
            val favorite =
                FavoritePageEntity(
                    sourceComicId = comic.id,
                    sourceFileKey = comic.fileKey,
                    sourceTitle = comic.title,
                    pageIndex = pageIndex,
                    pageCount = pageCount,
                    imagePath = imageFile.absolutePath,
                    thumbnailPath = thumbnailFile?.absolutePath,
                    addedAt = System.currentTimeMillis(),
                )
            val id = favoriteDao.insert(favorite)
            favoriteDao.getById(id) ?: favorite.copy(id = id)
        }

    suspend fun remove(favorite: FavoritePageEntity) =
        withContext(Dispatchers.IO) {
            favoriteDao.deleteById(favorite.id)
            deleteFiles(favorite)
        }

    suspend fun findSourceComic(favorite: FavoritePageEntity): ComicEntity? {
        val byId =
            comicDao.getById(favorite.sourceComicId)?.takeIf {
                it.fileKey == favorite.sourceFileKey && !it.isMissing
            }
        return byId ?: comicDao.getByFileKey(favorite.sourceFileKey)?.takeIf { !it.isMissing }
    }

    fun shareIntent(favorite: FavoritePageEntity): Intent? {
        val file = File(favorite.imagePath).takeIf { it.exists() } ?: return null
        val uri =
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file,
            )
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeFor(file.extension)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(send, "分享收藏图片").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    suspend fun exportToGallery(favorite: FavoritePageEntity): Uri =
        withContext(Dispatchers.IO) {
            val source =
                File(favorite.imagePath).takeIf { it.exists() } ?: throw IOException("收藏图片不存在")
            saveImageBytesToGallery(appContext, source.readBytes(), exportName(favorite))
        }

    private fun pagesDir(): File =
        File(File(appContext.filesDir, FAVORITES_DIR), PAGES_DIR).apply { mkdirs() }

    private fun thumbsDir(): File =
        File(File(appContext.filesDir, FAVORITES_DIR), THUMBS_DIR).apply { mkdirs() }

    private fun createThumbnail(key: String, pageBytes: ByteArray): File? =
        runCatching {
                val bitmap =
                    Covers.decodeSampled(pageBytes, THUMB_TARGET_WIDTH) ?: return@runCatching null
                try {
                    val file = File(thumbsDir(), "$key.jpg")
                    atomicWrite(file, "缩略图写入失败") { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    file
                } finally {
                    bitmap.recycle()
                }
            }
            .getOrNull()

    private fun deleteFiles(favorite: FavoritePageEntity) {
        File(favorite.imagePath).delete()
        favorite.thumbnailPath?.let { File(it).delete() }
    }

    /** 写临时文件再原子改名：进程中途被杀不会留下半截文件被误当成完整数据 */
    private inline fun atomicWrite(
        file: File,
        errorMessage: String,
        write: (OutputStream) -> Unit,
    ) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().use(write)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) throw IOException(errorMessage)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun exportName(favorite: FavoritePageEntity): String =
        "${sanitizeFileName(favorite.sourceTitle)}_p${favorite.pageIndex + 1}_${favorite.addedAt}"

    companion object {
        private const val FAVORITES_DIR = "favorites"
        private const val PAGES_DIR = "pages"
        private const val THUMBS_DIR = "thumbs"
        private const val THUMB_TARGET_WIDTH = 360

        private fun stableKey(sourceFileKey: String, pageIndex: Int): String {
            val bytes =
                MessageDigest.getInstance("SHA-256")
                    .digest("$sourceFileKey\n$pageIndex".toByteArray())
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
