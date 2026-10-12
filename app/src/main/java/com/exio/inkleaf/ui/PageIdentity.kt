package com.exio.inkleaf.ui

import com.exio.inkleaf.data.ComicVolume
import com.exio.inkleaf.data.PageRenderRequest
import com.exio.inkleaf.data.ReaderPageCacheKey

/** Key identifying one page of one chapter revision. */
internal data class ReaderChapterPageKey(
    val chapterId: String,
    val chapterRevision: String,
    val pageIdentity: String,
) : ReaderChapterWindowKey {
    init {
        require(chapterId.isNotBlank())
        require(chapterRevision.isNotBlank())
        require(pageIdentity.isNotBlank())
    }
}

/** Key identifying one page inside one namespace. */
internal data class ReaderPageStateKey(
    val namespace: String,
    val pageIdentity: String,
) {
    init {
        require(namespace.isNotBlank())
        require(pageIdentity.isNotBlank())
    }
}

internal fun ReaderChapterPageKey.toReaderPageStateKey(): ReaderPageStateKey =
    ReaderPageStateKey(
        namespace = "$chapterId\u0000$chapterRevision",
        pageIdentity = pageIdentity,
    )

/**
 * One owner for every reader key derived from "which page is this".
 *
 * The reader asks that question in several shapes: the Compose state key that carries zoom and
 * thumbnail state, the key that restarts a page's producer, and the Coil memory-cache key. Before
 * this module existed each shape was assembled at its call site. The local reader's prefix was a
 * shelf id, so re-syncing or re-packing a comic kept reusing the previous revision's keys and could
 * serve a stale decoded page; the online reader derived its namespace a fourth way.
 *
 * Everything now derives from one place: the volume's own source revision, which [ComicVolume]
 * documents as changing whenever page content or ordering may have changed. Pure and Android-free,
 * so JVM tests can drive every shape.
 */
internal object PageIdentity {
    /** The volume's own page identity, or a positional fallback for volumes that have none. */
    fun pageIdentity(volume: ComicVolume, page: Int): String =
        volume.pageIdentity(page)?.takeIf(String::isNotBlank) ?: "index:$page"

    /**
     * Namespace for every key a volume produces.
     *
     * Built from the source revision rather than a shelf id, so a re-synced archive rotates all of
     * its keys instead of inheriting the previous revision's.
     */
    fun volumeNamespace(volume: ComicVolume): String = "$LOCAL_NAMESPACE${volume.sourceRevision}"

    /** State key for a page of a volume the reader holds open. */
    fun stateKey(volume: ComicVolume, page: Int): ReaderPageStateKey =
        ReaderPageStateKey(
            namespace = volumeNamespace(volume),
            pageIdentity = pageIdentity(volume, page),
        )

    /** State key for a page identified by an online chapter revision rather than a volume. */
    fun stateKey(
        chapterId: String,
        chapterRevision: String,
        pageIdentity: String,
    ): ReaderPageStateKey =
        ReaderPageStateKey(
            namespace = "$chapterId\u0000$chapterRevision",
            pageIdentity = pageIdentity,
        )

    private const val LOCAL_NAMESPACE = "local:"
}

/**
 * Separates changes that invalidate the displayed page from changes that only refresh its
 * processing.
 */
internal data class ReaderPageContentKeys(
    val stateReset: StateReset,
    val producerRestart: ProducerRestart,
) {
    internal data class StateReset(
        val volumeToken: Any,
        val page: Int,
        val cacheKeyPrefix: String,
    )

    internal data class ProducerRestart(
        val pageRenderRequest: PageRenderRequest?,
    )
}

internal fun readerPageContentKeys(
    volumeToken: Any,
    page: Int,
    cacheKeyPrefix: String,
    pageRenderRequest: PageRenderRequest?,
): ReaderPageContentKeys =
    ReaderPageContentKeys(
        stateReset =
            ReaderPageContentKeys.StateReset(
                volumeToken = volumeToken,
                page = page,
                cacheKeyPrefix = cacheKeyPrefix,
            ),
        // Current-page status changes presentation only; render requests carry real work changes.
        producerRestart =
            ReaderPageContentKeys.ProducerRestart(
                pageRenderRequest = pageRenderRequest,
            ),
    )
