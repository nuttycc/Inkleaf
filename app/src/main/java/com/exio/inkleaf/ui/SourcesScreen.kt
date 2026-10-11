package com.exio.inkleaf.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.outlined.R as MaterialSymbolsOutlinedR
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.R
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginDownloadSource
import com.exio.inkleaf.plugin.PluginHealth
import com.exio.inkleaf.plugin.PluginInstallResult
import com.exio.inkleaf.plugin.PluginInstallStatus
import com.exio.inkleaf.plugin.PluginRepoContract
import com.exio.inkleaf.plugin.PluginRepoEntry
import com.exio.inkleaf.plugin.PluginRepoIndex
import com.exio.inkleaf.plugin.PluginState
import com.exio.inkleaf.plugin.PluginUpdateCandidate
import com.exio.inkleaf.plugin.PluginUpdatePlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration

private enum class SourcesFeedbackKind {
    INFO,
    ERROR,
}

private data class SourcesSnackbarVisuals(
    override val message: String,
    val kind: SourcesFeedbackKind,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
) : SnackbarVisuals

/**
 * Secondary screen for comic source plugins: one list where every plugin id appears once, whether it
 * came from the repository index, from disk, or from both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as InkleafApplication
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var plugins by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var repoIndex by remember { mutableStateOf<PluginRepoIndex?>(null) }
    var repoFetchedAtMs by remember { mutableStateOf<Long?>(null) }
    var repoUrl by rememberSaveable { mutableStateOf(PluginRepoContract.DEFAULT_REPO_URL) }

    var busy by remember { mutableStateOf(false) }
    var showImportSheet by rememberSaveable { mutableStateOf(false) }
    var showRepoSheet by rememberSaveable { mutableStateOf(false) }
    var importUrl by rememberSaveable { mutableStateOf("") }
    var importError by rememberSaveable { mutableStateOf<String?>(null) }
    var repoError by rememberSaveable { mutableStateOf<String?>(null) }

    val rows = remember(repoIndex, plugins) { SourceLifecycleRows.build(repoIndex, plugins) }
    val updates =
        remember(repoIndex, plugins) {
            repoIndex?.let { PluginUpdatePlanner.updates(it.plugins, plugins) }.orEmpty()
        }

    suspend fun refresh() {
        plugins = withContext(Dispatchers.IO) { application.pluginManager.installed() }
    }

    fun showFeedback(
        message: String,
        kind: SourcesFeedbackKind,
        actionLabel: String? = null,
        onAction: () -> Unit = {},
    ) {
        scope.launch {
            val result =
                snackbarHostState.showSnackbar(
                    SourcesSnackbarVisuals(
                        message = message,
                        kind = kind,
                        actionLabel = actionLabel,
                        withDismissAction = actionLabel != null,
                    )
                )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    /**
     * A rejected install needs the answer where the user is. From the import sheet that is the field
     * itself, because a snackbar lands under the sheet and reads as "nothing happened".
     */
    fun handleInstallResult(result: PluginInstallResult, onRejected: (String) -> Unit) {
        val pluginName = result.pluginId ?: "未知插件"
        val versionText = result.version?.let { " v$it" } ?: ""
        when (result.status) {
            PluginInstallStatus.INSTALLED,
            PluginInstallStatus.ALREADY_INSTALLED -> {
                showImportSheet = false
                showRepoSheet = false
                val message =
                    if (result.activatable) {
                        "已安装 $pluginName$versionText"
                    } else {
                        "已安装 $pluginName$versionText，但未激活：与当前版本不兼容"
                    }
                showFeedback(
                    message,
                    if (result.activatable) SourcesFeedbackKind.INFO else SourcesFeedbackKind.ERROR,
                    actionLabel = result.pluginId?.let { "打开" },
                ) {
                    result.pluginId?.let(onOpenSource)
                }
            }
            PluginInstallStatus.REJECTED -> {
                onRejected(result.errorMessage ?: result.errorCode?.name ?: "安装被拒绝")
            }
        }
    }

    fun launchOperation(operation: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                operation()
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val message = error.message ?: "操作失败"
                snackbarHostState.showSnackbar(
                    SourcesSnackbarVisuals(
                        message = "操作失败: $message",
                        kind = SourcesFeedbackKind.ERROR,
                    )
                )
                try {
                    refresh()
                } catch (refreshError: CancellationException) {
                    throw refreshError
                } catch (_: Throwable) {}
            } finally {
                if (currentCoroutineContext().isActive) busy = false
            }
        }
    }

    fun loadRepo(url: String, onError: (String) -> Unit) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://")) {
            onError("插件仓库仅支持 HTTPS URL")
            return
        }
        launchOperation {
            try {
                val fetched = application.pluginRepoRepository.fetchIndex(trimmed)
                repoIndex = fetched
                repoUrl = trimmed
                repoFetchedAtMs = application.pluginRepoRepository.cachedIndex()?.fetchedAtMs
                repoError = null
                showFeedback("已从插件仓库读取 ${fetched.plugins.size} 个插件", SourcesFeedbackKind.INFO)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onError(error.message ?: "无法读取插件仓库")
            }
        }
    }

    // Updates run one at a time: each install activates (and, on failure, rolls back) a plugin
    // runtime, so overlapping them would only add contention.
    fun updateAll(candidates: List<PluginUpdateCandidate>) {
        launchOperation {
            var updated = 0
            var failed = 0
            for (candidate in candidates) {
                val result =
                    application.pluginManager.installUrl(
                        candidate.entry.downloadSource(),
                        activate = true,
                    )
                if (result.status == PluginInstallStatus.REJECTED) failed++ else updated++
            }
            showFeedback(
                if (failed == 0) "已更新 $updated 个漫画源"
                else "已更新 $updated 个漫画源，$failed 个失败",
                if (failed == 0) SourcesFeedbackKind.INFO else SourcesFeedbackKind.ERROR,
            )
        }
    }

    fun repoEntry(pluginId: String): PluginRepoEntry? =
        repoIndex?.plugins?.firstOrNull { it.id == pluginId }

    fun installFromRepo(entry: PluginRepoEntry) {
        launchOperation {
            handleInstallResult(
                application.pluginManager.installUrl(entry.downloadSource(), activate = true),
            ) { reason -> showFeedback("安装失败: $reason", SourcesFeedbackKind.ERROR) }
        }
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                importError = null
                launchOperation {
                    handleInstallResult(
                        application.pluginManager.installUri(uri, activate = true)
                    ) { reason -> importError = "安装失败: $reason" }
                }
            }
        }

    // The cached index carries the URL it was fetched from, so the repository the list is showing
    // and the URL that produced it cannot drift apart between visits.
    LaunchedEffect(Unit) {
        launchOperation {}
        val cached = application.pluginRepoRepository.cachedIndex() ?: return@LaunchedEffect
        repoIndex = cached.index
        repoFetchedAtMs = cached.fetchedAtMs
        repoUrl = cached.sourceUrl ?: repoUrl
        if (application.pluginRepoRepository.isCacheExpired(cached)) {
            runCatching {
                application.pluginRepoRepository.fetchIndex(
                    cached.sourceUrl ?: PluginRepoContract.DEFAULT_REPO_URL
                )
            }.onSuccess { refreshed ->
                repoIndex = refreshed
                repoFetchedAtMs = application.pluginRepoRepository.cachedIndex()?.fetchedAtMs
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                val isError =
                    (data.visuals as? SourcesSnackbarVisuals)?.kind == SourcesFeedbackKind.ERROR
                if (isError) {
                    Snackbar(
                        snackbarData = data,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        actionColor = MaterialTheme.colorScheme.error,
                        dismissActionContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    Snackbar(
                        snackbarData = data,
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        actionColor = MaterialTheme.colorScheme.inversePrimary,
                    )
                }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("漫画源管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            importError = null
                            showImportSheet = true
                        },
                        enabled = !busy,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "导入漫画源")
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
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }

            if (updates.isNotEmpty()) {
                item(key = "batch-update") {
                    UpdateBanner(count = updates.size, busy = busy, onUpdateAll = {
                        updateAll(updates)
                    })
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
                                text = "还没有漫画源。",
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
                        onActivate = { repoEntry(row.pluginId)?.let { installFromRepo(it) } },
                        onOpen = { onOpenSource(row.pluginId) },
                    )
                }
            }

            item(key = "repo-caption") {
                SourceRepoCaption(
                    index = repoIndex,
                    repoUrl = repoUrl,
                    fetchedAtMs = repoFetchedAtMs,
                    onClick = {
                        repoError = null
                        showRepoSheet = true
                    },
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showImportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImportSheet = false },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            ImportSourceSheetContent(
                url = importUrl,
                busy = busy,
                error = importError,
                onUrlChange = {
                    importUrl = it
                    importError = null
                },
                onPickFile = {
                    picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                onInstallUrl = {
                    importError = null
                    launchOperation {
                        handleInstallResult(
                            application.pluginManager.installUrl(
                                PluginDownloadSource(importUrl),
                                activate = true,
                            )
                        ) { reason -> importError = reason }
                    }
                },
            )
        }
    }

    if (showRepoSheet) {
        ModalBottomSheet(
            onDismissRequest = { showRepoSheet = false },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            SourceRepoSheetContent(
                url = repoUrl,
                busy = busy,
                error = repoError,
                loadedIndex = repoIndex,
                onUrlChange = {
                    repoUrl = it
                    repoError = null
                },
                onLoad = { loadRepo(it) { reason -> repoError = reason } },
                onUseDefault = {
                    repoUrl = PluginRepoContract.DEFAULT_REPO_URL
                    loadRepo(PluginRepoContract.DEFAULT_REPO_URL) { reason -> repoError = reason }
                },
            )
        }
    }
}

/** The repository is a setting, so it lives in a caption row instead of on the resting screen. */
@Composable
private fun SourceRepoCaption(
    index: PluginRepoIndex?,
    repoUrl: String,
    fetchedAtMs: Long?,
    onClick: () -> Unit,
) {
    val summary =
        when {
            index == null -> "未加载仓库目录，点按设置仓库源"
            else -> {
                val age = fetchedAtMs?.let { " · 缓存于 ${relativeAge(it)}" } ?: ""
                "${index.repoName ?: "插件仓库"} · ${index.plugins.size} 个插件$age"
            }
        }
    InkleafActionListItem(
        headline = "插件仓库",
        supporting = summary,
        onClick = onClick,
        modifier = Modifier.padding(top = 8.dp),
        leadingContent = { RepoIcon() },
        trailingContent = { ForwardIcon() },
    )
}

/** One row of the merged list: identity and state, plus the single action that state allows. */
@Composable
private fun SourceLifecycleRowItem(
    row: SourceLifecycleRow,
    busy: Boolean,
    onActivate: () -> Unit,
    onOpen: () -> Unit,
) {
    val actionLabel =
        when (row.action) {
            SourceRowAction.Install -> "安装"
            SourceRowAction.Update -> "更新"
            SourceRowAction.Manage -> null
        }
    val supporting =
        row.description
            ?.takeIf { row.action == SourceRowAction.Install }
            ?.let { "${row.statusText} · $it" }
            ?: row.statusText

    InkleafActionListItem(
        headline = row.name,
        supporting = supporting,
        onClick = if (row.action == SourceRowAction.Manage) onOpen else onActivate,
        leadingContent = { SourceIcon() },
        trailingContent = {
            if (actionLabel == null) {
                ForwardIcon()
            } else {
                TextButton(onClick = onActivate, enabled = !busy) { Text(actionLabel) }
            }
        },
    )
}

/** The screen's only filled button, and only while the repository really offers something newer. */
@Composable
private fun UpdateBanner(count: Int, busy: Boolean, onUpdateAll: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "发现 $count 个可更新漫画源",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onUpdateAll, enabled = !busy) { Text("全部更新") }
        }
    }
}

@Composable
private fun SourceIcon() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter =
                    painterResource(
                        MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_extension_outlined
                    ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun RepoIcon() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun ImportSourceSheetContent(
    url: String,
    busy: Boolean,
    error: String?,
    onUrlChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onInstallUrl: () -> Unit,
) {
    SheetColumn(
        modifier = Modifier.imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
        scrollable = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StandardSheetTitle("导入漫画源")

            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "本地文件", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "选择 .zip 插件包，安装后自动激活。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onPickFile,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("选择插件包")
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "网络 URL", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text("插件包 URL") },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_file),
                            contentDescription = null,
                        )
                    },
                    trailingIcon =
                        if (url.isNotEmpty()) {
                            {
                                IconButton(onClick = { onUrlChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "清除")
                                }
                            }
                        } else null,
                    supportingText = {
                        Text(
                            text = error ?: "支持 HTTP 和 HTTPS 下载链接",
                            color =
                                if (error != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    isError = error != null,
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onInstallUrl,
                    enabled = !busy && url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("下载并安装")
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
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
            pluginState.health == PluginHealth.RUNTIME_UNHEALTHY ->
                Triple(
                    "运行异常",
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
            else ->
                Triple(
                    pluginState.health.name,
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
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

private fun PluginRepoEntry.downloadSource(): PluginDownloadSource =
    PluginDownloadSource(
        url = packageUrl,
        expectedSha256 = sha256,
        expectedSizeBytes = sizeBytes,
    )

private fun relativeAge(thenMs: Long): String {
    val minutes = Duration.ofMillis(System.currentTimeMillis() - thenMs).toMinutes()
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        else -> "${minutes / (24 * 60)} 天前"
    }
}
