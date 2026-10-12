package com.exio.inkleaf.plugin

import com.exio.inkleaf.data.ComicOpenException

/** One read of a chapter's page list, together with the plugin version that produced it. */
internal data class FetchedChapterPages(
    val response: PluginPagesResponse,
    val pluginVersion: String,
    val fetchedAtMs: Long,
)

/**
 * The pages port: everything the online chapter module needs from outside to stay honest about a
 * chapter's descriptors. A remote read and a canned fixture are the two adapters it is built for.
 */
internal interface OnlineChapterPages {
    /**
     * Reads [request]'s page list, retrying when the active plugin version moves mid-read so a
     * chapter is never assembled from a half-updated plugin.
     */
    suspend fun fetch(request: PluginPagesRequest): FetchedChapterPages
}

/** Reads page lists on behalf of the active plugin version, retrying once if that version moves. */
internal class VersionedChapterPages(
    private val readPages: suspend (PluginPagesRequest) -> PluginPagesResponse,
    private val activeVersion: suspend () -> String?,
) : OnlineChapterPages {
    /**
     * Returns pages read under a stable active version, retrying once before throwing
     * [ComicOpenException].
     */
    override suspend fun fetch(request: PluginPagesRequest): FetchedChapterPages {
        repeat(2) {
            val before = requireActiveVersion()
            val response = readPages(request)
            val after = requireActiveVersion()
            if (before == after) {
                return FetchedChapterPages(response, after, System.currentTimeMillis())
            }
        }
        throw ComicOpenException("插件在读取章节时已更新，请重试")
    }

    /** Requires an active plugin version so fetched pages can be assigned a cache identity. */
    private suspend fun requireActiveVersion(): String =
        activeVersion() ?: throw ComicOpenException("插件版本不可用")
}
