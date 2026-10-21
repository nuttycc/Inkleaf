package com.exio.inkleaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.outlined.R as MaterialSymbolsOutlinedR
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.R
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginHealth
import com.exio.inkleaf.plugin.PluginRepoContract
import com.exio.inkleaf.plugin.PluginState
import com.exio.inkleaf.plugin.PluginUpdatePlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which list of sources a screen is showing. */
internal enum class SourceListMode {
    /** The sources on this device, annotated with what the repository index says about them. */
    Device,

    /** Everything the repository index advertises, marked with what is already installed. */
    Repository,
}

/**
 * The one source list screen: the device list and the repository list differ only in where their rows
 * come from, so both share the coordinator, the refresh, the busy gate and the repository sheet.
 *
 * Adding a source is a deliberate detour behind the "+" menu — a local file, a download link, or the
 * repository — so the resting screen carries only what is installed. The repository address is
 * reachable from either list, because the device list annotates its rows with the index and a user who
 * sees wrong update information needs to be able to fix the address where they noticed it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceListScreen(
    mode: SourceListMode,
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    onImportFromFile: () -> Unit = {},
    onImportFromUrl: () -> Unit = {},
    onOpenRepository: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as InkleafApplication
    val repo = rememberPluginRepoState()
    val feedback = rememberSourceFeedback()
    val scope = rememberCoroutineScope()

    var plugins by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var showAddMenu by rememberSaveable { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsDraft by rememberSaveable { mutableStateOf("") }
    var settingsError by rememberSaveable { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        plugins = withContext(Dispatchers.IO) { application.pluginManager.installed() }
    }

    val coordinator =
        remember(application.pluginManager, feedback, scope) {
            SourceInstallCoordinator(
                pluginManager = application.pluginManager,
                feedback = feedback,
                scope = scope,
                refresh = { refresh() },
                onOpenSource = onOpenSource,
            )
        }
    val busy = coordinator.busy

    /** Reads the repository index under the shared busy gate, keeping failures in the settings form. */
    fun loadRepo(url: String) {
        coordinator.runGuarded {
            settingsError = null
            try {
                repo.load(url)
                showSettings = false
                feedback.show("已从插件仓库读取 ${repo.index?.plugins?.size ?: 0} 个插件")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                settingsError = error.message ?: "无法读取插件仓库"
            }
        }
    }

    val rows =
        remember(repo.index, plugins) {
            when (mode) {
                SourceListMode.Device -> SourceLifecycleRows.installedRows(repo.index, plugins)
                SourceListMode.Repository -> SourceLifecycleRows.catalogRows(repo.index, plugins)
            }
        }
    val updates =
        remember(repo.index, plugins) {
            repo.index?.let { PluginUpdatePlanner.updates(it.plugins, plugins) }.orEmpty()
        }

    LaunchedEffect(Unit) { coordinator.runOperation {} }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SourceSnackbarHost(feedback.hostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (mode) {
                            SourceListMode.Device -> "漫画源管理"
                            SourceListMode.Repository -> "插件仓库"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (mode == SourceListMode.Device) {
                        Box {
                            IconButton(onClick = { showAddMenu = true }, enabled = !busy) {
                                Icon(Icons.Default.Add, contentDescription = "添加漫画源")
                            }
                            DropdownMenu(
                                expanded = showAddMenu,
                                onDismissRequest = { showAddMenu = false },
                            ) {
                                AddSourceItem(
                                    "从本地文件导入",
                                    painterResource(R.drawable.ic_folder),
                                ) {
                                    showAddMenu = false
                                    onImportFromFile()
                                }
                                AddSourceItem(
                                    "从 URL 导入",
                                    painterResource(R.drawable.ic_download),
                                ) {
                                    showAddMenu = false
                                    onImportFromUrl()
                                }
                                AddSourceItem(
                                    "从源仓库安装",
                                    painterResource(
                                        MaterialSymbolsOutlinedR.drawable
                                            .materialsymbols_ic_extension_outlined
                                    ),
                                ) {
                                    showAddMenu = false
                                    onOpenRepository()
                                }
                            }
                        }
                    }
                    // The address is reachable from both lists: the device list annotates its rows
                    // with the index, so whoever notices wrong update information can fix it here.
                    IconButton(onClick = { showOptionsMenu = true }, enabled = !busy) {
                        Icon(Icons.Default.MoreVert, contentDescription = "源选项")
                    }
                    DropdownMenu(
                        expanded = showOptionsMenu,
                        onDismissRequest = { showOptionsMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("仓库设置") },
                            onClick = {
                                showOptionsMenu = false
                                settingsDraft = repo.url
                                settingsError = null
                                showSettings = true
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp)) }
            }

            if (mode == SourceListMode.Repository) {
                item(key = "repo-status") {
                    Text(
                        text = repoSummary(repo),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (updates.isNotEmpty()) {
                item(key = "batch-update") {
                    UpdateBanner(
                        count = updates.size,
                        busy = busy,
                        onUpdateAll = { coordinator.updateAll(updates) },
                    )
                }
            }

            if (mode == SourceListMode.Device) {
                item(key = "sources-header") {
                    Text(
                        text = "漫画源 · ${SourceLifecycleRows.usableCount(plugins)} 个可用",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (rows.isEmpty()) {
                    item(key = "sources-empty") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Box(
                                modifier = Modifier.padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "还没有漫画源。点右上角「+」添加。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (mode == SourceListMode.Repository && repo.index == null) {
                item(key = "repo-load") {
                    Text(
                        text = "仓库目录尚未加载。点右上角「源选项」填写索引地址并加载。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(rows, key = { it.pluginId }) { row ->
                SourceLifecycleRowItem(
                    row = row,
                    busy = busy,
                    onActivate = {
                        repo.index
                            ?.plugins
                            ?.firstOrNull { it.id == row.pluginId }
                            ?.let { entry ->
                                if (mode == SourceListMode.Repository) {
                                    coordinator.install(entry)
                                } else {
                                    coordinator.update(entry, row.name)
                                }
                            }
                    },
                    onOpen = { onOpenSource(row.pluginId) },
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            SourceRepoSheetContent(
                url = settingsDraft,
                busy = busy,
                error = settingsError,
                loadedIndex = repo.index,
                onUrlChange = {
                    settingsDraft = it
                    settingsError = null
                },
                onLoad = { loadRepo(it) },
                onUseDefault = {
                    settingsDraft = PluginRepoContract.DEFAULT_REPO_URL
                    loadRepo(PluginRepoContract.DEFAULT_REPO_URL)
                },
            )
        }
    }
}

/** Failure badge for the source detail screen; a healthy source shows nothing. */
@Composable
internal fun HealthStatusBadge(pluginState: PluginState, modifier: Modifier = Modifier) {
    val label = if (pluginState.health == PluginHealth.STORAGE_CORRUPT) "状态损坏" else "运行异常"
    val contentColor = MaterialTheme.colorScheme.onErrorContainer

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(6.dp).background(contentColor, shape = CircleShape))
            Text(text = label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AddSourceItem(label: String, icon: Painter, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(painter = icon, contentDescription = null) },
        onClick = onClick,
    )
}
