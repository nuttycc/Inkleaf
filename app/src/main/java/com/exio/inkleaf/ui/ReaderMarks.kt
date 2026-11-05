package com.exio.inkleaf.ui

import com.exio.inkleaf.data.BookmarkRepository
import com.exio.inkleaf.data.BookmarkToggleResult
import com.exio.inkleaf.data.ComicVolume
import com.exio.inkleaf.data.FavoriteRepository
import com.exio.inkleaf.data.db.ComicEntity
import com.exio.inkleaf.data.db.FavoritePageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The page a reader action points at.
 *
 * Both readers answer "which page is this" through [PageIdentity]; the actions used to pass a bare
 * page index instead, so each reader resolved it its own way. The reference is the action-facing
 * shape of that answer: [namespace] and [pageIdentity] identify the page, while [anchorPage] only
 * carries the index the storage layer needs to read or write it. [anchorPage] is deliberately
 * excluded from equality — the same page re-resolved to another index is not another page.
 */
internal data class ReaderPageRef(
    val namespace: String,
    val pageIdentity: String,
    val anchorPage: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is ReaderPageRef && namespace == other.namespace && pageIdentity == other.pageIdentity

    override fun hashCode(): Int = 31 * namespace.hashCode() + pageIdentity.hashCode()
}

internal fun ReaderPageRef.stateKey(): ReaderPageStateKey =
    ReaderPageStateKey(namespace = namespace, pageIdentity = pageIdentity)

internal enum class ReaderMarkKind {
    BOOKMARK,
    FAVORITE,
}

internal sealed interface ReaderMarkOutcome {
    data class Added(val kind: ReaderMarkKind) : ReaderMarkOutcome

    data class Removed(val kind: ReaderMarkKind) : ReaderMarkOutcome

    data class Failed(val kind: ReaderMarkKind, val error: Throwable) : ReaderMarkOutcome

    data object Ignored : ReaderMarkOutcome
}

/** The single owner of what a reader sees after a mark action. */
internal fun ReaderMarkOutcome.message(): String? =
    when (this) {
        is ReaderMarkOutcome.Added -> if (kind == ReaderMarkKind.BOOKMARK) "已添加书签" else "已收藏"
        is ReaderMarkOutcome.Removed -> if (kind == ReaderMarkKind.BOOKMARK) "已移除书签" else "已取消收藏"
        is ReaderMarkOutcome.Failed -> {
            val action = if (kind == ReaderMarkKind.BOOKMARK) "书签操作失败" else "收藏失败"
            error.message?.let { "$action：$it" } ?: action
        }
        ReaderMarkOutcome.Ignored -> null
    }

/**
 * What both readers record about the page the reader is on.
 *
 * Each reader supplies an adapter — Room bookmarks and favorite snapshots offline, plugin records
 * online — while the actions, their ordering and their wording live here.
 */
internal interface ReaderMarks {
    suspend fun toggleBookmark(page: ReaderPageRef): ReaderMarkOutcome

    suspend fun toggleFavorite(page: ReaderPageRef): ReaderMarkOutcome
}

/** Drops a repeated tap on the page that is already being written. */
internal class ReaderMarkGate {
    private val inFlight = mutableSetOf<ReaderPageRef>()

    fun enter(page: ReaderPageRef): Boolean = inFlight.add(page)

    fun leave(page: ReaderPageRef) {
        inFlight -= page
    }
}

/**
 * Offline adapter: Room bookmarks plus favorite snapshots.
 *
 * [bookmarkMutationMutex] is shared with the bookmark sheet's remove/restore, so a toolbar tap and
 * a list swipe never interleave. [favoriteAt] answers "is this page already a favorite" from the
 * observable projection the reader already keeps.
 */
internal class OfflineReaderMarks(
    private val bookmarks: BookmarkRepository,
    private val favorites: FavoriteRepository,
    private val comic: ComicEntity,
    private val volume: ComicVolume,
    private val favoriteAt: (Int) -> FavoritePageEntity?,
    private val bookmarkMutationMutex: Mutex,
) : ReaderMarks {
    private val bookmarkGate = ReaderMarkGate()
    private val favoriteGate = ReaderMarkGate()

    override suspend fun toggleBookmark(page: ReaderPageRef): ReaderMarkOutcome {
        if (!bookmarkGate.enter(page)) return ReaderMarkOutcome.Ignored
        return try {
            val result = bookmarkMutationMutex.withLock {
                bookmarks.toggle(comic, volume, page.anchorPage)
            }
            when (result) {
                is BookmarkToggleResult.Added -> ReaderMarkOutcome.Added(ReaderMarkKind.BOOKMARK)
                is BookmarkToggleResult.Removed ->
                    ReaderMarkOutcome.Removed(ReaderMarkKind.BOOKMARK)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReaderMarkOutcome.Failed(ReaderMarkKind.BOOKMARK, e)
        } finally {
            bookmarkGate.leave(page)
        }
    }

    override suspend fun toggleFavorite(page: ReaderPageRef): ReaderMarkOutcome {
        if (!favoriteGate.enter(page)) return ReaderMarkOutcome.Ignored
        return try {
            val existing = favoriteAt(page.anchorPage)
            if (existing != null) {
                favorites.remove(existing)
                ReaderMarkOutcome.Removed(ReaderMarkKind.FAVORITE)
            } else {
                val bytes = volume.loadPageBytes(page.anchorPage)
                favorites.addSnapshot(comic, page.anchorPage, volume.totalPageCount, bytes)
                ReaderMarkOutcome.Added(ReaderMarkKind.FAVORITE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReaderMarkOutcome.Failed(ReaderMarkKind.FAVORITE, e)
        } finally {
            favoriteGate.leave(page)
        }
    }
}
