package com.exio.inkleaf.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.exio.inkleaf.data.db.AlbumPageEntity
import com.exio.inkleaf.data.db.AppDatabase
import com.exio.inkleaf.data.db.BookSourceType
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AlbumExporter(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val comicDao = database.comicDao()
    private val albumPageDao = database.albumPageDao()

    suspend fun exportToUri(comicId: Long, uri: Uri) = albumFileMutex.withLock {
        withContext(Dispatchers.IO) {
            val album = loadAlbum(comicId)
            try {
                appContext.contentResolver.openOutputStream(uri)?.use { output ->
                    writeCbz(output, album.title, album.coverPageId, album.pages, ::openPage)
                } ?: throw IOException("无法打开导出文件")
            } catch (error: Throwable) {
                runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
                throw error
            }
        }
    }

    suspend fun createShareIntent(comicId: Long): Intent = albumFileMutex.withLock {
        withContext(Dispatchers.IO) {
            val album = loadAlbum(comicId)
            val exportDirectory =
                File(exportsDirectory(appContext), comicId.toString()).apply {
                    mkdirs()
                }
            val exportFile = File(exportDirectory, fileNameForTitle(album.title))
            atomicWrite(exportFile) { output ->
                writeCbz(output, album.title, album.coverPageId, album.pages, ::openPage)
            }

            val contentUri =
                FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    exportFile,
                )
            val sendIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = CBZ_MIME_TYPE
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    clipData =
                        ClipData.newUri(
                            appContext.contentResolver,
                            exportFile.name,
                            contentUri,
                        )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            Intent.createChooser(sendIntent, "分享图册").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    suspend fun suggestedFileName(comicId: Long): String =
        withContext(Dispatchers.IO) {
            val comic = comicDao.getById(comicId) ?: throw FileNotFoundException("图册不存在")
            fileNameForTitle(comic.title)
        }

    private suspend fun loadAlbum(comicId: Long): ExportAlbum {
        val comic = comicDao.getById(comicId) ?: throw FileNotFoundException("图册不存在")
        if (comic.sourceType != BookSourceType.CREATED_ALBUM || comic.isDraft) {
            throw IOException("所选内容不是可导出的图册")
        }
        val pages = albumPageDao.getByComicId(comicId)
        if (pages.isEmpty()) throw IOException("图册中没有可导出的图片")
        return ExportAlbum(
            title = comic.title,
            coverPageId = comic.coverPageId,
            pages = pages.map(AlbumPageEntity::toExportPage),
        )
    }

    private fun openPage(page: ExportPage): InputStream {
        val file = resolveAlbumPageFile(appContext.filesDir, page.relativePath)
        if (!file.isFile) throw FileNotFoundException("图册页面不存在：${page.displayName}")
        return file.inputStream()
    }

    companion object {
        const val CBZ_MIME_TYPE = "application/vnd.comicbook+zip"

        private const val EXPORTS_DIRECTORY = "exports"
        private const val MAX_EXPORT_AGE_MS = 24L * 60L * 60L * 1000L

        fun cleanupOnColdStart(context: Context) {
            val root = exportsDirectory(context.applicationContext)
            if (!root.exists()) return
            val cutoff = System.currentTimeMillis() - MAX_EXPORT_AGE_MS
            root.walkBottomUp().forEach { file ->
                when {
                    file == root -> Unit
                    file.isDirectory -> file.delete()
                    file.name.endsWith(".tmp") || file.lastModified() < cutoff -> file.delete()
                }
            }
        }

        private fun exportsDirectory(context: Context): File =
            File(context.cacheDir, EXPORTS_DIRECTORY)
    }
}

private data class ExportAlbum(
    val title: String,
    val coverPageId: String?,
    val pages: List<ExportPage>,
)

internal data class ExportPage(
    val id: String,
    val position: Int,
    val relativePath: String,
    val displayName: String,
    val extension: String,
)

private fun AlbumPageEntity.toExportPage() =
    ExportPage(
        id = id,
        position = position,
        relativePath = relativePath,
        displayName = displayName,
        extension = extension,
    )

internal fun writeCbz(
    output: OutputStream,
    title: String,
    coverPageId: String?,
    pages: List<ExportPage>,
    openPage: (ExportPage) -> InputStream,
) {
    val orderedPages = pages.sortedWith(compareBy<ExportPage> { it.position }.thenBy { it.id })
    ZipOutputStream(output.buffered()).use { zip ->
        // Page images are already compressed; level 0 avoids wasting CPU recompressing them.
        zip.setLevel(Deflater.NO_COMPRESSION)
        zip.putNextEntry(ZipEntry("ComicInfo.xml"))
        zip.write(
            comicInfoXml(
                    title,
                    orderedPages,
                    coverPageId,
                )
                .toByteArray(StandardCharsets.UTF_8)
        )
        zip.closeEntry()

        orderedPages.forEachIndexed { index, page ->
            zip.putNextEntry(ZipEntry(pageEntryName(index, orderedPages.size, page.extension)))
            openPage(page).use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

internal fun pageEntryName(index: Int, pageCount: Int, extension: String): String {
    val width = maxOf(4, pageCount.toString().length)
    val safeExtension =
        extension.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit).ifBlank { "jpg" }
    return "page-${(index + 1).toString().padStart(width, '0')}.$safeExtension"
}

internal fun fileNameForTitle(title: String): String {
    val safeTitle =
        title
            .replace(Regex("""[\\/:*?\"<>|\p{Cntrl}]"""), "_")
            .trim()
            .trimEnd('.', ' ')
            .take(80)
            .trimEnd('.', ' ')
            .ifBlank { "Inkleaf" }
    return "$safeTitle.cbz"
}

internal fun comicInfoXml(
    title: String,
    pages: List<ExportPage>,
    coverPageId: String?,
): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
    append("<ComicInfo xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
    append("xsi:noNamespaceSchemaLocation=\"ComicInfo.xsd\">\n")
    append("  <Title>").append(xmlEscape(title)).append("</Title>\n")
    append("  <PageCount>").append(pages.size).append("</PageCount>\n")
    append("  <Pages>\n")
    pages.forEachIndexed { index, page ->
        append("    <Page Image=\"").append(index).append('"')
        if (page.id == coverPageId) append(" Type=\"FrontCover\"")
        append(" />\n")
    }
    append("  </Pages>\n")
    append("</ComicInfo>\n")
}

private fun xmlEscape(value: String): String =
    buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }

private inline fun atomicWrite(file: File, write: (OutputStream) -> Unit) {
    file.parentFile?.mkdirs()
    val temporaryFile = File(file.parentFile, "${file.name}.tmp")
    try {
        temporaryFile.outputStream().use(write)
        if (file.exists() && !file.delete()) throw IOException("无法替换旧的导出文件")
        if (!temporaryFile.renameTo(file)) throw IOException("无法完成图册导出")
    } catch (error: Throwable) {
        temporaryFile.delete()
        throw error
    }
}
