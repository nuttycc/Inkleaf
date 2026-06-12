package com.exio.comicreader.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.exio.comicreader.data.db.AppDatabase
import com.exio.comicreader.data.db.ComicEntity
import com.exio.comicreader.data.db.FavoritePageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class FavoriteRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val favoriteDao = db.favoriteDao()
    private val comicDao = db.comicDao()

    fun observeAll(): Flow<List<FavoritePageEntity>> = favoriteDao.observeAll()

    fun observeForSource(sourceUri: String): Flow<List<FavoritePageEntity>> =
        favoriteDao.observeForSource(sourceUri)

    suspend fun addSnapshot(
        comic: ComicEntity,
        pageIndex: Int,
        pageCount: Int,
        pageBytes: ByteArray,
    ): FavoritePageEntity = withContext(Dispatchers.IO) {
        favoriteDao.getBySourcePage(comic.uri, pageIndex)?.let { return@withContext it }

        val key = stableKey(comic.uri, pageIndex)
        val extension = imageExtension(pageBytes)
        val imageFile = File(pagesDir(), "$key.$extension")
        atomicWrite(imageFile, pageBytes)

        val thumbnailFile = createThumbnail(key, pageBytes)
        val favorite = FavoritePageEntity(
            sourceComicId = comic.id,
            sourceUri = comic.uri,
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

    suspend fun remove(favorite: FavoritePageEntity) = withContext(Dispatchers.IO) {
        favoriteDao.deleteById(favorite.id)
        deleteFiles(favorite)
    }

    suspend fun findSourceComic(favorite: FavoritePageEntity): ComicEntity? {
        val byId = comicDao.getById(favorite.sourceComicId)
            ?.takeIf { it.uri == favorite.sourceUri && !it.isMissing }
        return byId ?: comicDao.getByUri(favorite.sourceUri)?.takeIf { !it.isMissing }
    }

    fun shareIntent(favorite: FavoritePageEntity): Intent? {
        val file = File(favorite.imagePath).takeIf { it.exists() } ?: return null
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeFor(file.extension)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "分享收藏图片")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    suspend fun exportToGallery(favorite: FavoritePageEntity): Uri = withContext(Dispatchers.IO) {
        val source = File(favorite.imagePath).takeIf { it.exists() }
            ?: throw IOException("收藏图片不存在")
        val extension = source.extension.ifBlank { "jpg" }.lowercase()
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, exportName(favorite, extension))
            put(MediaStore.Images.Media.MIME_TYPE, mimeTypeFor(extension))
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/ComicReader",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建相册文件")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("无法写入相册文件")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun pagesDir(): File =
        File(File(appContext.filesDir, FAVORITES_DIR), PAGES_DIR).apply { mkdirs() }

    private fun thumbsDir(): File =
        File(File(appContext.filesDir, FAVORITES_DIR), THUMBS_DIR).apply { mkdirs() }

    private fun createThumbnail(key: String, pageBytes: ByteArray): File? = runCatching {
        val bitmap = Covers.decodeSampled(pageBytes, THUMB_TARGET_WIDTH)
            ?: return@runCatching null
        val file = File(thumbsDir(), "$key.jpg")
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) throw IOException("缩略图写入失败")
            file
        } finally {
            bitmap.recycle()
            tmp.delete()
        }
    }.getOrNull()

    private fun deleteFiles(favorite: FavoritePageEntity) {
        File(favorite.imagePath).delete()
        favorite.thumbnailPath?.let { File(it).delete() }
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().use { it.write(bytes) }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) throw IOException("收藏图片写入失败")
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun exportName(favorite: FavoritePageEntity, extension: String): String {
        val title = favorite.sourceTitle
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .take(48)
            .ifBlank { "ComicReader" }
        return "${title}_p${favorite.pageIndex + 1}_${favorite.addedAt}.$extension"
    }

    companion object {
        private const val FAVORITES_DIR = "favorites"
        private const val PAGES_DIR = "pages"
        private const val THUMBS_DIR = "thumbs"
        private const val THUMB_TARGET_WIDTH = 360

        private fun stableKey(sourceUri: String, pageIndex: Int): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest("$sourceUri\n$pageIndex".toByteArray())
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun imageExtension(bytes: ByteArray): String = when {
            bytes.size >= 3 &&
                    bytes[0] == 0xFF.toByte() &&
                    bytes[1] == 0xD8.toByte() &&
                    bytes[2] == 0xFF.toByte() -> "jpg"

            bytes.size >= 8 &&
                    bytes[0] == 0x89.toByte() &&
                    bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() &&
                    bytes[3] == 0x47.toByte() -> "png"

            bytes.size >= 12 &&
                    bytes[0] == 0x52.toByte() &&
                    bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() &&
                    bytes[3] == 0x46.toByte() &&
                    bytes[8] == 0x57.toByte() &&
                    bytes[9] == 0x45.toByte() &&
                    bytes[10] == 0x42.toByte() &&
                    bytes[11] == 0x50.toByte() -> "webp"

            bytes.size >= 6 &&
                    bytes[0] == 0x47.toByte() &&
                    bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() -> "gif"

            else -> "jpg"
        }

        private fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
    }
}
