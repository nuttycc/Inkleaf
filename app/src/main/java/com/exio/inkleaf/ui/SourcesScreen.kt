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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.exio.inkleaf.plugin.PluginInstallResult
import com.exio.inkleaf.plugin.PluginInstallStatus
import com.exio.inkleaf.plugin.PluginRepoEntry
import com.exio.inkleaf.plugin.PluginState
import com.exio.inkleaf.plugin.PluginUpdatePlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The comic sources on this device. Adding one is a deliberate detour behind the "+" menu — a local
 * file, a download link, or the repository — so the resting screen carries only what is installed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit = {},
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
    var busy by remember { mutableStateOf(false) }
    var showAddMenu by rememberSaveable { mutableStateOf(false) }

    val rows =
        remember(repo.index, plugins) { SourceLifecycleRows.installedRows(repo.index, plugins) }
    val updates =
        remember(repo.index, plugins) {
            repo.index?.let { PluginUpdatePlanner.updates(it.plugins, plugins) }.orEmpty()
        }

    suspend fun refresh() {
        plugins = withContext(Dispatchers.IO) { application.pluginManager.installed() }
    }

    fun runOperation(operation: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                operation()
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                feedback.show("操作失败: ${error.message ?: "未知错误"}", SourceFeedbackKind.ERROR)
                runCatching { refresh() }
            } finally {
                if (currentCoroutineContext().isActive) busy = false
            }
        }
    }

    fun report(outcome: PluginInstallResult, sourceName: String) {
        val name = outcome.pluginId ?: sourceName
        val version = outcome.version?.let { " v$it" } ?: ""
        when (outcome.status) {
            PluginInstallStatus.INSTALLED,
            PluginInstallStatus.ALREADY_INSTALLED ->
                feedback.show(
                    if (outcome.activatable) "已更新 $name$version"
                    else "已安装 $name$version，但未激活：与当前版本不兼容",
                    if (outcome.activatable) SourceFeedbackKind.INFO else SourceFeedbackKind.ERROR,
                    actionLabel = outcome.pluginId?.let { "打开" },
                ) {
                    outcome.pluginId?.let(onOpenSource)
                }
            PluginInstallStatus.REJECTED ->
                feedback.show(
                    "更新失败: ${outcome.errorMessage ?: outcome.errorCode?.name ?: "安装被拒绝"}",
                    SourceFeedbackKind.ERROR,
                )
        }
    }

    fun update(entry: PluginRepoEntry, sourceName: String) {
        runOperation {
            report(
                application.pluginManager.installUrl(entry.downloadSource(), activate = true),
                sourceName,
            )
        }
    }

    // Updates run one at a time: each install activates (and, on failure, rolls back) a plugin
    // runtime, so overlapping them would only add contention.
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

    LaunchedEffect(Unit) { runOperation {} }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SourceSnackbarHost(feedback.hostState) },
        topBar = {
            TopAppBar(
                title = { Text("漫画源管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showAddMenu = true }, enabled = !busy) {
                            Icon(Icons.Default.Add, contentDescription = "添加漫画源")
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false },
                        ) {
                            AddSourceItem("从本地文件导入", painterResource(R.drawable.ic_folder)) {
                                showAddMenu = false
                                onImportFromFile()
                            }
                            AddSourceItem("从 URL 导入", painterResource(R.drawable.ic_download)) {
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

            if (updates.isNotEmpty()) {
                item(key = "batch-update") {
                    UpdateBanner(count = updates.size, busy = busy, onUpdateAll = { updateAll() })
                }
            }

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
            } else {
                items(rows, key = { it.pluginId }) { row ->
                    SourceLifecycleRowItem(
                        row = row,
                        busy = busy,
                        onActivate = {
                            repo.index
                                ?.plugins
                                ?.firstOrNull { it.id == row.pluginId }
                                ?.let { update(it, row.name) }
                        },
                        onOpen = { onOpenSource(row.pluginId) },
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
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

/** Health badge shared by the source detail screen. */
@Composable
internal fun HealthStatusBadge(pluginState: PluginState, modifier: Modifier = Modifier) {
    val (label, containerColor, contentColor) =
        when {
            pluginState.health == PluginHealth.STORAGE_CORRUPT ->
                Triple(
                    "状态损坏",
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
            pluginState.disabled ->
                Triple(
                    "已禁用",
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            pluginState.health == PluginHealth.HEALTHY ->
                Triple(
                    "正常",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            else ->
                Triple(
                    "运行异常",
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
        }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(modifier = Modifier.size(6.dp).background(contentColor, shape = CircleShape))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
