package com.exio.inkleaf.data

import kotlinx.serialization.Serializable

/** Stable, source-aware identity for content opened by the host reader. */
sealed interface ReaderContentIdentity

@Serializable
data class LocalContentIdentity(val fileKey: String) : ReaderContentIdentity {
    init {
        require(fileKey.isNotBlank()) { "fileKey must not be blank" }
    }
}

@Serializable
data class OnlineContentIdentity(
    val pluginId: String,
    val sourceId: String,
) : ReaderContentIdentity {
    init {
        require(pluginId.isNotBlank()) { "pluginId must not be blank" }
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
    }
}

@Serializable
data class OnlineChapterIdentity(
    val content: OnlineContentIdentity,
    val chapterId: String,
) {
    init {
        require(chapterId.isNotBlank()) { "chapterId must not be blank" }
    }
}

@Serializable
data class RevisionPageIndex(
    val chapterRevision: String,
    val pageIndex: Int,
) {
    init {
        require(chapterRevision.isNotBlank()) { "chapterRevision must not be blank" }
        require(pageIndex >= 0) { "pageIndex must be non-negative" }
    }
}

/**
 * An online page is identified by a source page ID when one exists. The fallback is deliberately
 * revision-bound so an index cannot silently identify different content after a source update.
 */
@Serializable
@ConsistentCopyVisibility
data class OnlinePageIdentity
private constructor(
    val chapter: OnlineChapterIdentity,
    val pageId: String? = null,
    val fallback: RevisionPageIndex? = null,
) {
    init {
        require((pageId != null) != (fallback != null)) {
            "Exactly one of pageId or fallback must be present"
        }
        pageId?.let { require(it.isNotBlank()) { "pageId must not be blank" } }
    }

    companion object {
        fun create(
            chapter: OnlineChapterIdentity,
            pageId: String?,
            pageIndex: Int,
            chapterRevision: String?,
        ): OnlinePageIdentity {
            require(pageIndex >= 0) { "pageIndex must be non-negative" }
            return if (pageId != null) {
                require(pageId.isNotBlank()) { "pageId must not be blank" }
                OnlinePageIdentity(chapter = chapter, pageId = pageId)
            } else {
                OnlinePageIdentity(
                    chapter = chapter,
                    fallback =
                        RevisionPageIndex(
                            chapterRevision =
                                requireNotNull(chapterRevision?.takeIf { it.isNotBlank() }) {
                                    "chapterRevision is required when pageId is absent"
                                },
                            pageIndex = pageIndex,
                        ),
                )
            }
        }
    }
}

/** A navigable position snapshot. Index and revision are context, not content identity. */
@Serializable
data class OnlinePageLocation(
    val identity: OnlinePageIdentity,
    val pageIndex: Int,
    val chapterRevision: String? = null,
) {
    init {
        require(pageIndex >= 0) { "pageIndex must be non-negative" }
        identity.fallback?.let { fallback ->
            require(pageIndex == fallback.pageIndex) { "Fallback page index must match location" }
            require(chapterRevision == fallback.chapterRevision) {
                "Fallback chapter revision must match location"
            }
        }
    }

    companion object {
        fun create(
            chapter: OnlineChapterIdentity,
            pageId: String?,
            pageIndex: Int,
            chapterRevision: String?,
        ): OnlinePageLocation =
            OnlinePageLocation(
                identity = OnlinePageIdentity.create(chapter, pageId, pageIndex, chapterRevision),
                pageIndex = pageIndex,
                chapterRevision = chapterRevision,
            )
    }
}
