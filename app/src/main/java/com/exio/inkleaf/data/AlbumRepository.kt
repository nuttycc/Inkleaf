package com.exio.inkleaf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.exio.inkleaf.data.db.AlbumPageEntity
import com.exio.inkleaf.data.db.AppDatabase
import com.exio.inkleaf.data.db.BookSourceType
import com.exio.inkleaf.data.db.ComicEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

data class AlbumPageDraft(
    val id: String,
    /** Absolute path. Staged files live in cache; saved files live below filesDir. */
    val filePath: String,
    val displayName: String,
    val extension: String,
    val isStaged: Boolean,
)

data class AlbumImportResult(
    val pages: List<AlbumPageDraft>,
    val failedNames: List<String>,
)

data class AlbumSnapshot(
    val comic: ComicEntity,
    val pages: List<AlbumPageDraft>,
)

/** Pure progress mapping shared by album editing and unit tests. */
object AlbumProgress {
    data class Result(val pageIndex: Int, val pageId: String?)

    fun remap(
        oldPageIds: List<String>,
        newPageIds: List<String>,
        lastReadPageId: String?,
        lastReadPageIndex: Int,
    ): Result {
        if (newPageIds.isEmpty()) return Result(0, null)

        val oldIndex = lastReadPageId
            ?.let(oldPageIds::indexOf)
            ?.takeIf { it >= 0 }
            ?: lastReadPageIndex.coerceIn(0, (oldPageIds.size - 1).coerceAtLeast(0))
        val currentId = lastReadPageId ?: oldPageIds.getOrNull(oldIndex)
        currentId?.let { id ->
            newPageIds.indexOf(id).takeIf { it >= 0 }?.let { index ->
                return Result(index, newPageIds[index])
            }
        }

        // Prefer the following old page, then the previous one, so deleting the
        // current page continues forward while still tracking subsequent reorders.
        for (distance in 1..oldPageIds.size) {
            val nextId = oldPageIds.getOrNull(oldIndex + distance)
            nextId?.let { id ->
                newPageIds.indexOf(id).takeIf { it >= 0 }?.let { index ->
                    return Result(index, newPageIds[index])
                }
            }
            val previousId = oldPageIds.getOrNull(oldIndex - distance)
            previousId?.let { id ->
                newPageIds.indexOf(id).takeIf { it >= 0 }?.let { index ->
                    return Result(index, newPageIds[index])
                }
            }
        }

        val fallback = oldIndex.coerceIn(newPageIds.indices)
        return Result(fallback, newPageIds[fallback])
    }
}

/** Owns album import sessions, private files, and Room records. */
class AlbumRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val db = AppDatabase.getInstance(appContext)
    private val comicDao = db.comicDao()
    private val pageDao = db.albumPageDao()

    fun newSessionId(): String = UUID.randomUUID().toString()

    suspend fun loadAlbum(comicId: Long): AlbumSnapshot {
        val comic = comicDao.getById(comicId)
            ?.takeIf { it.sourceType == BookSourceType.CREATED_ALBUM }
            ?: throw IllegalArgumentException("图册不存在")
        val pages = pageDao.getByComicId(comicId).map { it.toDraft() }
        return AlbumSnapshot(comic, pages)
    }

    suspend fun stageUris(sessionId: String, uris: List<Uri>): AlbumImportResult =
        albumFileMutex.withLock {
            withContext(Dispatchers.IO) {
                requireSessionId(sessionId)
                val pages = mutableListOf<AlbumPageDraft>()
                val failures = mutableListOf<String>()
                uris.forEach { uri ->
                    val name = displayName(uri)
                    val page = catchImportFailure { stageUri(sessionId, uri, name) }
                    if (page == null) failures += name else pages += page
                }
                AlbumImportResult(pages, failures)
            }
        }

    suspend fun stageFolder(sessionId: String, treeUri: Uri): AlbumImportResult {
        val documents = withContext(Dispatchers.IO) {
            requireSessionId(sessionId)
            val root = DocumentFile.fromTreeUri(appContext, treeUri)
                ?: throw IllegalArgumentException("无法打开所选文件夹")
            root.listFiles()
                .filter { it.isFile && isImageCandidate(it.name, it.type) }
                .sortedWith { a, b ->
                    ChapterSort.compareNatural(a.name.orEmpty(), b.name.orEmpty())
                }
        }
        return albumFileMutex.withLock {
            withContext(Dispatchers.IO) {
                val pages = mutableListOf<AlbumPageDraft>()
                val failures = mutableListOf<String>()
                documents.forEach { document ->
                    val name = document.name ?: "未命名图片"
                    val page = catchImportFailure { stageUri(sessionId, document.uri, name) }
                    if (page == null) failures += name else pages += page
                }
                AlbumImportResult(pages, failures)
            }
        }
    }

    suspend fun saveAlbum(
        comicId: Long?,
        title: String,
        orderedPages: List<AlbumPageDraft>,
        coverPageId: String?,
    ): Long = albumFileMutex.withLock {
        withContext(Dispatchers.IO) {
            require(orderedPages.isNotEmpty()) { "图册至少需要一张图片" }
            require(orderedPages.map { it.id }.distinct().size == orderedPages.size) {
                "图册页面标识不能重复"
            }

            val normalizedTitle = title.trim().ifEmpty { "新建图册" }
            val existingComic = comicId?.let { id ->
                comicDao.getById(id)?.takeIf { it.sourceType == BookSourceType.CREATED_ALBUM }
                    ?: throw IllegalArgumentException("图册不存在")
            }
            val oldPages = existingComic?.let { pageDao.getByComicId(it.id) }.orEmpty()
            val oldById = oldPages.associateBy { it.id }
            validateDrafts(orderedPages, oldById)

            var createdId: Long? = null
            val albumId = existingComic?.id ?: db.withTransaction {
                val token = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val id = comicDao.insert(
                    ComicEntity(
                        uri = "inkleaf://album/pending/$token",
                        fileKey = "album:pending:$token",
                        title = normalizedTitle,
                        addedAt = now,
                        lastReadAt = now,
                        sourceType = BookSourceType.CREATED_ALBUM,
                        isDraft = true,
                    )
                )
                check(id != -1L) { "无法创建图册记录" }
                comicDao.updateIdentity(id, albumUri(id), albumFileKey(id))
                id
            }.also { createdId = it }

            val writtenFiles = mutableListOf<File>()
            var generatedCover: File? = null
            try {
                val entities = materializePages(albumId, orderedPages, oldById, writtenFiles)
                val pageIds = entities.map { it.id }
                val selectedCoverId = coverPageId?.takeIf(pageIds::contains) ?: pageIds.first()
                val progress = AlbumProgress.remap(
                    oldPageIds = oldPages.map { it.id },
                    newPageIds = pageIds,
                    lastReadPageId = existingComic?.lastReadPageId,
                    lastReadPageIndex = existingComic?.lastReadPage ?: 0,
                )

                val selectedPage = entities.first { it.id == selectedCoverId }
                val oldCoverCanBeReused = existingComic?.let { comic ->
                    comic.coverPageId == selectedCoverId && comic.coverPath?.let(::File)?.isFile == true
                } == true
                val coverPath = if (oldCoverCanBeReused) {
                    existingComic.coverPath
                } else {
                    generatedCover = Covers.createCoverFile(
                        appContext,
                        albumId,
                        resolveAlbumPageFile(appContext.filesDir, selectedPage.relativePath),
                    )
                    generatedCover?.absolutePath
                }

                db.withTransaction {
                    pageDao.deleteByComicId(albumId)
                    pageDao.insertAll(entities)
                    comicDao.updateAlbumMetadata(
                        id = albumId,
                        title = normalizedTitle,
                        pageCount = entities.size,
                        lastReadPage = progress.pageIndex,
                        lastReadPageId = progress.pageId,
                        coverPageId = selectedCoverId,
                    )
                    comicDao.updateAlbumCover(albumId, coverPath, selectedCoverId)
                }

                deleteRemovedPageFiles(oldPages, entities)
                orderedPages.filter { it.isStaged }.forEach { File(it.filePath).delete() }
                pruneEmptySessionDirectories()
                generatedCover?.let { newCover ->
                    existingComic?.coverPath
                        ?.let(::File)
                        ?.takeIf { it.absolutePath != newCover.absolutePath }
                        ?.delete()
                }
                albumId
            } catch (error: Throwable) {
                generatedCover?.delete()
                writtenFiles.forEach(File::delete)
                if (createdId != null) {
                    comicDao.deleteById(createdId!!)
                    albumDirectory(createdId!!).deleteRecursively()
                }
                throw error
            }
        }
    }

    suspend fun discardSession(sessionId: String) = albumFileMutex.withLock {
        withContext(Dispatchers.IO) {
            requireSessionId(sessionId)
            sessionDirectory(sessionId).deleteRecursively()
        }
    }

    fun discardSessionNow(sessionId: String) {
        requireSessionId(sessionId)
        sessionDirectory(sessionId).deleteRecursively()
    }

    /** Removes abandoned staging data and reconciles restored/missing album files. */
    suspend fun cleanupOnColdStart() = albumFileMutex.withLock {
        withContext(Dispatchers.IO) {
            stagingRoot().deleteRecursively()
            val allAlbums = comicDao.getBySourceType(BookSourceType.CREATED_ALBUM)
            val drafts = allAlbums.filter { it.isDraft }
            drafts.forEach { comic ->
                comicDao.deleteById(comic.id)
                albumDirectory(comic.id).deleteRecursively()
            }
            val albums = allAlbums.filterNot { it.isDraft }
            val pagesByComicId = pageDao.getAll().groupBy { it.comicId }
            val albumIds = albums.map { it.id }.toSet()
            albumsRoot().listFiles().orEmpty().forEach { directory ->
                val directoryId = directory.name.toLongOrNull()
                if (directory.isDirectory && (directoryId == null || directoryId !in albumIds)) {
                    directory.deleteRecursively()
                }
            }

            albums.forEach { comic ->
                val pages = pagesByComicId[comic.id].orEmpty()
                val referenced = pages.mapNotNull { page ->
                    runCatching {
                        resolveAlbumPageFile(appContext.filesDir, page.relativePath).canonicalPath
                    }.getOrNull()
                }.toSet()
                pagesDirectory(comic.id).listFiles().orEmpty().forEach { file ->
                    if (file.isFile && file.canonicalPath !in referenced) file.delete()
                }
                val missing = pages.isEmpty() || pages.any { page ->
                    runCatching {
                        !resolveAlbumPageFile(appContext.filesDir, page.relativePath).isFile
                    }.getOrDefault(true)
                }
                if (missing != comic.isMissing) comicDao.setMissing(listOf(comic.id), missing)

                val referencedCover = comic.coverPath?.let(::File)?.absolutePath
                File(appContext.filesDir, "covers").listFiles().orEmpty()
                    .filter { it.isFile && it.name.startsWith("${comic.id}-") }
                    .filter { it.absolutePath != referencedCover }
                    .forEach(File::delete)
            }
        }
    }

    private fun stageUri(sessionId: String, uri: Uri, displayName: String): AlbumPageDraft {
        val id = UUID.randomUUID().toString()
        val sourceExtension = extensionOf(displayName)
        val mimeType = resolver.getType(uri)?.lowercase()
        val preservedExtension = preservedExtension(sourceExtension, mimeType)
        val session = sessionDirectory(sessionId).apply { mkdirs() }

        if (preservedExtension != null) {
            val target = File(session, "$id.$preservedExtension")
            copyUriAtomically(uri, target, displayName)
            if (!hasDecodableBounds(target)) {
                target.delete()
                throw IllegalArgumentException("不支持或已损坏的图片：$displayName")
            }
            return AlbumPageDraft(id, target.absolutePath, displayName, preservedExtension, true)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)
            ?.use { input -> BitmapFactory.decodeStream(input, null, bounds) }
            ?: throw IllegalArgumentException("无法读取：$displayName")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("不支持或已损坏的图片：$displayName")
        }
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (pixels > MAX_CONVERT_PIXELS) {
            throw IllegalArgumentException("图片分辨率过高，无法安全转换：$displayName")
        }

        val bitmap =
            resolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input) }
                ?: throw IllegalArgumentException("不支持或已损坏的图片：$displayName")
        return try {
            val extension = if (bitmap.hasAlpha()) "png" else "jpg"
            val target = File(session, "$id.$extension")
            val temporary = File(session, ".$id.$extension.tmp")
            val format =
                if (extension == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val quality = if (extension == "png") 100 else 95
            try {
                temporary.outputStream().use { output ->
                    check(bitmap.compress(format, quality, output)) { "图片转换失败：$displayName" }
                }
                if (!temporary.renameTo(target)) throw IOException("无法保存转换后的图片：$displayName")
            } finally {
                temporary.delete()
            }
            AlbumPageDraft(id, target.absolutePath, displayName, extension, true)
        } finally {
            bitmap.recycle()
        }
    }

    private fun materializePages(
        albumId: Long,
        drafts: List<AlbumPageDraft>,
        oldById: Map<String, AlbumPageEntity>,
        writtenFiles: MutableList<File>,
    ): List<AlbumPageEntity> {
        val directory = pagesDirectory(albumId).apply { mkdirs() }
        return drafts.mapIndexed { position, draft ->
            val oldPage = oldById[draft.id]
            val extension = (oldPage?.extension ?: draft.extension).normalizeExtension()
            val relativePath = albumRelativePath(albumId, draft.id, extension)
            val target = File(appContext.filesDir, relativePath)
            if (draft.isStaged) {
                val source = File(draft.filePath)
                val temporary = File(directory, ".${draft.id}.$extension.tmp")
                try {
                    temporary.delete()
                    source.inputStream().use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (!temporary.renameTo(target)) throw IOException("无法保存图册页面")
                    writtenFiles += target
                } finally {
                    temporary.delete()
                }
            }
            AlbumPageEntity(
                id = draft.id,
                comicId = albumId,
                position = position,
                relativePath = relativePath,
                displayName = draft.displayName,
                extension = extension,
            )
        }
    }

    private fun validateDrafts(
        drafts: List<AlbumPageDraft>,
        oldById: Map<String, AlbumPageEntity>,
    ) {
        drafts.forEach { draft ->
            require(draft.id.isNotBlank()) { "图册页面标识不能为空" }
            draft.extension.normalizeExtension()
            val file = if (draft.isStaged) {
                File(draft.filePath).also { staged ->
                    require(staged.isWithin(stagingRoot())) { "暂存图片路径无效" }
                }
            } else {
                val old = oldById[draft.id]
                    ?: throw IllegalArgumentException("页面不属于当前图册")
                resolveAlbumPageFile(appContext.filesDir, old.relativePath)
            }
            require(file.isFile) { "图册页面不存在：${draft.displayName}" }
        }
    }

    private fun deleteRemovedPageFiles(
        oldPages: List<AlbumPageEntity>,
        newPages: List<AlbumPageEntity>,
    ) {
        val retained = newPages.map { it.relativePath }.toSet()
        oldPages.filter { it.relativePath !in retained }.forEach { page ->
            runCatching { resolveAlbumPageFile(appContext.filesDir, page.relativePath).delete() }
        }
    }

    private fun AlbumPageEntity.toDraft(): AlbumPageDraft = AlbumPageDraft(
        id = id,
        filePath = resolveAlbumPageFile(appContext.filesDir, relativePath).absolutePath,
        displayName = displayName,
        extension = extension,
        isStaged = false,
    )

    private fun displayName(uri: Uri): String =
        DocumentFile.fromSingleUri(appContext, uri)?.name
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "未命名图片"

    private fun isImageCandidate(name: String?, mimeType: String?): Boolean {
        if (mimeType?.startsWith("image/") == true) return true
        return extensionOf(name.orEmpty()) in IMAGE_EXTENSIONS
    }

    private fun preservedExtension(extension: String, mimeType: String?): String? = when {
        extension in PRESERVED_EXTENSIONS -> extension.normalizePreservedExtension()
        mimeType == "image/jpeg" -> "jpg"
        mimeType == "image/png" -> "png"
        mimeType == "image/webp" -> "webp"
        mimeType == "image/gif" -> "gif"
        else -> null
    }

    private fun hasDecodableBounds(file: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    private fun copyUriAtomically(uri: Uri, target: File, displayName: String) {
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        try {
            resolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("无法读取：$displayName")
            if (!temporary.renameTo(target)) throw IOException("无法保存图片：$displayName")
        } finally {
            temporary.delete()
        }
    }

    private inline fun <T> catchImportFailure(block: () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun pruneEmptySessionDirectories() {
        stagingRoot().listFiles().orEmpty().forEach { directory ->
            if (directory.isDirectory && directory.list().isNullOrEmpty()) directory.delete()
        }
    }

    private fun requireSessionId(sessionId: String) {
        require(SESSION_ID.matches(sessionId)) { "图册编辑会话无效" }
    }

    private fun String.normalizeExtension(): String {
        val normalized = lowercase().removePrefix(".")
        require(normalized in PRESERVED_EXTENSIONS) { "不支持保存该图片格式：$this" }
        return normalized.normalizePreservedExtension()
    }

    private fun String.normalizePreservedExtension(): String = if (this == "jpeg") "jpg" else this

    private fun File.isWithin(parent: File): Boolean {
        val parentPath = parent.canonicalFile.toPath()
        return canonicalFile.toPath().startsWith(parentPath)
    }

    private fun sessionDirectory(sessionId: String) = File(stagingRoot(), sessionId)
    private fun stagingRoot() = File(appContext.cacheDir, "album_staging")
    private fun albumsRoot() = File(appContext.filesDir, "albums")
    private fun albumDirectory(comicId: Long) = File(albumsRoot(), comicId.toString())
    private fun pagesDirectory(comicId: Long) = File(albumDirectory(comicId), "pages")

    companion object {
        private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private const val MAX_CONVERT_PIXELS = 16_000_000L
        private val PRESERVED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
        private val IMAGE_EXTENSIONS = PRESERVED_EXTENSIONS +
                setOf("heic", "heif", "avif", "bmp", "tif", "tiff")

        fun albumUri(comicId: Long): String = "inkleaf://album/$comicId"
        fun albumFileKey(comicId: Long): String = "album:$comicId"
        fun albumRelativePath(comicId: Long, pageId: String, extension: String): String =
            "albums/$comicId/pages/$pageId.$extension"

        private fun extensionOf(name: String): String =
            name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    }
}
