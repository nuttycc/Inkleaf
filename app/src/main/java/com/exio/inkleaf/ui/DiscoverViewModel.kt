package com.exio.inkleaf.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.plugin.ComicSummary
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginBrowseRequest
import com.exio.inkleaf.plugin.PluginCatalog
import com.exio.inkleaf.plugin.PluginCapabilities
import com.exio.inkleaf.plugin.PluginFeedDescriptor
import com.exio.inkleaf.plugin.PluginHealth
import com.exio.inkleaf.plugin.PluginSearchRequest
import com.exio.inkleaf.plugin.PluginSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Navigation-scoped state holder for the comic discovery surface.
 * Retains browse and search state across SourcesScreen navigation.
 */
class DiscoverViewModel : ViewModel() {
    enum class Mode { BROWSE, SEARCH }

    data class Feed(
        val pluginId: String,
        val pluginName: String,
        val pluginVersion: String,
        val descriptor: PluginFeedDescriptor,
    ) {
        val key: String = "$pluginId:${descriptor.id}"
    }

    private val _mode = MutableStateFlow(Mode.BROWSE)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

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

    private var feedLoadJob: Job? = null
    private var browseJob: Job? = null
    private var searchJob: Job? = null
    private var browseGeneration = 0L
    private var searchGeneration = 0L

    private data class FeedLoadResult(
        val feeds: List<Feed>,
        val error: String? = null,
    )

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

    fun selectMode(mode: Mode) {
        _mode.value = mode
    }

    fun loadFeeds(catalog: PluginCatalog, availablePlugins: List<InstalledPlugin>) {
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
                                        feeds = catalog.describe(plugin.state.pluginId).feeds.map { descriptor ->
                                            Feed(
                                                pluginId = plugin.state.pluginId,
                                                pluginName = plugin.manifest?.name ?: plugin.state.pluginId,
                                                pluginVersion = plugin.state.activeVersion.orEmpty(),
                                                descriptor = descriptor,
                                            )
                                        }
                                    )
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Throwable) {
                                    FeedLoadResult(
                                        feeds = emptyList(),
                                        error = "${plugin.manifest?.name ?: plugin.state.pluginId}: " +
                                            (error.message ?: "无法读取内容流"),
                                    )
                                }
                            }
                        }.awaitAll()
                }
                val discoveredFeeds = loadResults.flatMap { it.feeds }
                _feedLoadError.value = loadResults.mapNotNull { it.error }.takeIf { it.isNotEmpty() }
                    ?.joinToString("\n")
                _feeds.value = discoveredFeeds
                val selected = discoveredFeeds.firstOrNull { it.key == _selectedFeedKey.value }
                    ?: discoveredFeeds.firstOrNull()
                if (selected?.key != _selectedFeedKey.value ||
                    selected?.pluginVersion != previousSelected?.pluginVersion ||
                    selected?.descriptor != previousSelected?.descriptor
                ) {
                    selectFeed(catalog, selected?.key)
                }
            } finally {
                _isLoadingFeeds.value = false
            }
        }
    }

    fun selectFeed(catalog: PluginCatalog, feedKey: String?) {
        browseGeneration += 1
        browseJob?.cancel()
        _isBrowsing.value = false
        _selectedFeedKey.value = feedKey
        _browseFilters.value = selectedFeed()?.descriptor?.filters.orEmpty()
            .mapNotNull { filter -> filter.options.firstOrNull()?.id?.let { filter.id to it } }
            .toMap()
        _browseItems.value = emptyList()
        _browseNextCursor.value = null
        _browseError.value = null
        if (selectedFeed() != null) loadBrowsePage(catalog, append = false)
    }

    fun selectBrowseFilter(catalog: PluginCatalog, filterId: String, optionId: String) {
        if (_browseFilters.value[filterId] == optionId) return
        _browseFilters.value = _browseFilters.value + (filterId to optionId)
        _browseItems.value = emptyList()
        _browseNextCursor.value = null
        _browseError.value = null
        loadBrowsePage(catalog, append = false)
    }

    fun loadMore(catalog: PluginCatalog) {
        if (_browseNextCursor.value != null) loadBrowsePage(catalog, append = true)
    }

    fun retryBrowse(catalog: PluginCatalog) {
        loadBrowsePage(catalog, append = _browseItems.value.isNotEmpty())
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
        val next = if (pluginId in current) {
            current - pluginId
        } else {
            current + pluginId
        }
        _selectedPluginIds.value = next
    }

    fun selectAllPlugins(availablePluginIds: List<String>) {
        _selectedPluginIds.value = availablePluginIds.toSet()
    }

    fun performSearch(catalog: PluginCatalog, availablePlugins: List<InstalledPlugin>) {
        val currentQuery = _query.value.trim()
        if (currentQuery.isBlank() || _isSearching.value) return

        val activeHealthyIds = availablePlugins
            .filter { !it.state.disabled && it.state.health == PluginHealth.HEALTHY && it.state.activeVersion != null }
            .map { it.state.pluginId }

        if (activeHealthyIds.isEmpty()) return

        val targetIds = (_selectedPluginIds.value ?: activeHealthyIds.toSet())
            .filter { it in activeHealthyIds }

        if (targetIds.isEmpty()) return

        val generation = ++searchGeneration
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            _errorMessage.value = null
            try {
                val res = catalog.search(
                    PluginSearchRequest(query = currentQuery),
                    pluginIds = targetIds,
                )
                if (generation == searchGeneration && _query.value.trim() == currentQuery) {
                    _results.value = res
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == searchGeneration) {
                    _errorMessage.value = error.message ?: "搜索失败"
                }
            } finally {
                if (generation == searchGeneration) {
                    _isSearching.value = false
                }
            }
        }
    }

    private fun selectedFeed(): Feed? = _feeds.value.firstOrNull { it.key == _selectedFeedKey.value }

    private fun loadBrowsePage(catalog: PluginCatalog, append: Boolean) {
        val feed = selectedFeed() ?: return
        val cursor = if (append) _browseNextCursor.value ?: return else null
        val filters = _browseFilters.value
        val generation = ++browseGeneration
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            _isBrowsing.value = true
            _browseError.value = null
            try {
                val page = catalog.browse(
                    feed.pluginId,
                    PluginBrowseRequest(
                        feedId = feed.descriptor.id,
                        cursor = cursor,
                        filters = filters,
                    ),
                )
                if (generation == browseGeneration) {
                    _browseItems.value = if (append) {
                        (_browseItems.value + page.items).distinctBy { it.sourceId }
                    } else {
                        page.items
                    }
                    _browseNextCursor.value = page.nextCursor
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == browseGeneration) {
                    _browseError.value = error.message ?: "加载失败"
                }
            } finally {
                if (generation == browseGeneration) {
                    _isBrowsing.value = false
                }
            }
        }
    }
}
