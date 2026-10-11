package com.exio.inkleaf.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginInstallResult
import com.exio.inkleaf.plugin.PluginInstallStatus
import com.exio.inkleaf.plugin.PluginRepoContract
import com.exio.inkleaf.plugin.PluginRepoEntry
import com.exio.inkleaf.plugin.PluginUpdatePlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * The repository screen, reached from the source list's import menu: everything the index advertises,
 * marked with what is already on the device. The index URL is a setting and lives behind the overflow
 * menu rather than on the resting list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginRepoScreen(
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as InkleafApplication
    val repo = rememberPluginRepoState()
    val feedback = rememberSourceFeedback()
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsDraft by rememberSaveable { mutableStateOf("") }
    var settingsError by rememberSaveable { mutableStateOf<String?>(null) }

    val rows = remember(repo.index, installed) {
        SourceLifecycleRows.catalogRows(repo.index, installed)
    }
    val updates = remember(repo.index, installed) {
        repo.index?.let { PluginUpdatePlanner.updates(it.plugins, installed) }.orEmpty()
    }

    suspend fun refreshInstalled() {
        installed = withContext(Dispatchers.IO) { application.pluginManager.installed() }
    }

    fun entryOf(pluginId: String): PluginRepoEntry? =
        repo.index?.plugins?.firstOrNull { it.id == pluginId }

    fun runOperation(operation: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                operation()
                refreshInstalled()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                feedback.show(
                    "操作失败: ${error.message ?: "未知错误"}",
                    SourceFeedbackKind.ERROR,
                )
                runCatching { refreshInstalled() }
            } finally {
                if (currentCoroutineContext().isActive) busy = false
            }
        }
    }

    fun report(outcome: PluginInstallResult) {
        val name = outcome.pluginId ?: "未知插件"
        val version = outcome.version?.let { " v$it" } ?: ""
        when (outcome.status) {
            PluginInstallStatus.INSTALLED,
            PluginInstallStatus.ALREADY_INSTALLED ->
                feedback.show(
                    if (outcome.activatable) "已安装 $name$version"
                    else "已安装 $name$version，但未激活：与当前版本不兼容",
                    if (outcome.activatable) SourceFeedbackKind.INFO else SourceFeedbackKind.ERROR,
                    actionLabel = outcome.pluginId?.let { "打开" },
                ) {
                    outcome.pluginId?.let(onOpenSource)
                }
            PluginInstallStatus.REJECTED ->
                feedback.show(
                    "安装失败: ${outcome.errorMessage ?: outcome.errorCode?.name ?: "安装被拒绝"}",
                    SourceFeedbackKind.ERROR,
                )
        }
    }

    // Updates run one at a time: each install activates (and, on failure, rolls back) a plugin
    // runtime, so overlapping them would only add contention.
    fun install(entry: PluginRepoEntry) {
        runOperation {
            report(application.pluginManager.installUrl(entry.downloadSource(), activate = true))
        }
    }

    fun updateAll() {
        val candidates = updates
        runOperation {
            var updated = 0
            var failed = 0
            for (candidate in candidates) {
                val outcome =
                    application.pluginManager.installUrl(
                        candidate.entry.downloadSource(),
                        activate = true,
                    )
                if (outcome.status == PluginInstallStatus.REJECTED) failed++ else updated++
            }
            feedback.show(
                if (failed == 0) "已更新 $updated 个漫画源"
                else "已更新 $updated 个漫画源，$failed 个失败",
                if (failed == 0) SourceFeedbackKind.INFO else SourceFeedbackKind.ERROR,
            )
        }
    }

    fun loadRepo(url: String) {
        if (busy) return
        scope.launch {
            busy = true
            settingsError = null
            try {
                repo.load(url)
                showSettings = false
                feedback.show("已从插件仓库读取 ${repo.index?.plugins?.size ?: 0} 个插件")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                settingsError = error.message ?: "无法读取插件仓库"
            } finally {
                if (currentCoroutineContext().isActive) busy = false
            }
        }
    }

    LaunchedEffect(Unit) { refreshInstalled() }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SourceSnackbarHost(feedback.hostState) },
        topBar = {
            TopAppBar(
                title = { Text("插件仓库") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "仓库选项")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("仓库设置") },
                            onClick = {
                                showMenu = false
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

            item(key = "repo-status") {
                Text(
                    text = repoSummary(repo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (updates.isNotEmpty()) {
                item(key = "batch-update") {
                    UpdateBanner(count = updates.size, busy = busy, onUpdateAll = { updateAll() })
                }
            }

            if (repo.index == null) {
                item(key = "repo-load") {
                    Text(
                        text = "仓库目录尚未加载。点右上角「仓库设置」填写索引地址并加载。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(rows, key = { it.pluginId }) { row ->
                SourceLifecycleRowItem(
                    row = row,
                    busy = busy,
                    onActivate = { entryOf(row.pluginId)?.let { install(it) } },
                    onOpen = { onOpenSource(row.pluginId) },
                )
            }
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
