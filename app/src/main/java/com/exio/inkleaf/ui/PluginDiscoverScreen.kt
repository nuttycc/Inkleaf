package com.exio.inkleaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.composables.icons.materialsymbols.outlined.R as MaterialSymbolsOutlinedR
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.R
import com.exio.inkleaf.data.DiscoverLayoutMode
import com.exio.inkleaf.data.DiscoverLayoutSettings
import com.exio.inkleaf.data.GridColumnsMode
import com.exio.inkleaf.plugin.ComicSummary
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PageImage
import com.exio.inkleaf.plugin.PluginFilterDescriptor
import com.exio.inkleaf.plugin.PluginHealth
import com.exio.inkleaf.plugin.PluginSearchResult

/** 距列表末尾还有这么多条目时就预取下一页，滚动到底之前内容已经补上。 */
private const val LOAD_MORE_PREFETCH = 6

/** Native-rendered discovery surface for plugin feeds and comic search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDiscoverScreen(
    onOpenComic: (String, ComicSummary) -> Unit = { _, _ -> },
    onOpenSources: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = viewModel(),
) {
    val context = LocalContext.current
    val application = context.applicationContext as InkleafApplication

    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val layoutSettings by viewModel.layoutSettings.collectAsStateWithLifecycle()
    val installedPlugins by viewModel.installedPlugins.collectAsStateWithLifecycle()
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val isLoadingFeeds by viewModel.isLoadingFeeds.collectAsStateWithLifecycle()
    val feedLoadError by viewModel.feedLoadError.collectAsStateWithLifecycle()
    val selectedFeedKey by viewModel.selectedFeedKey.collectAsStateWithLifecycle()
    val browseFilters by viewModel.browseFilters.collectAsStateWithLifecycle()
    val browseItems by viewModel.browseItems.collectAsStateWithLifecycle()
    val browseNextCursor by viewModel.browseNextCursor.collectAsStateWithLifecycle()
    val isBrowsing by viewModel.isBrowsing.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val browseError by viewModel.browseError.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedPluginIds by viewModel.selectedPluginIds.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadInstalledPlugins(application.pluginManager)
    }

    val activeHealthyPlugins =
        remember(installedPlugins) {
            installedPlugins.filter {
                !it.state.disabled &&
                    it.state.health == PluginHealth.HEALTHY &&
                    it.state.activeVersion != null
            }
        }
    val activePluginSignature =
        remember(activeHealthyPlugins) {
            activeHealthyPlugins.map { "${it.state.pluginId}:${it.state.activeVersion}" }
        }
    LaunchedEffect(activePluginSignature) {
        viewModel.loadFeeds(
            application.pluginCatalog,
            application.pluginBrowseRepository,
            activeHealthyPlugins,
        )
    }

    val currentSelectedIds =
        selectedPluginIds
            ?: remember(activeHealthyPlugins) {
                activeHealthyPlugins.map { it.state.pluginId }.toSet()
            }
    val visibleResults =
        remember(results, currentSelectedIds) {
            results.filter { it.pluginId in currentSelectedIds }
        }
    val hasNoResults =
        remember(visibleResults) {
            visibleResults.isEmpty() ||
                visibleResults.all { result ->
                    result.error == null && result.page?.items.orEmpty().isEmpty()
                }
        }

    val selectedFeed = feeds.firstOrNull { it.key == selectedFeedKey }
    // 源与分类分两级：源占标题位，chips 只列当前源的分类。拉平成一行时
    // 多个源都有"最新""热门"，chip 文字完全相同却指向不同的源
    val sources = remember(feeds) { feeds.distinctBy { it.pluginId } }
    val currentSourceFeeds =
        remember(feeds, selectedFeed) {
            feeds.filter { it.pluginId == selectedFeed?.pluginId }
        }

    val gridState = rememberLazyGridState()
    val isListLayout = layoutSettings.layout == DiscoverLayoutMode.LIST
    var showLayoutSheet by remember { mutableStateOf(false) }

    // 触底预取。derivedStateOf 让滚动的每一帧都不触发重组，只有跨过阈值那一刻才发信号
    val reachedPrefetchEdge by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible =
                info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= info.totalItemsCount - LOAD_MORE_PREFETCH
        }
    }
    // browseItems.size 也当 key：一页数据太少时阈值条件持续成立，
    // 只监听 reachedPrefetchEdge 的话它不会二次变化，翻页就停在这里了
    LaunchedEffect(reachedPrefetchEdge, browseItems.size, browseNextCursor, mode) {
        if (
            mode == DiscoverViewModel.Mode.BROWSE && reachedPrefetchEdge && browseNextCursor != null
        ) {
            viewModel.loadMore(application.pluginBrowseRepository)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            // 顶栏与筛选控件都放在 Scaffold 的 topBar 里：它们不参与 lazy 布局，
            // 滚多远都留在原地，换分类不必先滚回顶部
            Column {
                if (mode == DiscoverViewModel.Mode.SEARCH) {
                    DiscoverSearchTopBar(
                        query = query,
                        onQueryChange = viewModel::updateQuery,
                        onSearch = {
                            viewModel.performSearch(
                                application.pluginCatalog,
                                activeHealthyPlugins,
                            )
                        },
                        onExit = { viewModel.exitSearch(application.pluginBrowseRepository) },
                    )
                } else {
                    DiscoverBrowseTopBar(
                        sources = sources,
                        currentPluginId = selectedFeed?.pluginId,
                        onSelectSource = { pluginId ->
                            viewModel.selectSource(application.pluginBrowseRepository, pluginId)
                        },
                        onSearch = { viewModel.enterSearch() },
                        onOpenLayout = { showLayoutSheet = true },
                        onOpenSources = onOpenSources,
                    )
                }

                if (mode == DiscoverViewModel.Mode.SEARCH) {
                    SourceFilterChips(
                        plugins = activeHealthyPlugins,
                        selectedIds = currentSelectedIds,
                        onToggle = { pluginId ->
                            viewModel.togglePluginSelection(
                                pluginId,
                                activeHealthyPlugins.map { it.state.pluginId },
                            )
                            if (query.isNotBlank()) {
                                viewModel.performSearch(
                                    application.pluginCatalog,
                                    activeHealthyPlugins,
                                )
                            }
                        },
                        onSelectAll = {
                            viewModel.selectAllPlugins(
                                activeHealthyPlugins.map { it.state.pluginId }
                            )
                            if (query.isNotBlank()) {
                                viewModel.performSearch(
                                    application.pluginCatalog,
                                    activeHealthyPlugins,
                                )
                            }
                        },
                    )
                } else if (currentSourceFeeds.isNotEmpty()) {
                    FeedCategoryChips(
                        feeds = currentSourceFeeds,
                        selectedFeedKey = selectedFeedKey,
                        onSelect = { feedKey ->
                            viewModel.selectFeed(application.pluginBrowseRepository, feedKey)
                        },
                    )
                    selectedFeed
                        ?.descriptor
                        ?.filters
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { filters ->
                            BrowseFilterRow(
                                filters = filters,
                                selected = browseFilters,
                                onSelected = { filterId, optionId ->
                                    viewModel.selectBrowseFilter(
                                        application.pluginBrowseRepository,
                                        filterId,
                                        optionId,
                                    )
                                },
                            )
                        }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                viewModel.refresh(
                    application.pluginCatalog,
                    application.pluginBrowseRepository,
                    activeHealthyPlugins,
                )
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyVerticalGrid(
                // 列表就是"只有一列的网格"：换 columns 而不是给每个 item 传
                // maxLineSpan，两种布局共用同一份 item 声明
                columns =
                    if (isListLayout) {
                        GridCells.Fixed(1)
                    } else {
                        layoutSettings.columns.fixedCount?.let { GridCells.Fixed(it) }
                            ?: GridCells.Adaptive(minSize = GridDefaults.AdaptiveMinCellWidth)
                    },
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(if (isListLayout) 0.dp else 12.dp),
            ) {
                if (activeHealthyPlugins.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { NoSources(onOpenSources) }
                } else if (mode == DiscoverViewModel.Mode.BROWSE) {
                    browseContent(
                        feeds = feeds,
                        selectedFeed = selectedFeed,
                        isLoadingFeeds = isLoadingFeeds,
                        feedLoadError = feedLoadError,
                        browseItems = browseItems,
                        browseError = browseError,
                        isBrowsing = isBrowsing,
                        hasMore = browseNextCursor != null,
                        isListLayout = isListLayout,
                        onOpenComic = onOpenComic,
                        onReloadFeeds = {
                            viewModel.loadFeeds(
                                application.pluginCatalog,
                                application.pluginBrowseRepository,
                                activeHealthyPlugins,
                                force = true,
                            )
                        },
                        onRetryBrowse = {
                            viewModel.retryBrowse(application.pluginBrowseRepository)
                        },
                    )
                } else {
                    searchContent(
                        visibleResults = visibleResults,
                        installedPlugins = installedPlugins,
                        query = query,
                        isSearching = isSearching,
                        errorMessage = errorMessage,
                        hasNoResults = hasNoResults,
                        isListLayout = isListLayout,
                        onOpenComic = onOpenComic,
                        onRetry = {
                            viewModel.retrySearch(
                                application.pluginCatalog,
                                activeHealthyPlugins,
                            )
                        },
                    )
                }
            }
        }
    }

    if (showLayoutSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLayoutSheet = false },
            sheetState = rememberExpandOnlySheetState(),
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            DiscoverLayoutSheetContent(
                settings = layoutSettings,
                onLayoutChange = viewModel::setLayout,
                onColumnsChange = viewModel::setColumns,
            )
        }
    }
}

/** 网格封面比例：2:3，漫画单行本最常见的开本 */
private const val COVER_ASPECT = 0.72f

// ---------------------------------------------------------------------------
// 顶栏
// ---------------------------------------------------------------------------

/** 浏览态顶栏：源名占标题位（多源时可下拉切换），右侧是搜索 / 排版 / 源管理。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverBrowseTopBar(
    sources: List<DiscoverViewModel.Feed>,
    currentPluginId: String?,
    onSelectSource: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenLayout: () -> Unit,
    onOpenSources: () -> Unit,
) {
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    val currentName = sources.firstOrNull { it.pluginId == currentPluginId }?.pluginName

    TopAppBar(
        title = {
            if (currentName == null) {
                Text("发现")
            } else if (sources.size <= 1) {
                Text(currentName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Box {
                    // contentPadding 归零：TextButton 自带的横向内边距会让源名
                    // 比其他屏的标题多缩进一截，顶栏起始线就对不齐了
                    TextButton(
                        onClick = { sourceMenuExpanded = true },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            text = currentName,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            painter =
                                painterResource(
                                    MaterialSymbolsOutlinedR.drawable
                                        .materialsymbols_ic_expand_more_outlined
                                ),
                            contentDescription = "切换漫画源",
                            modifier = Modifier.padding(start = 4.dp).size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = sourceMenuExpanded,
                        onDismissRequest = { sourceMenuExpanded = false },
                    ) {
                        sources.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.pluginName) },
                                onClick = {
                                    sourceMenuExpanded = false
                                    onSelectSource(source.pluginId)
                                },
                                trailingIcon =
                                    if (source.pluginId == currentPluginId) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null,
                            )
                        }
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onSearch) {
                Icon(
                    painter =
                        painterResource(
                            MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_search_outlined
                        ),
                    contentDescription = "搜索漫画",
                )
            }
            IconButton(onClick = onOpenLayout) {
                Icon(painter = painterResource(R.drawable.ic_tune), contentDescription = "发现页排版")
            }
            IconButton(onClick = onOpenSources) {
                Icon(
                    painter =
                        painterResource(
                            MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_extension_outlined
                        ),
                    contentDescription = "漫画源管理",
                )
            }
        },
    )
}

/** 搜索态顶栏：标题位原地变成输入框，返回箭头退出搜索。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onExit: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // 进入搜索即聚焦并弹键盘：点了搜索图标却还要再点一次输入框，是白费的一步。
    // 先等一帧——顶栏刚从标题换成输入框，TextField 的焦点节点还没挂上布局树，
    // 这一帧里直接 requestFocus 会撞上 "FocusRequester is not initialized"
    LaunchedEffect(Unit) {
        withFrameNanos {}
        focusRequester.requestFocus()
    }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onExit) {
                Icon(
                    painter =
                        painterResource(
                            MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_arrow_back_outlined
                        ),
                    contentDescription = "退出搜索",
                )
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("搜索漫画...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            onSearch()
                        }
                    ),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        painter =
                            painterResource(
                                MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_close_outlined
                            ),
                        contentDescription = "清除搜索词",
                    )
                }
            }
        },
    )
}

/** 当前源的分类 chips。 */
@Composable
private fun FeedCategoryChips(
    feeds: List<DiscoverViewModel.Feed>,
    selectedFeedKey: String?,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(feeds, key = { it.key }) { feed ->
            val isSelected = feed.key == selectedFeedKey
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(feed.key) },
                label = { Text(feed.descriptor.title) },
                leadingIcon =
                    if (isSelected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else null,
            )
        }
    }
}

/** 搜索态的源筛选 chips。 */
@Composable
private fun SourceFilterChips(
    plugins: List<InstalledPlugin>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            val isAllSelected = selectedIds.size == plugins.size
            FilterChip(
                selected = isAllSelected,
                onClick = onSelectAll,
                label = { Text("全部") },
                leadingIcon =
                    if (isAllSelected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else null,
            )
        }
        items(plugins, key = { it.state.pluginId }) { plugin ->
            val isSelected = plugin.state.pluginId in selectedIds
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(plugin.state.pluginId) },
                label = { Text(plugin.manifest?.name ?: plugin.state.pluginId) },
                leadingIcon =
                    if (isSelected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else null,
            )
        }
    }
}

/** 当前分类自带的筛选项（排序、地区等），由插件描述符驱动。 */
@Composable
private fun BrowseFilterRow(
    filters: List<PluginFilterDescriptor>,
    selected: Map<String, String>,
    onSelected: (String, String) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(filters, key = { it.id }) { filter ->
            BrowseFilterMenu(
                filter = filter,
                selectedOptionId = selected[filter.id],
                onSelected = { optionId -> onSelected(filter.id, optionId) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 内容
// ---------------------------------------------------------------------------

private fun LazyGridScope.browseContent(
    feeds: List<DiscoverViewModel.Feed>,
    selectedFeed: DiscoverViewModel.Feed?,
    isLoadingFeeds: Boolean,
    feedLoadError: String?,
    browseItems: List<ComicSummary>,
    browseError: String?,
    isBrowsing: Boolean,
    hasMore: Boolean,
    isListLayout: Boolean,
    onOpenComic: (String, ComicSummary) -> Unit,
    onReloadFeeds: () -> Unit,
    onRetryBrowse: () -> Unit,
) {
    if (isLoadingFeeds && feeds.isEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) { CenteredProgress() }
        return
    }

    if (feeds.isEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            if (feedLoadError != null) {
                M3ErrorBanner(message = feedLoadError, onRetry = onReloadFeeds)
            } else {
                Text(
                    "已启用的漫画源暂不支持推荐或分类浏览，请使用搜索。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        return
    }

    feedLoadError?.let { error ->
        item(span = { GridItemSpan(maxLineSpan) }) {
            M3ErrorBanner(message = error, onRetry = onReloadFeeds)
        }
    }

    val feed = selectedFeed ?: return

    browseError?.let { error ->
        item(span = { GridItemSpan(maxLineSpan) }) {
            M3ErrorBanner(message = error, onRetry = onRetryBrowse)
        }
    }

    if (isBrowsing && browseItems.isEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) { CenteredProgress() }
    } else if (!isBrowsing && browseError == null && browseItems.isEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "暂无内容",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }

    items(browseItems, key = { "${feed.pluginId}_${it.sourceId}" }) { comic ->
        DiscoverComicItem(
            comic = comic,
            isListLayout = isListLayout,
            onClick = { onOpenComic(feed.pluginId, comic) },
        )
    }

    // 翻页由触底预取驱动，底部只需要一个"还在加载"的提示
    if (isBrowsing && browseItems.isNotEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) { CenteredProgress() }
    } else if (hasMore && browseError != null) {
        // 预取失败后自动重试会立刻再撞上同一个错误，这里给一次手动机会
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                OutlinedButton(onClick = onRetryBrowse) { Text("加载更多") }
            }
        }
    }
}

private fun LazyGridScope.searchContent(
    visibleResults: List<PluginSearchResult>,
    installedPlugins: List<InstalledPlugin>,
    query: String,
    isSearching: Boolean,
    errorMessage: String?,
    hasNoResults: Boolean,
    isListLayout: Boolean,
    onOpenComic: (String, ComicSummary) -> Unit,
    onRetry: () -> Unit,
) {
    if (isSearching) {
        item(span = { GridItemSpan(maxLineSpan) }) { CenteredProgress() }
    }

    errorMessage?.let { error ->
        item(span = { GridItemSpan(maxLineSpan) }) {
            M3ErrorBanner(message = error, onRetry = onRetry)
        }
    }

    if (!isSearching && errorMessage == null && query.isNotBlank() && hasNoResults) {
        item(span = { GridItemSpan(maxLineSpan) }) { SearchEmptyState() }
        return
    }

    visibleResults.forEach { result ->
        val sourceName =
            installedPlugins.firstOrNull { it.state.pluginId == result.pluginId }?.manifest?.name
                ?: result.pluginId

        item(span = { GridItemSpan(maxLineSpan) }, key = "header_${result.pluginId}") {
            Text(
                text = sourceName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }

        val error = result.error
        if (error != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "error_${result.pluginId}") {
                M3ErrorBanner(message = "加载失败: ${error.message}", onRetry = onRetry)
            }
            return@forEach
        }

        val comics = result.page?.items.orEmpty()
        if (comics.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty_${result.pluginId}") {
                Text(
                    "无搜索结果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            return@forEach
        }

        items(comics, key = { "${result.pluginId}_${it.sourceId}" }) { comic ->
            DiscoverComicItem(
                comic = comic,
                isListLayout = isListLayout,
                onClick = { onOpenComic(result.pluginId, comic) },
            )
        }
    }
}

/** 单个漫画条目：按当前排版渲染成封面卡片或信息行。 */
@Composable
private fun DiscoverComicItem(
    comic: ComicSummary,
    isListLayout: Boolean,
    onClick: () -> Unit,
) {
    if (isListLayout) {
        DiscoverComicRow(comic = comic, onClick = onClick)
    } else {
        DiscoverComicCard(comic = comic, onClick = onClick)
    }
}

// ---------------------------------------------------------------------------
// 条目
// ---------------------------------------------------------------------------

/** 封面 + 标题的网格卡片。 */
@Composable
private fun DiscoverComicCard(
    comic: ComicSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column {
            ComicCover(
                cover = comic.cover,
                contentDescription = comic.title,
                modifier =
                    Modifier.fillMaxWidth()
                        .aspectRatio(COVER_ASPECT)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            )

            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = comic.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                comic.subtitle
                    ?.takeIf { it.isNotBlank() }
                    ?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
            }
        }
    }
}

/**
 * 密度型信息行：无卡片、无阴影，靠分隔线切分，一屏能放下网格两倍的条目。
 *
 * 第三行放 tags 而不是源名：源名在这两种场景下都已由顶栏或分组标题给出，而 tags 是网格卡片 塞不下、却真正帮人决定"要不要点进去"的信息。
 */
@Composable
private fun DiscoverComicRow(
    comic: ComicSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ComicCover(
                cover = comic.cover,
                contentDescription = comic.title,
                modifier =
                    Modifier.size(width = 64.dp, height = 88.dp).clip(RoundedCornerShape(6.dp)),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = comic.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                comic.subtitle
                    ?.takeIf { it.isNotBlank() }
                    ?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                comic.tags
                    .takeIf { it.isNotEmpty() }
                    ?.let { tags ->
                        Text(
                            text = tags.take(3).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
    }
}

/** 封面图。网格卡片和信息行共用：插件封面往往需要 Referer 或自定义请求头才能取到图， 这段逻辑复制两份就迟早会改一处漏一处。 */
@Composable
private fun ComicCover(
    cover: PageImage?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (cover == null) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_file),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
        return
    }

    val imageRequest =
        remember(cover) {
            ImageRequest.Builder(context)
                .data(cover.url)
                .apply {
                    cover.headers.forEach { (name, value) -> setHeader(name, value) }
                    cover.referer?.let { setHeader("Referer", it) }
                }
                .crossfade(150)
                .build()
        }
    AsyncImage(
        model = imageRequest,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// 排版抽屉
// ---------------------------------------------------------------------------

/** 发现页排版：布局形态 + 网格列数。与书架排版抽屉共用同一套视觉语言。 */
@Composable
private fun DiscoverLayoutSheetContent(
    settings: DiscoverLayoutSettings,
    onLayoutChange: (DiscoverLayoutMode) -> Unit,
    onColumnsChange: (GridColumnsMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetColumn(modifier = modifier.padding(horizontal = 16.dp)) {
        Text(text = "发现页排版", style = MaterialTheme.typography.titleLarge)

        SheetSectionLabel("布局")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            DiscoverLayoutMode.entries.forEachIndexed { index, layout ->
                SegmentedButton(
                    selected = settings.layout == layout,
                    onClick = { onLayoutChange(layout) },
                    shape =
                        SegmentedButtonDefaults.itemShape(index, DiscoverLayoutMode.entries.size),
                    icon = {
                        Icon(
                            painter =
                                painterResource(
                                    when (layout) {
                                        DiscoverLayoutMode.GRID ->
                                            MaterialSymbolsOutlinedR.drawable
                                                .materialsymbols_ic_grid_view_outlined
                                        DiscoverLayoutMode.LIST ->
                                            MaterialSymbolsOutlinedR.drawable
                                                .materialsymbols_ic_view_list_outlined
                                    }
                                ),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) {
                    Text(
                        when (layout) {
                            DiscoverLayoutMode.GRID -> "网格"
                            DiscoverLayoutMode.LIST -> "列表"
                        }
                    )
                }
            }
        }

        // 列数只对网格有意义，列表恒为单列
        if (settings.layout == DiscoverLayoutMode.GRID) {
            SheetSectionLabel("列数")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                GridColumnsMode.entries.forEachIndexed { index, columns ->
                    SegmentedButton(
                        selected = settings.columns == columns,
                        onClick = { onColumnsChange(columns) },
                        shape =
                            SegmentedButtonDefaults.itemShape(index, GridColumnsMode.entries.size),
                    ) {
                        Text(columns.fixedCount?.toString() ?: "自适应")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 状态与提示
// ---------------------------------------------------------------------------

@Composable
private fun SearchEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter =
                painterResource(
                    MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_search_off_outlined
                ),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = "未找到相关漫画",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "尝试更换搜索词，或勾选更多漫画源",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BrowseFilterMenu(
    filter: PluginFilterDescriptor,
    selectedOptionId: String?,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedTitle =
        filter.options.firstOrNull { it.id == selectedOptionId }?.title
            ?: filter.options.firstOrNull()?.title
            ?: "选择"
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("${filter.title}: $selectedTitle")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            filter.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title) },
                    onClick = {
                        expanded = false
                        onSelected(option.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun NoSources(onOpenSources: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter =
                painterResource(
                    MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_extension_outlined
                ),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("暂无可用的漫画源", style = MaterialTheme.typography.titleMedium)
        Text(
            "请先添加并启用插件。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpenSources) { Text("管理漫画源") }
    }
}

@Composable
private fun CenteredProgress() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun M3ErrorBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = onRetry,
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
            ) {
                Text("重试")
            }
        }
    }
}
