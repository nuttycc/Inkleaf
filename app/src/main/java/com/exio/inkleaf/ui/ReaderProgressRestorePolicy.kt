package com.exio.inkleaf.ui

/**
 * Keeps a route's first-open target separate from a restored reader state.
 *
 * A fresh explicit target wins once, while a route recreated after the reader has already been
 * opened follows the durable progress record. The route target remains a fallback when no durable
 * record exists yet.
 */
internal object ReaderProgressRestorePolicy {
    fun pageIndex(
        resumeFromPersistedPosition: Boolean,
        explicitPageIndex: Int?,
        persistedPageIndex: Int?,
        fallbackPageIndex: Int,
    ): Int =
        if (resumeFromPersistedPosition && persistedPageIndex != null) {
            persistedPageIndex
        } else {
            explicitPageIndex ?: persistedPageIndex ?: fallbackPageIndex
        }

    fun chapterId(
        resumeFromPersistedPosition: Boolean,
        requestedChapterId: String,
        persistedChapterId: String?,
        availableChapterIds: Set<String>,
    ): String =
        if (
            resumeFromPersistedPosition &&
                persistedChapterId != null &&
                persistedChapterId in availableChapterIds
        ) {
            persistedChapterId
        } else {
            requestedChapterId
        }

    fun shouldRestoreChapterMetadata(
        resumeFromPersistedPosition: Boolean,
        resolvedChapterId: String,
        persistedChapterId: String?,
    ): Boolean =
        resumeFromPersistedPosition && persistedChapterId == resolvedChapterId
}
