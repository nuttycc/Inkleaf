package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.PageDescriptor

internal fun resolveOnlinePageReference(
    pageId: String?,
    fallbackPageIndex: Int?,
    fallbackChapterRevision: String?,
    currentChapterRevision: String?,
    pages: List<PageDescriptor>,
): Int? {
    pageId
        ?.let { candidate -> pages.indexOfFirst { it.pageId == candidate } }
        ?.takeIf { it >= 0 }
        ?.let { return it }
    return fallbackPageIndex?.takeIf {
        fallbackChapterRevision == currentChapterRevision && it in pages.indices
    }
}
