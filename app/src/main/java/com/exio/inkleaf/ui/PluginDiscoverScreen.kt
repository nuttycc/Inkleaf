package com.exio.inkleaf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
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
    val activeHealthyPluginIds = remember(activeHealthyPlugins) {
        activeHealthyPlugins.map { it.state.pluginId }.toSet()
    }
    val visibleResults = remember(results, activeHealthyPluginIds) {
        results.filter { it.pluginId in activeHealthyPluginIds }
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
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
                item { NoSources(onOpenSources) }
            } else if (mode == DiscoverViewModel.Mode.BROWSE) {
                if (isLoadingFeeds && feeds.isEmpty()) {
                    item { CenteredProgress() }
                } else if (feeds.isEmpty()) {
                    item {
                        if (feedLoadError != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    feedLoadError.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(
                                    onClick = {
                                        viewModel.loadFeeds(application.pluginCatalog, activeHealthyPlugins)
                                    }
                                ) { Text("重试") }
                            }
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
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        viewModel.loadFeeds(application.pluginCatalog, activeHealthyPlugins)
                                    }
                                ) { Text("重试") }
                            }
                        }
                    }
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(feeds, key = { it.key }) { feed ->
                                FilterChip(
                                    selected = feed.key == selectedFeedKey,
                                    onClick = { viewModel.selectFeed(application.pluginCatalog, feed.key) },
                                    label = { Text(feed.descriptor.title) },
                                )
                            }
                        }
                    }

                    selectedFeed?.descriptor?.filters?.takeIf { it.isNotEmpty() }?.let { filters ->
                        item {
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
                        item {
                            Text(
                                text = "${feed.pluginName} · ${feed.descriptor.title}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        browseError?.let { error ->
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = error,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { viewModel.retryBrowse(application.pluginCatalog) }) {
                                        Text("重试")
                                    }
                                }
                            }
                        }

                        if (isBrowsing && browseItems.isEmpty()) {
                            item { CenteredProgress() }
                        } else if (!isBrowsing && browseError == null && browseItems.isEmpty()) {
                            item {
                                Text(
                                    "暂无内容",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        items(browseItems, key = { "${feed.pluginId}_${it.sourceId}" }) { comic ->
                            DiscoverComicRow(
                                comic = comic,
                                sourceName = feed.pluginName,
                                onClick = { onOpenComic(feed.pluginId, comic) },
                            )
                        }

                        if (isBrowsing && browseItems.isNotEmpty()) {
                            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                        } else if (browseNextCursor != null) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    TextButton(onClick = { viewModel.loadMore(application.pluginCatalog) }) {
                                        Text("加载更多")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::updateQuery,
                        label = { Text("搜索漫画") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                viewModel.performSearch(application.pluginCatalog, activeHealthyPlugins)
                            }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        item {
                            FilterChip(
                                selected = currentSelectedIds.size == activeHealthyPlugins.size,
                                onClick = {
                                    viewModel.selectAllPlugins(activeHealthyPlugins.map { it.state.pluginId })
                                },
                                label = { Text("全部") },
                            )
                        }
                        items(activeHealthyPlugins, key = { it.state.pluginId }) { plugin ->
                            FilterChip(
                                selected = plugin.state.pluginId in currentSelectedIds,
                                onClick = {
                                    viewModel.togglePluginSelection(
                                        plugin.state.pluginId,
                                        activeHealthyPlugins.map { it.state.pluginId },
                                    )
                                },
                                label = { Text(plugin.manifest?.name ?: plugin.state.pluginId) },
                            )
                        }
                    }
                }

                if (isSearching) item { CenteredProgress() }
                errorMessage?.let { error ->
                    item {
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                }

                visibleResults.forEach { result ->
                    val sourcePlugin = installedPlugins.firstOrNull { it.state.pluginId == result.pluginId }
                    val sourceName = sourcePlugin?.manifest?.name ?: result.pluginId
                    item(key = "header_${result.pluginId}") {
                        Text(
                            text = sourceName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    result.error?.let { error ->
                        item(key = "error_${result.pluginId}") {
                            Text(
                                text = "加载失败: ${error.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } ?: run {
                        val comics = result.page?.items.orEmpty()
                        if (comics.isEmpty()) {
                            item(key = "empty_${result.pluginId}") {
                                Text(
                                    "无搜索结果",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(comics, key = { "${result.pluginId}_${it.sourceId}" }) { comic ->
                                DiscoverComicRow(
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
private fun DiscoverComicRow(
    comic: ComicSummary,
    sourceName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        comic.cover?.let { cover ->
            val imageRequest = remember(cover) {
                ImageRequest.Builder(context)
                    .data(cover.url)
                    .apply {
                        cover.headers.forEach { (name, value) -> setHeader(name, value) }
                        cover.referer?.let { setHeader("Referer", it) }
                    }
                    .crossfade(120)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = comic.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 48.dp, height = 64.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = comic.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            comic.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = sourceName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}
