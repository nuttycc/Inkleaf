package com.exio.inkleaf.ui

import com.exio.inkleaf.data.OnlinePageIdentity
import com.exio.inkleaf.plugin.ChapterSummary
import com.exio.inkleaf.plugin.OnlinePageBookmark
import com.exio.inkleaf.plugin.PageDescriptor

/**
 * Builds book-wide bookmark rows. Storage already keeps every bookmark of a book together; this
 * function only decides how each location is presented without opening other chapters.
 */
internal fun onlineBookmarkItems(
    bookmarks: List<OnlinePageBookmark>,
    chapters: List<ChapterSummary>,
    currentChapterId: String?,
    currentChapterRevision: String?,
    currentChapterPageCount: Int,
    currentPageDescriptors: List<PageDescriptor>?,
    keyOf: (OnlinePageIdentity) -> String,
): List<ReaderBookmarkItem> = bookmarks.map { bookmark ->
    val identity = bookmark.location.identity
    val chapterId = identity.chapter.chapterId
    val chapterIndex = chapters.indexOfFirst { it.chapterId == chapterId }
    val chapter = chapters.getOrNull(chapterIndex)
    val pageId = identity.pageId

    if (chapter == null || !chapter.available) {
        return@map ReaderBookmarkItem(
            key = keyOf(identity),
            target =
                ReaderBookmarkTarget.Online(
                    chapterId = chapterId,
                    chapterPageIndex = null,
                    pageId = pageId,
                ),
            chapterOrder = Int.MAX_VALUE,
            pageIndex = null,
            globalPage = null,
            chapterTitle =
                bookmark.chapterTitleSnapshot?.takeIf(String::isNotBlank)
                    ?: chapter?.title?.takeIf(String::isNotBlank)
                    ?: "未知章节",
            stale = false,
            available = false,
            addedAtMs = bookmark.addedAtMs,
            thumbnailPage = null,
        )
    }

    val isCurrentChapter = chapterId == currentChapterId && currentPageDescriptors != null
    val revisionChanged =
        chapter.revision != null &&
            bookmark.chapterRevision() != null &&
            bookmark.chapterRevision() != chapter.revision

    val page =
        if (isCurrentChapter) {
            resolveCurrentChapterPage(
                identity = identity,
                bookmark = bookmark,
                currentChapterRevision = currentChapterRevision,
                pages = checkNotNull(currentPageDescriptors),
                pageCount = currentChapterPageCount,
            )
        } else {
            val knownPage = identity.fallback?.pageIndex
            BookmarkPage(page = knownPage, stale = revisionChanged)
        }

    ReaderBookmarkItem(
        key = keyOf(identity),
        target =
            ReaderBookmarkTarget.Online(
                chapterId = chapterId,
                chapterPageIndex = page.page,
                pageId = pageId,
            ),
        chapterOrder = chapterIndex,
        pageIndex = page.page,
        globalPage = null,
        chapterTitle =
            bookmark.chapterTitleSnapshot?.takeIf(String::isNotBlank)
                ?: chapter.title.ifBlank { "未命名章节" },
        stale = page.stale,
        available = true,
        addedAtMs = bookmark.addedAtMs,
        thumbnailPage = if (isCurrentChapter) page.page else null,
    )
}

/** Page shown for a bookmark plus whether it is only an approximate location. */
internal data class BookmarkPage(val page: Int?, val stale: Boolean)

private fun OnlinePageBookmark.chapterRevision(): String? =
    location.identity.fallback?.chapterRevision ?: location.chapterRevision

private fun resolveCurrentChapterPage(
    identity: OnlinePageIdentity,
    bookmark: OnlinePageBookmark,
    currentChapterRevision: String?,
    pages: List<PageDescriptor>,
    pageCount: Int,
): BookmarkPage {
    val resolved =
        resolveOnlinePageReference(
            pageId = identity.pageId,
            fallbackPageIndex = identity.fallback?.pageIndex,
            fallbackChapterRevision = identity.fallback?.chapterRevision,
            currentChapterRevision = currentChapterRevision,
            pages = pages,
        )
    if (resolved != null) return BookmarkPage(page = resolved, stale = false)
    val fallback = bookmark.location.pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    return BookmarkPage(page = fallback, stale = true)
}

/**
 * Resolves the page to open after a chapter switch completes. A stable page ID wins; otherwise the
 * recorded index is clamped and reported as approximate.
 */
internal fun resolveOnlineBookmarkJump(
    target: ReaderBookmarkTarget.Online,
    currentRevision: String,
    pages: List<PageDescriptor>,
): OnlineBookmarkJump {
    val pageIdPage =
        target.pageId?.let { pageId ->
            pages.indexOfFirst { it.pageId == pageId }.takeIf { it >= 0 }
        }
    if (pageIdPage != null) return OnlineBookmarkJump(page = pageIdPage, approximate = false)

    val requested = target.chapterPageIndex
    val page = requested?.takeIf { it in pages.indices } ?: 0
    return OnlineBookmarkJump(
        page = page,
        approximate = target.pageId != null || requested == null || requested != page,
    )
}

internal data class OnlineBookmarkJump(val page: Int, val approximate: Boolean)
