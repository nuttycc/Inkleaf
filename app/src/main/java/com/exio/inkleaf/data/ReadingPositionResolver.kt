package com.exio.inkleaf.data

import com.exio.inkleaf.data.db.BookSourceType

/**
 * Shared reading-position resolution for bookmarks and history continue-reading.
 *
 * One contract for both features (#15 / #16):
 * - unchanged sourceRevision → use stored global page (clamped)
 * - ZIP/CBZ/album → remap by stable page identity when possible
 * - PDF series content change → SourceChanged with approximate page
 * - identity lost → SourceChanged with clamped approximate page
 * - empty book → Unavailable
 */
sealed interface ReadingPositionResolution {
    data class Ready(val globalPage: Int) : ReadingPositionResolution

    data class SourceChanged(val approximateGlobalPage: Int) : ReadingPositionResolution

    data class Unavailable(val message: String) : ReadingPositionResolution
}

object ReadingPositionResolver {
    fun resolve(
        sourceType: BookSourceType,
        storedSourceRevision: String,
        storedGlobalPage: Int,
        pageIdentity: String?,
        currentSourceRevision: String,
        currentPageCount: Int,
        findPageByIdentity: (String) -> Int?,
    ): ReadingPositionResolution {
        if (currentPageCount <= 0) {
            return ReadingPositionResolution.Unavailable("漫画中没有可读取的页面")
        }

        val approximatePage = storedGlobalPage.coerceIn(0, currentPageCount - 1)
        if (storedSourceRevision == currentSourceRevision) {
            return ReadingPositionResolution.Ready(approximatePage)
        }

        val remappedPage =
            pageIdentity
                ?.takeIf { it.isNotBlank() }
                ?.let(findPageByIdentity)
                ?.takeIf { it in 0 until currentPageCount }

        if (sourceType == BookSourceType.PDF_SERIES) {
            return ReadingPositionResolution.SourceChanged(remappedPage ?: approximatePage)
        }
        return if (remappedPage != null) {
            ReadingPositionResolution.Ready(remappedPage)
        } else {
            ReadingPositionResolution.SourceChanged(approximatePage)
        }
    }
}
