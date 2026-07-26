package com.exio.inkleaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.res.painterResource
import com.exio.inkleaf.R
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.plugin.ComicSummary
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginFilterDescriptor
import com.exio.inkleaf.plugin.PluginHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val isLoadingFeeds by viewModel.isLoadingFeeds.collectAsStateWithLifecycle()
    val feedLoadError by viewModel.feedLoadError.collectAsStateWithLifecycle()
    val selectedFeedKey by viewModel.selectedFeedKey.collectAsStateWithLifecycle()
    val browseFilters by viewModel.browseFilters.collectAsStateWithLifecycle()
    val browseItems by viewModel.browseItems.collectAsStateWithLifecycle()
    val browseNextCursor by viewModel.browseNextCursor.collectAsStateWithLifecycle()
    val isBrowsing by viewModel.isBrowsing.collectAsStateWithLifecycle()
    val browseError by viewModel.browseError.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedPluginIds by viewModel.selectedPluginIds.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    var installedPlugins by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }

    LaunchedEffect(Unit) {
        installedPlugins = withContext(Dispatchers.IO) { application.pluginManager.installed() }
    }

    val activeHealthyPlugins = remember(installedPlugins) {
        installedPlugins.filter {
            !it.state.disabled && it.state.health == PluginHealth.HEALTHY && it.state.activeVersion != null
        }
    }
    val activePluginSignature = remember(activeHealthyPlugins) {
        activeHealthyPlugins.map { "${it.state.pluginId}:${it.state.activeVersion}" }
    }
    LaunchedEffect(activePluginSignature) {
        viewModel.loadFeeds(application.pluginCatalog, activeHealthyPlugins)
    }

    val currentSelectedIds = selectedPluginIds ?: remember(activeHealthyPlugins) {
        activeHealthyPlugins.map { it.state.pluginId }.toSet()
    }
    val visibleResults = remember(results, currentSelectedIds) {
        results.filter { it.pluginId in currentSelectedIds }
    }
    val hasNoResults = remember(visibleResults) {
        visibleResults.isEmpty() || visibleResults.all { result ->
            result.error == null && result.page?.items.orEmpty().isEmpty()
        }
    }
    val selectedFeed = feeds.firstOrNull { it.key == selectedFeedKey }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("发现") },
                actions = {
                    IconButton(onClick = onOpenSources) {
                        Icon(Icons.Default.Settings, contentDescription = "漫画源管理")
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Mode Tabs (Browse vs Search)
            item(span = { GridItemSpan(maxLineSpan) }) {
                PrimaryTabRow(selectedTabIndex = if (mode == DiscoverViewModel.Mode.BROWSE) 0 else 1) {
                    Tab(
                        selected = mode == DiscoverViewModel.Mode.BROWSE,
                        onClick = { viewModel.selectMode(DiscoverViewModel.Mode.BROWSE) },
                        text = { Text("浏览") },
                    )
                    Tab(
                        selected = mode == DiscoverViewModel.Mode.SEARCH,
                        onClick = { viewModel.selectMode(DiscoverViewModel.Mode.SEARCH) },
                        text = { Text("搜索") },
                    )
                }
            }

            if (activeHealthyPlugins.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    NoSources(onOpenSources)
                }
            } else if (mode == DiscoverViewModel.Mode.BROWSE) {
                if (isLoadingFeeds && feeds.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CenteredProgress()
                    }
                } else if (feeds.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        if (feedLoadError != null) {
                            M3ErrorBanner(
                                message = feedLoadError.orEmpty(),
                                onRetry = {
                                    viewModel.loadFeeds(application.pluginCatalog, activeHealthyPlugins)
                                },
                            )
                        } else {
                            Text(
                                "已启用的漫画源暂不支持推荐或分类浏览，请使用搜索。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                } else {
                    feedLoadError?.let { error ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            M3ErrorBanner(
                                message = error,
                                onRetry = {
                                    viewModel.loadFeeds(application.pluginCatalog, activeHealthyPlugins)
                                },
                            )
                        }
                    }

                    // Feed selection chips
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(feeds, key = { it.key }) { feed ->
                                val isSelected = feed.key == selectedFeedKey
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.selectFeed(application.pluginCatalog, feed.key) },
                                    label = { Text(feed.descriptor.title) },
                                    leadingIcon = if (isSelected) {
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

                    selectedFeed?.descriptor?.filters?.takeIf { it.isNotEmpty() }?.let { filters ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(filters, key = { it.id }) { filter ->
                                    BrowseFilterMenu(
                                        filter = filter,
                                        selectedOptionId = browseFilters[filter.id],
                                        onSelected = { optionId ->
                                            viewModel.selectBrowseFilter(
                                                application.pluginCatalog,
                                                filter.id,
                                                optionId,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    selectedFeed?.let { feed ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = "${feed.pluginName} · ${feed.descriptor.title}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        browseError?.let { error ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                M3ErrorBanner(
                                    message = error,
                                    onRetry = { viewModel.retryBrowse(application.pluginCatalog) },
                                )
                            }
                        }

                        if (isBrowsing && browseItems.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                CenteredProgress()
                            }
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
                            DiscoverComicCard(
                                comic = comic,
                                sourceName = feed.pluginName,
                                onClick = { onOpenComic(feed.pluginId, comic) },
                            )
                        }

                        if (isBrowsing && browseItems.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                            }
                        } else if (browseNextCursor != null) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    OutlinedButton(onClick = { viewModel.loadMore(application.pluginCatalog) }) {
                                        Text("加载更多")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // SEARCH Mode
                item(span = { GridItemSpan(maxLineSpan) }) {
                    var searchActive by remember { mutableStateOf(false) }
                    DockedSearchBar(
                        inputField = {
                            SearchBarDefaults.InputField(
                                query = query,
                                onQueryChange = viewModel::updateQuery,
                                onSearch = {
                                    searchActive = false
                                    viewModel.performSearch(application.pluginCatalog, activeHealthyPlugins)
                                },
                                expanded = searchActive,
                                onExpandedChange = { searchActive = it },
                                placeholder = { Text("搜索漫画...") },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = "搜索")
                                },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.updateQuery("") }) {
                                            Icon(Icons.Default.Close, contentDescription = "清除")
                                        }
                                    }
                                },
                            )
                        },
                        expanded = searchActive,
                        onExpandedChange = { searchActive = it },
                        modifier = Modifier.fillMaxWidth(),
                    ) {}
                }

                // Source selection FilterChips Group
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        item {
                            val isAllSelected = currentSelectedIds.size == activeHealthyPlugins.size
                            FilterChip(
                                selected = isAllSelected,
                                onClick = {
                                    viewModel.selectAllPlugins(activeHealthyPlugins.map { it.state.pluginId })
                                    if (query.isNotBlank()) {
                                        viewModel.performSearch(application.pluginCatalog, activeHealthyPlugins)
                                    }
                                },
                                label = { Text("全部") },
                                leadingIcon = if (isAllSelected) {
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
                        items(activeHealthyPlugins, key = { it.state.pluginId }) { plugin ->
                            val isSelected = plugin.state.pluginId in currentSelectedIds
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    viewModel.togglePluginSelection(
                                        plugin.state.pluginId,
                                        activeHealthyPlugins.map { it.state.pluginId },
                                    )
                                    if (query.isNotBlank()) {
                                        viewModel.performSearch(application.pluginCatalog, activeHealthyPlugins)
                                    }
                                },
                                label = { Text(plugin.manifest?.name ?: plugin.state.pluginId) },
                                leadingIcon = if (isSelected) {
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

                if (isSearching) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                }

                errorMessage?.let { error ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        M3ErrorBanner(
                            message = error,
                            onRetry = {
                                viewModel.retrySearch(application.pluginCatalog, activeHealthyPlugins)
                            },
                        )
                    }
                }

                if (!isSearching && errorMessage == null && query.isNotBlank() && hasNoResults) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SearchEmptyState()
                    }
                } else {
                    visibleResults.forEach { result ->
                        val sourcePlugin = installedPlugins.firstOrNull { it.state.pluginId == result.pluginId }
                        val sourceName = sourcePlugin?.manifest?.name ?: result.pluginId

                        item(span = { GridItemSpan(maxLineSpan) }, key = "header_${result.pluginId}") {
                            Text(
                                text = sourceName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }

                        result.error?.let { error ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "error_${result.pluginId}") {
                                M3ErrorBanner(
                                    message = "加载失败: ${error.message}",
                                    onRetry = {
                                        viewModel.retrySearch(application.pluginCatalog, activeHealthyPlugins)
                                    },
                                )
                            }
                        } ?: run {
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
                            } else {
                                items(comics, key = { "${result.pluginId}_${it.sourceId}" }) { comic ->
                                    DiscoverComicCard(
                                        comic = comic,
                                        sourceName = sourceName,
                                        onClick = { onOpenComic(result.pluginId, comic) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchEmptyState(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Search,
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
    val selectedTitle = filter.options.firstOrNull { it.id == selectedOptionId }?.title
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
            Icons.Default.Settings,
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
        colors = CardDefaults.cardColors(
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
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun DiscoverComicCard(
    comic: ComicSummary,
    sourceName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            ) {
                val cover = comic.cover
                if (cover != null) {
                    val imageRequest = remember(cover) {
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
                        contentDescription = comic.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_file),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }

                // Source badge overlaid on top-left of cover card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Text(
                        text = sourceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

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
                comic.subtitle?.let { subtitle ->
                    if (subtitle.isNotBlank()) {
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
}
