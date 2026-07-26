package com.exio.inkleaf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.data.DiscoverLayoutMode
import com.exio.inkleaf.data.DiscoverLayoutSettings
import com.exio.inkleaf.data.DiscoverSettingsRepository
import com.exio.inkleaf.data.GridColumnsMode
import com.exio.inkleaf.plugin.ComicSummary
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginBrowseCacheKey
import com.exio.inkleaf.plugin.PluginBrowseCacheSnapshot
import com.exio.inkleaf.plugin.PluginBrowseRepository
import com.exio.inkleaf.plugin.PluginBrowseRequest
import com.exio.inkleaf.plugin.PluginCapabilities
import com.exio.inkleaf.plugin.PluginCatalog
import com.exio.inkleaf.plugin.PluginFeedDescriptor
import com.exio.inkleaf.plugin.PluginHealth
import com.exio.inkleaf.plugin.PluginManager
import com.exio.inkleaf.plugin.PluginSearchRequest
import com.exio.inkleaf.plugin.PluginSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Navigation-scoped state holder for the comic discovery surface. Retains browse and search state
 * across SourcesScreen and bottom-tab navigation.
 */
class DiscoverViewModel(app: Application) : AndroidViewModel(app) {
    /** 浏览与搜索共用这一屏：搜索是覆盖在浏览之上的临时态，退出即回到原来的内容流。 */
    enum class Mode {
        BROWSE,
        SEARCH,
    }

    data class Feed(
        val pluginId: String,
        val pluginName: String,
        val pluginVersion: String,
        val descriptor: PluginFeedDescriptor,
    ) {
        val key: String = "$pluginId:${descriptor.id}"
    }

    private data class BrowseSessionSnapshot(
        val items: List<ComicSummary>,
        val nextCursor: String?,
        val firstPageFetchedAtMs: Long,
        val firstPageRevision: String,
    )

    private data class FeedLoadResult(
        val feeds: List<Feed>,
        val error: String? = null,
    )

    private enum class BrowseFailure {
        FIRST_PAGE,
        NEXT_PAGE,
    }

    private val settingsRepo = DiscoverSettingsRepository(app)

    private val _mode = MutableStateFlow(Mode.BROWSE)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    /** 排版设置。initial 给默认构造对象：DataStore 首次发射前网格就有合法参数，不会闪变 */
    val layoutSettings: StateFlow<DiscoverLayoutSettings> =
        settingsRepo.settings.stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            DiscoverLayoutSettings(),
        )

    /**
     * 下拉刷新指示器专用，与 isBrowsing 刻意分开：isBrowsing 在翻下一页时同样为 true，
     * 共用一个标志会让触底加载把顶部的刷新圈也转起来。
     */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _installedPlugins = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installedPlugins: StateFlow<List<InstalledPlugin>> = _installedPlugins.asStateFlow()

    private val _feeds = MutableStateFlow<List<Feed>>(emptyList())
    val feeds: StateFlow<List<Feed>> = _feeds.asStateFlow()

    private val _isLoadingFeeds = MutableStateFlow(false)
    val isLoadingFeeds: StateFlow<Boolean> = _isLoadingFeeds.asStateFlow()

    private val _feedLoadError = MutableStateFlow<String?>(null)
    val feedLoadError: StateFlow<String?> = _feedLoadError.asStateFlow()

    private val _selectedFeedKey = MutableStateFlow<String?>(null)
    val selectedFeedKey: StateFlow<String?> = _selectedFeedKey.asStateFlow()

    private val _browseFilters = MutableStateFlow<Map<String, String>>(emptyMap())
    val browseFilters: StateFlow<Map<String, String>> = _browseFilters.asStateFlow()

    private val _browseItems = MutableStateFlow<List<ComicSummary>>(emptyList())
    val browseItems: StateFlow<List<ComicSummary>> = _browseItems.asStateFlow()

    private val _browseNextCursor = MutableStateFlow<String?>(null)
    val browseNextCursor: StateFlow<String?> = _browseNextCursor.asStateFlow()

    private val _isBrowsing = MutableStateFlow(false)
    val isBrowsing: StateFlow<Boolean> = _isBrowsing.asStateFlow()

    private val _browseError = MutableStateFlow<String?>(null)
    val browseError: StateFlow<String?> = _browseError.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selectedPluginIds = MutableStateFlow<Set<String>?>(null)
    val selectedPluginIds: StateFlow<Set<String>?> = _selectedPluginIds.asStateFlow()

    private val _results = MutableStateFlow<List<PluginSearchResult>>(emptyList())
    val results: StateFlow<List<PluginSearchResult>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val browseSessions =
        object : LinkedHashMap<PluginBrowseCacheKey, BrowseSessionSnapshot>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<PluginBrowseCacheKey, BrowseSessionSnapshot>?
            ): Boolean = size > MAX_BROWSE_SESSIONS
        }
    private val browseFiltersByFeed =
        object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Map<String, String>>?
            ): Boolean = size > MAX_BROWSE_SESSIONS
        }
    private var currentBrowseKey: PluginBrowseCacheKey? = null
    private var loadedFeedSignature: List<String>? = null
    private var browseFailure: BrowseFailure? = null
    private var installedLoadJob: Job? = null
    private var feedLoadJob: Job? = null
    private var browseJob: Job? = null
    private var searchJob: Job? = null
    private var installedLoadGeneration = 0L
    private var feedLoadGeneration = 0L
    private var browseGeneration = 0L
    private var searchGeneration = 0L

    fun enterSearch() {
        _mode.value = Mode.SEARCH
    }

    /**
     * 退出搜索一并清空查询词与结果：顶栏变回源名后，搜索态在界面上再无任何可见承载，
     * 留着它就成了用户看不见却仍会影响下一次搜索的隐藏状态。
     */
    fun exitSearch(browseRepository: PluginBrowseRepository) {
        _mode.value = Mode.BROWSE
        updateQuery("")
        ensureCurrentBrowseFresh(browseRepository)
    }

    /** 切换当前源：落到该源的第一个内容流上 */
    fun selectSource(repository: PluginBrowseRepository, pluginId: String) {
        val target = _feeds.value.firstOrNull { it.pluginId == pluginId } ?: return
        selectFeed(repository, target.key)
    }

    fun setLayout(value: DiscoverLayoutMode) {
        viewModelScope.launch { settingsRepo.setLayout(value) }
    }

    fun setColumns(value: GridColumnsMode) {
        viewModelScope.launch { settingsRepo.setColumns(value) }
    }

    /** 下拉刷新的统一入口：刷新用户此刻真正在看的东西 */
    fun refresh(
        catalog: PluginCatalog,
        browseRepository: PluginBrowseRepository,
        availablePlugins: List<InstalledPlugin>,
    ) {
        when (_mode.value) {
            Mode.BROWSE -> {
                if (currentBrowseKey == null) return
                _isRefreshing.value = true
                loadBrowseFirstPage(browseRepository, force = true, manual = true)
            }
            Mode.SEARCH -> {
                if (_query.value.isBlank()) return
                _isRefreshing.value = true
                performSearch(catalog, availablePlugins)
            }
        }
    }

    fun loadInstalledPlugins(manager: PluginManager) {
        val generation = ++installedLoadGeneration
        installedLoadJob?.cancel()
        installedLoadJob = viewModelScope.launch {
            try {
                val plugins = withContext(Dispatchers.IO) { manager.installed() }
                if (generation == installedLoadGeneration) _installedPlugins.value = plugins
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Preserve the last usable list during a transient metadata read failure.
            }
        }
    }

    fun loadFeeds(
        catalog: PluginCatalog,
        browseRepository: PluginBrowseRepository,
        availablePlugins: List<InstalledPlugin>,
        force: Boolean = false,
    ) {
        val signature =
            availablePlugins
                .filter { PluginCapabilities.BROWSE in it.manifest?.capabilities.orEmpty() }
                .map { "${it.state.pluginId}:${it.state.activeVersion}" }
                .sorted()
        if (!force && loadedFeedSignature == signature) {
            if (_mode.value == Mode.BROWSE) ensureCurrentBrowseFresh(browseRepository)
            return
        }

        val generation = ++feedLoadGeneration
        feedLoadJob?.cancel()
        feedLoadJob = viewModelScope.launch {
            _isLoadingFeeds.value = true
            _feedLoadError.value = null
            try {
                val previousSelected = selectedFeed()
                val loadResults = coroutineScope {
                    availablePlugins
                        .filter { PluginCapabilities.BROWSE in it.manifest?.capabilities.orEmpty() }
                        .map { plugin ->
                            async {
                                try {
                                    FeedLoadResult(
                                        feeds =
                                            catalog.describe(plugin.state.pluginId).feeds.map {
                                                descriptor ->
                                                Feed(
                                                    pluginId = plugin.state.pluginId,
                                                    pluginName =
                                                        plugin.manifest?.name
                                                            ?: plugin.state.pluginId,
                                                    pluginVersion =
                                                        plugin.state.activeVersion.orEmpty(),
                                                    descriptor = descriptor,
                                                )
                                            }
                                    )
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Throwable) {
                                    FeedLoadResult(
                                        feeds = emptyList(),
                                        error =
                                            "${plugin.manifest?.name ?: plugin.state.pluginId}: " +
                                                (error.message ?: "无法读取内容流"),
                                    )
                                }
                            }
                        }
                        .awaitAll()
                }
                if (generation == feedLoadGeneration) {
                    val discoveredFeeds = loadResults.flatMap { it.feeds }
                    loadedFeedSignature = signature
                    _feedLoadError.value =
                        loadResults
                            .mapNotNull { it.error }
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString("\n")
                    _feeds.value = discoveredFeeds
                    val selected =
                        discoveredFeeds.firstOrNull { it.key == _selectedFeedKey.value }
                            ?: discoveredFeeds.firstOrNull()
                    if (
                        selected?.key != _selectedFeedKey.value ||
                            selected?.pluginVersion != previousSelected?.pluginVersion ||
                            selected?.descriptor != previousSelected?.descriptor
                    ) {
                        selectFeed(browseRepository, selected?.key)
                    }
                }
            } finally {
                if (generation == feedLoadGeneration) _isLoadingFeeds.value = false
            }
        }
    }

    fun selectFeed(repository: PluginBrowseRepository, feedKey: String?) {
        val feed = _feeds.value.firstOrNull { it.key == feedKey }
        val filters = feed?.selectedFilters().orEmpty()
        val key = feed?.cacheKey(filters)
        if (key != null && key == currentBrowseKey) return

        _selectedFeedKey.value = feedKey
        activateBrowseTarget(repository, key, filters)
    }

    fun selectBrowseFilter(
        repository: PluginBrowseRepository,
        filterId: String,
        optionId: String,
    ) {
        if (_browseFilters.value[filterId] == optionId) return
        val filters = _browseFilters.value + (filterId to optionId)
        val feed = selectedFeed() ?: return
        browseFiltersByFeed[feed.sessionKey] = filters
        val key = feed.cacheKey(filters)
        activateBrowseTarget(repository, key, filters)
    }

    /**
     * 触底预取会在滚动中被反复调用，这里必须挡住重入：loadBrowsePage 会先 cancel 掉
     * 正在跑的 browseJob，不挡的话每次调用都把上一次快取到手的请求掐掉，永远加载不完。
     */
    fun loadMore(repository: PluginBrowseRepository) {
        if (_isBrowsing.value) return
        if (_browseNextCursor.value != null) loadBrowsePage(repository)
    }

    fun retryBrowse(repository: PluginBrowseRepository) {
        if (browseFailure == BrowseFailure.NEXT_PAGE) {
            loadBrowsePage(repository)
        } else {
            loadBrowseFirstPage(repository, force = true)
        }
    }

    fun updateQuery(newQuery: String) {
        if (newQuery == _query.value) return
        searchGeneration += 1
        searchJob?.cancel()
        _isSearching.value = false
        _query.value = newQuery
        _results.value = emptyList()
        _errorMessage.value = null
    }

    fun togglePluginSelection(pluginId: String, availablePluginIds: List<String>) {
        val current = _selectedPluginIds.value ?: availablePluginIds.toSet()
        _selectedPluginIds.value =
            if (pluginId in current) current - pluginId else current + pluginId
    }

    fun selectAllPlugins(availablePluginIds: List<String>) {
        _selectedPluginIds.value = availablePluginIds.toSet()
    }

    fun performSearch(catalog: PluginCatalog, availablePlugins: List<InstalledPlugin>) {
        val currentQuery = _query.value.trim()
        if (currentQuery.isBlank()) {
            _isRefreshing.value = false
            return
        }

        val activeHealthyIds =
            availablePlugins
                .filter {
                    !it.state.disabled &&
                        it.state.health == PluginHealth.HEALTHY &&
                        it.state.activeVersion != null
                }
                .map { it.state.pluginId }
        if (activeHealthyIds.isEmpty()) {
            _isRefreshing.value = false
            return
        }

        val targetIds =
            (_selectedPluginIds.value ?: activeHealthyIds.toSet()).filter { it in activeHealthyIds }
        if (targetIds.isEmpty()) {
            searchJob?.cancel()
            _results.value = emptyList()
            _isSearching.value = false
            _isRefreshing.value = false
            _errorMessage.value = null
            return
        }

        val generation = ++searchGeneration
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            _errorMessage.value = null
            try {
                val result =
                    catalog.search(
                        PluginSearchRequest(query = currentQuery),
                        pluginIds = targetIds,
                    )
                if (generation == searchGeneration && _query.value.trim() == currentQuery) {
                    _results.value = result
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == searchGeneration) _errorMessage.value = error.message ?: "搜索失败"
            } finally {
                _isRefreshing.value = false
                if (generation == searchGeneration) _isSearching.value = false
            }
        }
    }

    fun retrySearch(catalog: PluginCatalog, availablePlugins: List<InstalledPlugin>) {
        _errorMessage.value = null
        performSearch(catalog, availablePlugins)
    }

    private fun activateBrowseTarget(
        repository: PluginBrowseRepository,
        key: PluginBrowseCacheKey?,
        filters: Map<String, String>,
    ) {
        browseGeneration += 1
        browseJob?.cancel()
        currentBrowseKey = key
        _browseFilters.value = filters
        _browseError.value = null
        _isBrowsing.value = false
        browseFailure = null

        val session = key?.let(browseSessions::get)
        _browseItems.value = session?.items.orEmpty()
        _browseNextCursor.value = session?.nextCursor
        if (
            _mode.value == Mode.BROWSE &&
                key != null &&
                (session == null || !repository.isFresh(session.firstPageFetchedAtMs))
        ) {
            loadBrowseFirstPage(repository, force = false)
        }
    }

    private fun ensureCurrentBrowseFresh(repository: PluginBrowseRepository) {
        if (_isBrowsing.value || currentBrowseKey == null) return
        val session = currentBrowseKey?.let(browseSessions::get)
        if (session == null || !repository.isFresh(session.firstPageFetchedAtMs)) {
            loadBrowseFirstPage(repository, force = false)
        }
    }

    private fun loadBrowseFirstPage(
        repository: PluginBrowseRepository,
        force: Boolean,
        manual: Boolean = false,
    ) {
        val feed = selectedFeed() ?: return
        val key = currentBrowseKey ?: return
        val filters = _browseFilters.value
        val generation = ++browseGeneration
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            _isBrowsing.value = true
            _browseError.value = null
            browseFailure = null
            try {
                val cached = repository.readFirstPage(key)
                if (generation != browseGeneration || key != currentBrowseKey) return@launch

                val session = browseSessions[key]
                if (cached != null && cached.revision != session?.firstPageRevision) {
                    publishFirstPage(key, cached)
                }
                if (!force && cached != null && repository.isFresh(cached)) return@launch

                val refreshed =
                    repository.refreshFirstPage(
                        key = key,
                        request =
                            PluginBrowseRequest(feedId = feed.descriptor.id, filters = filters),
                        expectedRevision = cached?.revision,
                        force = force,
                    )
                if (generation == browseGeneration && key == currentBrowseKey) {
                    publishFirstPage(key, refreshed)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == browseGeneration && key == currentBrowseKey) {
                    _browseError.value = error.message ?: "加载失败"
                    browseFailure = BrowseFailure.FIRST_PAGE
                }
            } finally {
                // 刷新指示器的收尾不受 generation 守卫：刷新途中用户切了分类，
                // 这一轮就再也满足不了 key == currentBrowseKey，圈会一直转下去
                if (manual) _isRefreshing.value = false
                if (generation == browseGeneration && key == currentBrowseKey) {
                    _isBrowsing.value = false
                }
            }
        }
    }

    private fun loadBrowsePage(repository: PluginBrowseRepository) {
        val feed = selectedFeed() ?: return
        val key = currentBrowseKey ?: return
        val cursor = _browseNextCursor.value ?: return
        val filters = _browseFilters.value
        val generation = ++browseGeneration
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            _isBrowsing.value = true
            _browseError.value = null
            browseFailure = null
            try {
                val page =
                    repository.loadPage(
                        feed.pluginId,
                        PluginBrowseRequest(
                            feedId = feed.descriptor.id,
                            cursor = cursor,
                            filters = filters,
                        ),
                    )
                if (generation == browseGeneration && key == currentBrowseKey) {
                    _browseItems.value =
                        (_browseItems.value + page.items).distinctBy { it.sourceId }
                    _browseNextCursor.value = page.nextCursor
                    val firstPageFetchedAt = browseSessions[key]?.firstPageFetchedAtMs ?: 0L
                    val firstPageRevision = browseSessions[key]?.firstPageRevision.orEmpty()
                    browseSessions[key] =
                        BrowseSessionSnapshot(
                            items = _browseItems.value,
                            nextCursor = page.nextCursor,
                            firstPageFetchedAtMs = firstPageFetchedAt,
                            firstPageRevision = firstPageRevision,
                        )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == browseGeneration && key == currentBrowseKey) {
                    _browseError.value = error.message ?: "加载失败"
                    browseFailure = BrowseFailure.NEXT_PAGE
                }
            } finally {
                if (generation == browseGeneration && key == currentBrowseKey) {
                    _isBrowsing.value = false
                }
            }
        }
    }

    private fun publishFirstPage(key: PluginBrowseCacheKey, snapshot: PluginBrowseCacheSnapshot) {
        _browseItems.value = snapshot.page.items
        _browseNextCursor.value = snapshot.page.nextCursor
        browseSessions[key] =
            BrowseSessionSnapshot(
                items = snapshot.page.items,
                nextCursor = snapshot.page.nextCursor,
                firstPageFetchedAtMs = snapshot.fetchedAtMs,
                firstPageRevision = snapshot.revision,
            )
    }

    private fun selectedFeed(): Feed? =
        _feeds.value.firstOrNull { it.key == _selectedFeedKey.value }

    private fun Feed.cacheKey(filters: Map<String, String>) =
        PluginBrowseCacheKey(
            pluginId = pluginId,
            pluginVersion = pluginVersion,
            feedId = descriptor.id,
            filters = filters,
        )

    private val Feed.sessionKey: String
        get() = "$pluginId\n$pluginVersion\n${descriptor.id}"

    private fun Feed.selectedFilters(): Map<String, String> {
        val remembered = browseFiltersByFeed[sessionKey].orEmpty()
        return descriptor.filters
            .mapNotNull { filter ->
                val optionId =
                    remembered[filter.id]?.takeIf { candidate ->
                        filter.options.any { it.id == candidate }
                    } ?: filter.options.firstOrNull()?.id
                optionId?.let { filter.id to it }
            }
            .toMap()
            .also { browseFiltersByFeed[sessionKey] = it }
    }

    private companion object {
        const val MAX_BROWSE_SESSIONS = 32
    }
}
