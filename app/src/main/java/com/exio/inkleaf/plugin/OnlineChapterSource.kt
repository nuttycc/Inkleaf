package com.exio.inkleaf.plugin

import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.OnlinePageCache
import com.exio.inkleaf.data.OnlinePageCacheIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import okhttp3.Call

/** A volume assembled from a freshly read page list, with the revision it resolved to. */
internal data class OpenedOnlineChapter(
    val volume: OnlineChapterVolume,
    val revision: String,
)

/** What a descriptor re-validation found, phrased in the terms the reader can act on. */
internal sealed interface OnlineChapterRevalidation {
    /** Nothing to adopt: the source was empty, unreadable, or structurally different. */
    data object None : OnlineChapterRevalidation

    /** The active volume adopted fresh descriptors in place, under an unchanged revision. */
    data class Replaced(val cacheKeyPrefixChanged: Boolean) : OnlineChapterRevalidation

    /** The source moved to a new revision, so [volume] replaces the active one. */
    data class NewRevision(val volume: OnlineChapterVolume, val revision: String) :
        OnlineChapterRevalidation
}

/**
 * Opens online chapters and keeps their descriptors honest. It owns what the reader otherwise had to
 * know: the revision a chapter resolves to, its cache identity, the manifest written for it, the
 * descriptor refresh a rejected page triggers, and the verdict of a TTL re-validation.
 *
 * A caller supplies only the chapter and a [pages] port, then reacts to "replaced" or "superseded".
 */
internal class OnlineChapterSource(
    private val pluginId: String,
    private val sourceId: String,
    private val pages: OnlineChapterPages,
    private val client: Call.Factory,
    private val cache: OnlinePageCache,
) {
    /** Reads [chapter] and assembles its volume. Throws when the source advertises no pages. */
    suspend fun open(chapter: ChapterSummary, opaqueContext: JsonElement?): OpenedOnlineChapter {
        val request = pagesRequest(chapter, opaqueContext)
        return build(chapter, request, pages.fetch(request))
    }

    /**
     * Re-reads [opened]'s source and reports what changed. The same stable pages under a new cache
     * identity are adopted in place; a new revision yields a volume for the caller to commit.
     *
     * [isCurrent] is re-checked after the read, because a chapter commit may have replaced [opened]
     * while the read was in flight.
     */
    suspend fun revalidate(
        opened: OnlineChapterVolume,
        chapter: ChapterSummary,
        opaqueContext: JsonElement?,
        isCurrent: () -> Boolean,
    ): OnlineChapterRevalidation {
        val request = pagesRequest(chapter, opaqueContext)
        val fetched = pages.fetch(request)
        val response = fetched.response
        if (response.pages.isEmpty()) return OnlineChapterRevalidation.None
        val revision = resolveOnlineChapterRevision(chapter.chapterId, chapter.revision, response)
        if (!isCurrent()) return OnlineChapterRevalidation.None
        if (revision != opened.sourceRevision) {
            val refreshed = chapter.copy(revision = revision)
            val openedNew = build(refreshed, pagesRequest(refreshed, opaqueContext), fetched)
            return OnlineChapterRevalidation.NewRevision(openedNew.volume, openedNew.revision)
        }
        val pageIdentities = onlinePageIdentities(revision, response.pages)
        val identity = cacheIdentity(fetched.pluginVersion, response, chapter.chapterId, revision)
        val previousCacheKeyPrefix = opened.cacheKeyPrefix
        if (!opened.replaceDescriptors(OnlineChapterRefresh(response.pages, identity))) {
            return OnlineChapterRevalidation.None
        }
        cache.writeManifest(identity, pageIdentities, fetched.fetchedAtMs)
        return OnlineChapterRevalidation.Replaced(
            cacheKeyPrefixChanged = opened.cacheKeyPrefix != previousCacheKeyPrefix
        )
    }

    /**
     * Builds a nonempty volume and writes its manifest; closes the volume if manifest writing is
     * cancelled.
     */
    private suspend fun build(
        chapter: ChapterSummary,
        request: PluginPagesRequest,
        fetched: FetchedChapterPages,
    ): OpenedOnlineChapter {
        val response = fetched.response
        if (response.pages.isEmpty()) throw ComicOpenException("目标章节没有可阅读页面")
        val revision = resolveOnlineChapterRevision(chapter.chapterId, chapter.revision, response)
        val identity = cacheIdentity(fetched.pluginVersion, response, chapter.chapterId, revision)
        val stablePageIdentities = onlinePageIdentities(revision, response.pages)
        val volume =
            OnlineChapterVolume(
                chapterId = chapter.chapterId,
                title = chapter.title.takeIf(String::isNotBlank) ?: chapter.chapterId,
                sourceRevision = revision,
                pages = response.pages,
                client = client,
                cache = cache,
                initialCacheIdentity = identity,
                refreshChapter = {
                    refreshDescriptors(
                        request = request,
                        chapterId = chapter.chapterId,
                        expectedRevision = revision,
                        expectedPageIdentities = stablePageIdentities,
                    )
                },
            )
        try {
            cache.writeManifest(identity, stablePageIdentities, fetched.fetchedAtMs)
        } catch (error: CancellationException) {
            volume.close()
            throw error
        }
        return OpenedOnlineChapter(volume, revision)
    }

    /** Re-resolves descriptors for a page the source rejected, keeping the stable pages in place. */
    private suspend fun refreshDescriptors(
        request: PluginPagesRequest,
        chapterId: String,
        expectedRevision: String,
        expectedPageIdentities: List<String>,
    ): OnlineChapterRefresh? {
        val fetched = pages.fetch(request)
        val response = fetched.response
        if (response.pages.isEmpty()) return null
        val revision = resolveOnlineChapterRevision(chapterId, request.revision, response)
        if (revision != expectedRevision) return null
        val pageIdentities = onlinePageIdentities(revision, response.pages)
        if (pageIdentities != expectedPageIdentities) return null
        val identity = cacheIdentity(fetched.pluginVersion, response, chapterId, revision)
        cache.writeManifest(identity, pageIdentities, fetched.fetchedAtMs)
        return OnlineChapterRefresh(response.pages, identity)
    }

    /** Builds a page request, preferring chapter-specific opaque context over the comic context. */
    private fun pagesRequest(
        chapter: ChapterSummary,
        opaqueContext: JsonElement?,
    ): PluginPagesRequest =
        PluginPagesRequest(
            sourceId = sourceId,
            chapterId = chapter.chapterId,
            revision = chapter.revision,
            opaqueContext = chapter.opaqueContext ?: opaqueContext,
        )

    /** Scopes cached pages to the plugin version, access scope and resolved chapter revision. */
    private fun cacheIdentity(
        pluginVersion: String,
        response: PluginPagesResponse,
        chapterId: String,
        revision: String,
    ): OnlinePageCacheIdentity =
        OnlinePageCacheIdentity.create(
            pluginId = pluginId,
            pluginVersion = pluginVersion,
            accessScope = response.accessScope ?: LEGACY_ACCESS_SCOPE,
            sourceId = sourceId,
            chapterId = chapterId,
            revision = revision,
        )

    private companion object {
        const val LEGACY_ACCESS_SCOPE = "legacy"
    }
}
