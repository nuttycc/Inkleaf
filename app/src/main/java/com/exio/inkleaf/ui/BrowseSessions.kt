package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.ComicSummary
import com.exio.inkleaf.plugin.PluginBrowseCacheKey
import com.exio.inkleaf.plugin.PluginBrowseCacheSnapshot
import com.exio.inkleaf.plugin.PluginBrowseRequest
import com.exio.inkleaf.plugin.PluginFilterDescriptor
import com.exio.inkleaf.plugin.PluginSearchPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The grid state one browse target publishes to the discover surface. */
internal data class BrowseState(
    val items: List<ComicSummary> = emptyList(),
    val nextCursor: String? = null,
    val filters: Map<String, String> = emptyMap(),
    val ready: Boolean = false,
    val isBrowsing: Boolean = false,
    val error: String? = null,
    val firstPageCommitRevision: Long = 0L,
)

/** The source data behind [BrowseSessions]. */
internal interface BrowseSource {
    fun cacheGeneration(pluginId: String): Long

    fun isFresh(fetchedAtMs: Long): Boolean

    suspend fun readFirstPage(key: PluginBrowseCacheKey): PluginBrowseCacheSnapshot?

    suspend fun refreshFirstPage(
        key: PluginBrowseCacheKey,
        request: PluginBrowseRequest,
        expectedRevision: String?,
        force: Boolean = false,
    ): PluginBrowseCacheSnapshot

    suspend fun loadPage(pluginId: String, request: PluginBrowseRequest): PluginSearchPage
}

/** What the view model asks the module to show. A null key means no feed is selected. */
internal data class BrowseTarget(
    val key: PluginBrowseCacheKey?,
    val feedId: String?,
    val filters: Map<String, String>,
    val loadNow: Boolean,
)

/**
 * Owns discovery's browse sessions for the current navigation entry: the active target, each
 * target's loaded items and cursor, remembered filters, scroll anchors, and the generation guards
 * that stop a superseded first or next page from publishing.
 *
 * The view model keeps feed discovery, search and layout; it hands the module a [BrowseTarget] and
 * projects [state] into the screen's flows.
 */
internal class BrowseSessions(
    private val scope: CoroutineScope,
    private val source: BrowseSource,
) {
    private data class Session(
        val items: List<ComicSummary>,
        val nextCursor: String?,
        val firstPageFetchedAtMs: Long,
        val firstPageRevision: String,
        val cacheGeneration: Long,
    )

    private enum class Failure {
        FIRST_PAGE,
        NEXT_PAGE,
    }

    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    private val sessions =
        object : LinkedHashMap<PluginBrowseCacheKey, Session>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<PluginBrowseCacheKey, Session>?
            ): Boolean = size > MAX_BROWSE_SESSIONS
        }
    private val filtersByFeed =
        object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Map<String, String>>?
            ): Boolean = size > MAX_BROWSE_SESSIONS
        }
    private val scrollAnchors = DiscoverScrollAnchorStore(MAX_SCROLL_CONTEXTS)

    private var target: BrowseTarget? = null
    private var browseJob: Job? = null
    private var generation = 0L
    private var failure: Failure? = null

    val currentKey: PluginBrowseCacheKey?
        get() = target?.key

    val hasTarget: Boolean
        get() = target?.key != null

    fun scrollAnchor(contextKey: DiscoverScrollContextKey): DiscoverScrollAnchor? =
        scrollAnchors.get(contextKey)

    fun updateScrollAnchor(contextKey: DiscoverScrollContextKey, anchor: DiscoverScrollAnchor) {
        scrollAnchors.put(contextKey, anchor)
    }

    /** The filters a feed should activate with, remembering the choice that produced them. */
    fun selectedFilters(
        sessionKey: String,
        filters: List<PluginFilterDescriptor>,
    ): Map<String, String> {
        val remembered = filtersByFeed[sessionKey].orEmpty()
        return filters
            .mapNotNull { filter ->
                val optionId =
                    remembered[filter.id]?.takeIf { candidate ->
                        filter.options.any { it.id == candidate }
                    } ?: filter.options.firstOrNull()?.id
                optionId?.let { filter.id to it }
            }
            .toMap()
            .also { filtersByFeed[sessionKey] = it }
    }

    /** Remembers the filter set the user chose for one feed session. */
    fun rememberFilters(sessionKey: String, filters: Map<String, String>) {
        filtersByFeed[sessionKey] = filters
    }

    /**
     * Activates [next]. A session from the current cache generation is restored immediately; a
     * stale or missing one loads the first page when [BrowseTarget.loadNow] is set.
     */
    fun select(next: BrowseTarget): Job? {
        generation += 1
        browseJob?.cancel()
        target = next
        failure = null
        val key = next.key
        val session =
            key?.let { candidate ->
                sessions[candidate]?.takeIf {
                    it.cacheGeneration == source.cacheGeneration(candidate.pluginId)
                }
            }
        if (key != null && session == null) sessions.remove(key)
        _state.value =
            _state.value.copy(
                filters = next.filters,
                error = null,
                isBrowsing = false,
                ready = key == null || session != null,
                items = session?.items.orEmpty(),
                nextCursor = session?.nextCursor,
            )
        if (
            next.loadNow &&
                key != null &&
                (session == null || !source.isFresh(session.firstPageFetchedAtMs))
        ) {
            return loadFirstPage(force = false)
        }
        return null
    }

    /** Refreshes a missing, invalidated or expired session unless a browse request is running. */
    fun ensureFresh(): Job? {
        if (_state.value.isBrowsing) return null
        val key = target?.key ?: return null
        val session = sessions[key]
        if (
            session == null ||
                session.cacheGeneration != source.cacheGeneration(key.pluginId) ||
                !source.isFresh(session.firstPageFetchedAtMs)
        ) {
            return loadFirstPage(force = false)
        }
        return null
    }

    /** Refreshes the first page of the active target, if there is one. */
    fun refresh(force: Boolean): Job? {
        if (target?.key == null) return null
        return loadFirstPage(force = force)
    }

    /**
     * Loads the current cursor and appends comics without duplicate source IDs. Discards results if
     * the target or plugin cache generation changes during the request.
     */
    fun loadMore(): Job? {
        if (_state.value.isBrowsing) return null
        val next = target ?: return null
        val key = next.key ?: return null
        val feedId = next.feedId ?: return null
        val cursor = _state.value.nextCursor ?: return null
        val filters = _state.value.filters
        val cacheGeneration = source.cacheGeneration(key.pluginId)
        val loadGeneration = ++generation
        browseJob?.cancel()
        val job = scope.launch {
            _state.value = _state.value.copy(isBrowsing = true, error = null)
            failure = null
            try {
                val page =
                    source.loadPage(
                        key.pluginId,
                        PluginBrowseRequest(feedId = feedId, cursor = cursor, filters = filters),
                    )
                if (
                    loadGeneration == generation &&
                        key == target?.key &&
                        cacheGeneration == source.cacheGeneration(key.pluginId)
                ) {
                    val items = (_state.value.items + page.items).distinctBy { it.sourceId }
                    _state.value = _state.value.copy(items = items, nextCursor = page.nextCursor)
                    val firstPageFetchedAt = sessions[key]?.firstPageFetchedAtMs ?: 0L
                    val firstPageRevision = sessions[key]?.firstPageRevision.orEmpty()
                    sessions[key] =
                        Session(
                            items = items,
                            nextCursor = page.nextCursor,
                            firstPageFetchedAtMs = firstPageFetchedAt,
                            firstPageRevision = firstPageRevision,
                            cacheGeneration = cacheGeneration,
                        )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (loadGeneration == generation && key == target?.key) {
                    _state.value =
                        _state.value.copy(error = error.message ?: "加载失败")
                    failure = Failure.NEXT_PAGE
                }
            } finally {
                if (loadGeneration == generation && key == target?.key) {
                    _state.value = _state.value.copy(isBrowsing = false)
                }
            }
        }
        browseJob = job
        return job
    }

    /** Retries the failed next page, or refreshes the first page for other failures. */
    fun retry(): Job? =
        if (failure == Failure.NEXT_PAGE) loadMore() else refresh(force = true)

    /**
     * Publishes a valid cached first page, then refreshes it if stale or [force] is true. Guards
     * publication against target changes.
     */
    private fun loadFirstPage(force: Boolean): Job? {
        val next = target ?: return null
        val key = next.key ?: return null
        val feedId = next.feedId ?: return null
        val filters = next.filters
        val loadGeneration = ++generation
        browseJob?.cancel()
        val job = scope.launch {
            _state.value = _state.value.copy(isBrowsing = true, error = null)
            failure = null
            try {
                val cached =
                    source.readFirstPage(key)?.takeIf {
                        it.cacheGeneration == source.cacheGeneration(key.pluginId)
                    }
                if (loadGeneration != generation || key != target?.key) return@launch

                val session = sessions[key]
                if (
                    cached != null &&
                        (cached.revision != session?.firstPageRevision ||
                            cached.cacheGeneration != session.cacheGeneration)
                ) {
                    publishFirstPage(key, cached)
                }
                if (!force && cached != null && source.isFresh(cached.fetchedAtMs)) {
                    return@launch
                }

                val refreshed =
                    source.refreshFirstPage(
                        key = key,
                        request = PluginBrowseRequest(feedId = feedId, filters = filters),
                        expectedRevision = cached?.revision,
                        force = force,
                    )
                if (loadGeneration == generation && key == target?.key) {
                    if (
                        refreshed.cacheGeneration == source.cacheGeneration(key.pluginId)
                    ) {
                        publishFirstPage(key, refreshed)
                    } else {
                        _state.value =
                            _state.value.copy(
                                error = "内容源版本已更新，请重试",
                                ready = true,
                            )
                        failure = Failure.FIRST_PAGE
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (loadGeneration == generation && key == target?.key) {
                    _state.value =
                        _state.value.copy(
                            error = error.message ?: "加载失败",
                            ready = true,
                        )
                    failure = Failure.FIRST_PAGE
                }
            } finally {
                if (loadGeneration == generation && key == target?.key) {
                    _state.value = _state.value.copy(isBrowsing = false)
                }
            }
        }
        browseJob = job
        return job
    }

    private fun publishFirstPage(key: PluginBrowseCacheKey, snapshot: PluginBrowseCacheSnapshot) {
        val previous = sessions[key]
        val changed =
            previous == null ||
                previous.firstPageRevision != snapshot.revision ||
                previous.cacheGeneration != snapshot.cacheGeneration
        _state.value =
            _state.value.copy(
                items = snapshot.page.items,
                nextCursor = snapshot.page.nextCursor,
                ready = true,
                firstPageCommitRevision =
                    if (changed) _state.value.firstPageCommitRevision + 1
                    else _state.value.firstPageCommitRevision,
            )
        sessions[key] =
            Session(
                items = snapshot.page.items,
                nextCursor = snapshot.page.nextCursor,
                firstPageFetchedAtMs = snapshot.fetchedAtMs,
                firstPageRevision = snapshot.revision,
                cacheGeneration = snapshot.cacheGeneration,
            )
    }

    private companion object {
        const val MAX_BROWSE_SESSIONS = 32
        const val MAX_SCROLL_CONTEXTS = 32
    }
}
