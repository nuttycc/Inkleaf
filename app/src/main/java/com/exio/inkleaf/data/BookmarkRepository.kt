package com.exio.inkleaf.data

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.room.withTransaction
import com.exio.inkleaf.data.db.AppDatabase
import com.exio.inkleaf.data.db.BookSourceType
import com.exio.inkleaf.data.db.BookmarkEntity
import com.exio.inkleaf.data.db.BookmarkWithComic
import com.exio.inkleaf.data.db.ComicEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

sealed interface BookmarkToggleResult {
    data class Added(val bookmark: BookmarkEntity) : BookmarkToggleResult

    data class Removed(val bookmark: BookmarkEntity) : BookmarkToggleResult
}

sealed interface BookmarkResolution {
    data class Ready(val comicId: Long, val globalPage: Int) : BookmarkResolution

    data class SourceChanged(
        val comicId: Long,
        val approximateGlobalPage: Int,
    ) : BookmarkResolution

    data class Unavailable(val message: String) : BookmarkResolution
}

class BookmarkRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val bookmarkDao = db.bookmarkDao()
    private val comicDao = db.comicDao()
    private val comicRepository = ComicRepository(appContext)

    fun observeAll(): Flow<List<BookmarkWithComic>> = bookmarkDao.observeAll()

    fun observeForComic(comicId: Long): Flow<List<BookmarkEntity>> =
        bookmarkDao.observeForComic(comicId)

    suspend fun toggle(
        comic: ComicEntity,
        volume: ComicVolume,
        globalPage: Int,
    ): BookmarkToggleResult {
        require(comic.id > 0) { "书签只能属于已保存的漫画" }
        require(volume.totalPageCount > 0) { "漫画中没有可添加书签的页面" }

        val normalizedGlobalPage = globalPage.coerceIn(0, volume.totalPageCount - 1)
        val location = volume.globalToChapterPage(normalizedGlobalPage)
        val pageIdentity = volume.pageIdentity(normalizedGlobalPage)
        val targetKey =
            bookmarkTargetKey(
                pageIdentity = pageIdentity,
                sourceRevision = volume.sourceRevision,
                globalPageIndex = normalizedGlobalPage,
            )

        return db.withTransaction {
            val matches =
                bookmarkMatchesForCurrentPage(
                    bookmarks = bookmarkDao.getForComic(comic.id),
                    comicId = comic.id,
                    sourceType = comic.sourceType,
                    currentSourceRevision = volume.sourceRevision,
                    currentPageCount = volume.totalPageCount,
                    currentGlobalPage = normalizedGlobalPage,
                    findPageByIdentity = volume::findPageByIdentity,
                )
            if (matches.ready.isNotEmpty()) {
                bookmarkDao.deleteByIds(matches.ready.map(BookmarkEntity::id))
                val removed =
                    matches.ready.firstOrNull { it.targetKey == targetKey } ?: matches.ready.first()
                BookmarkToggleResult.Removed(removed)
            } else {
                // A stale PDF bookmark can retain the same chapter/page identity after the file
                // changes. Refresh only that exact logical target; unrelated approximate matches
                // remain available for explicit review in the bookmark sheet.
                bookmarkDao.getByTargetKey(comic.id, targetKey)?.let { staleSameTarget ->
                    bookmarkDao.deleteById(staleSameTarget.id)
                }
                val candidate =
                    BookmarkEntity(
                        comicId = comic.id,
                        targetKey = targetKey,
                        pageIdentity = pageIdentity,
                        sourceRevision = volume.sourceRevision,
                        globalPageIndex = normalizedGlobalPage,
                        chapterIndex = location.chapterIndex,
                        pageIndex = location.pageIndex,
                        chapterTitle = volume.chapterTitle(location.chapterIndex),
                        addedAt = System.currentTimeMillis(),
                    )
                val id = bookmarkDao.insert(candidate)
                val inserted =
                    if (id != -1L) {
                        candidate.copy(id = id)
                    } else {
                        bookmarkDao.getByTargetKey(comic.id, targetKey) ?: candidate
                    }
                BookmarkToggleResult.Added(inserted)
            }
        }
    }

    suspend fun remove(bookmark: BookmarkEntity) {
        bookmarkDao.deleteById(bookmark.id)
    }

    /** Restores a removed bookmark for undo without relying on its previous row ID. */
    suspend fun restore(bookmark: BookmarkEntity): BookmarkEntity = db.withTransaction {
        val candidate = bookmark.copy(id = 0)
        val id = bookmarkDao.insert(candidate)
        if (id != -1L) {
            candidate.copy(id = id)
        } else {
            bookmarkDao.getByTargetKey(bookmark.comicId, bookmark.targetKey) ?: candidate
        }
    }

    /** Reads the disposable reader cache first and rebuilds a missing thumbnail on demand. */
    suspend fun loadThumbnail(bookmark: BookmarkEntity): ImageBitmap? =
        withContext(Dispatchers.IO) { loadThumbnailOnIo(bookmark) }

    suspend fun resolve(bookmark: BookmarkEntity): BookmarkResolution =
        withContext(Dispatchers.IO) { resolveOnIo(bookmark) }

    private suspend fun loadThumbnailOnIo(bookmark: BookmarkEntity): ImageBitmap? {
        val comic =
            comicDao.getById(bookmark.comicId)?.takeUnless { it.isMissing || it.isDraft }
                ?: return null
        val volume =
            try {
                comicRepository.openBook(comic)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
        return try {
            val page =
                resolveBookmarkLocation(
                        comicId = bookmark.comicId,
                        sourceType = comic.sourceType,
                        storedSourceRevision = bookmark.sourceRevision,
                        storedGlobalPage = bookmark.globalPageIndex,
                        pageIdentity = bookmark.pageIdentity,
                        currentSourceRevision = volume.sourceRevision,
                        currentPageCount = volume.totalPageCount,
                        findPageByIdentity = volume::findPageByIdentity,
                    )
                    .thumbnailPageOrNull() ?: return null
            val currentIdentity = volume.pageIdentity(page)
            ReaderCache.readThumbnail(
                    context = appContext,
                    comicId = bookmark.comicId,
                    page = page,
                    pageIdentity = currentIdentity,
                )
                ?.let {
                    return it.asImageBitmap()
                }

            val rendered = volume.renderThumbnail(page, BOOKMARK_THUMBNAIL_WIDTH) ?: return null
            ReaderCache.writeThumbnail(
                context = appContext,
                comicId = bookmark.comicId,
                page = page,
                pageIdentity = currentIdentity,
                bitmap = rendered.asAndroidBitmap(),
            )
            rendered
        } finally {
            volume.close()
        }
    }

    private suspend fun resolveOnIo(bookmark: BookmarkEntity): BookmarkResolution {
        val comic =
            comicDao.getById(bookmark.comicId)
                ?: return BookmarkResolution.Unavailable("书签所属漫画已从书架移除")
        if (comic.isMissing) {
            return BookmarkResolution.Unavailable("漫画源文件当前不可用")
        }
        if (comic.isDraft) {
            return BookmarkResolution.Unavailable("漫画尚未完成保存")
        }

        val volume =
            try {
                comicRepository.openBook(comic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return BookmarkResolution.Unavailable(e.message ?: "无法打开漫画")
            }
        return try {
            resolveBookmarkLocation(
                comicId = comic.id,
                sourceType = comic.sourceType,
                storedSourceRevision = bookmark.sourceRevision,
                storedGlobalPage = bookmark.globalPageIndex,
                pageIdentity = bookmark.pageIdentity,
                currentSourceRevision = volume.sourceRevision,
                currentPageCount = volume.totalPageCount,
                findPageByIdentity = volume::findPageByIdentity,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BookmarkResolution.Unavailable(e.message ?: "无法定位书签页面")
        } finally {
            volume.close()
        }
    }

    private companion object {
        const val BOOKMARK_THUMBNAIL_WIDTH = 360
    }
}

internal data class BookmarkPageMatches(
    val ready: List<BookmarkEntity>,
    val sourceChanged: List<BookmarkEntity>,
)

internal fun bookmarkMatchesForCurrentPage(
    bookmarks: List<BookmarkEntity>,
    comicId: Long,
    sourceType: BookSourceType,
    currentSourceRevision: String,
    currentPageCount: Int,
    currentGlobalPage: Int,
    findPageByIdentity: (String) -> Int?,
): BookmarkPageMatches {
    val ready = mutableListOf<BookmarkEntity>()
    val sourceChanged = mutableListOf<BookmarkEntity>()
    bookmarks.forEach { bookmark ->
        when (
            val resolution =
                resolveBookmarkLocation(
                    comicId = comicId,
                    sourceType = sourceType,
                    storedSourceRevision = bookmark.sourceRevision,
                    storedGlobalPage = bookmark.globalPageIndex,
                    pageIdentity = bookmark.pageIdentity,
                    currentSourceRevision = currentSourceRevision,
                    currentPageCount = currentPageCount,
                    findPageByIdentity = findPageByIdentity,
                )
        ) {
            is BookmarkResolution.Ready -> {
                if (resolution.globalPage == currentGlobalPage) ready += bookmark
            }

            is BookmarkResolution.SourceChanged -> {
                if (resolution.approximateGlobalPage == currentGlobalPage) {
                    sourceChanged += bookmark
                }
            }

            is BookmarkResolution.Unavailable -> Unit
        }
    }
    return BookmarkPageMatches(ready = ready, sourceChanged = sourceChanged)
}

internal fun BookmarkResolution.thumbnailPageOrNull(): Int? =
    (this as? BookmarkResolution.Ready)?.globalPage

internal fun bookmarkTargetKey(
    pageIdentity: String?,
    sourceRevision: String,
    globalPageIndex: Int,
): String {
    require(sourceRevision.isNotBlank())
    require(globalPageIndex >= 0)
    val identity = pageIdentity?.takeIf { it.isNotBlank() }
    val parts =
        if (identity != null) {
            listOf("bookmark-page-v1", identity)
        } else {
            listOf("bookmark-position-v1", sourceRevision, globalPageIndex.toString())
        }
    return "bookmark:${ReaderPageCacheKey.sourceRevision(parts)}"
}

/**
 * Bookmark-facing wrapper around [ReadingPositionResolver]. Keeps comicId on the resolution so
 * Saved UI can navigate without a second lookup.
 */
internal fun resolveBookmarkLocation(
    comicId: Long,
    sourceType: BookSourceType,
    storedSourceRevision: String,
    storedGlobalPage: Int,
    pageIdentity: String?,
    currentSourceRevision: String,
    currentPageCount: Int,
    findPageByIdentity: (String) -> Int?,
): BookmarkResolution =
    when (
        val resolution =
            ReadingPositionResolver.resolve(
                sourceType = sourceType,
                storedSourceRevision = storedSourceRevision,
                storedGlobalPage = storedGlobalPage,
                pageIdentity = pageIdentity,
                currentSourceRevision = currentSourceRevision,
                currentPageCount = currentPageCount,
                findPageByIdentity = findPageByIdentity,
            )
    ) {
        is ReadingPositionResolution.Ready ->
            BookmarkResolution.Ready(comicId, resolution.globalPage)
        is ReadingPositionResolution.SourceChanged ->
            BookmarkResolution.SourceChanged(comicId, resolution.approximateGlobalPage)
        is ReadingPositionResolution.Unavailable ->
            BookmarkResolution.Unavailable(resolution.message)
    }
