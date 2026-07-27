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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
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
import com.exio.inkleaf.plugin.PluginState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Secondary screen for plugin source installation and lifecycle management. */
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
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showImportSheet by rememberSaveable { mutableStateOf(false) }

    suspend fun refresh() {
        plugins = withContext(Dispatchers.IO) { application.pluginManager.installed() }
    }

    fun handleInstallResult(result: PluginInstallResult) {
        val pluginName = result.pluginId ?: "未知插件"
        val versionText = result.version?.let { "@$it" } ?: ""
        when (result.status) {
            PluginInstallStatus.INSTALLED,
            PluginInstallStatus.ALREADY_INSTALLED -> {
                showImportSheet = false
                val message =
                    if (result.activatable) {
                        "已成功安装并激活 $pluginName$versionText"
                    } else {
                        "已安装 $pluginName$versionText，但当前版本不兼容，未激活"
                    }
                scope.launch {
                    snackbarHostState.showSnackbar(message)
                }
            }
            PluginInstallStatus.REJECTED -> {
                val err = result.errorMessage ?: result.errorCode?.name ?: "安装被拒绝"
                scope.launch {
                    snackbarHostState.showSnackbar("安装失败: $err")
                }
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
                snackbarHostState.showSnackbar("操作失败: $message")
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

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                launchOperation {
                    val result = application.pluginManager.installUri(uri, activate = true)
                    handleInstallResult(result)
                }
            }
        }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        modifier = modifier,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                val isError =
                    data.visuals.message.startsWith("安装失败") ||
                        data.visuals.message.startsWith("操作失败") ||
                        data.visuals.message.contains("失败") ||
                        data.visuals.message.contains("拒绝") ||
                        data.visuals.message.contains("错误")
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
                        onClick = { showImportSheet = true },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (busy) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }

            item {
                Text(
                    text = "已安装来源 (${plugins.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (plugins.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        Box(
                            modifier = Modifier.padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "还没有启用的漫画源。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(plugins, key = { it.state.pluginId }) { plugin ->
                    SourceManagementItem(
                        plugin = plugin,
                        busy = busy,
                        onToggle = {
                            launchOperation {
                                application.pluginManager.setEnabled(
                                    plugin.state.pluginId,
                                    plugin.state.disabled,
                                )
                            }
                        },
                        onOpen = { onOpenSource(plugin.state.pluginId) },
                    )
                }
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
                url = url,
                busy = busy,
                onUrlChange = { url = it },
                onPickFile = {
                    picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                onInstallUrl = {
                    launchOperation {
                        val result =
                            application.pluginManager.installUrl(
                                PluginDownloadSource(url),
                                activate = true,
                            )
                        handleInstallResult(result)
                    }
                },
            )
        }
    }
}

@Composable
private fun ImportSourceSheetContent(
    url: String,
    busy: Boolean,
    onUrlChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onInstallUrl: () -> Unit,
) {
    SheetColumn(
        modifier = Modifier.imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
        scrollable = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "导入漫画源",
                style = MaterialTheme.typography.titleLarge,
            )

            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "本地文件",
                    style = MaterialTheme.typography.titleSmall,
                )
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
                Text(
                    text = "网络 URL",
                    style = MaterialTheme.typography.titleSmall,
                )
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
                    trailingIcon = {
                        if (url.isNotEmpty()) {
                            IconButton(onClick = { onUrlChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除")
                            }
                        }
                    },
                    supportingText = { Text("支持 HTTP 和 HTTPS 下载链接") },
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

/** Health badge shared by the source list and source detail screen. */
@Composable
internal fun HealthStatusBadge(
    pluginState: PluginState,
    modifier: Modifier = Modifier,
) {
    val (label, containerColor, contentColor) =
        when {
            pluginState.health == PluginHealth.STORAGE_CORRUPT ->
                Triple(
                    "BROKEN (状态损坏)",
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
                    "HEALTHY",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            pluginState.health == PluginHealth.RUNTIME_UNHEALTHY ->
                Triple(
                    "BROKEN (运行异常)",
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
            pluginState.health.name.contains("DEGRADED", ignoreCase = true) ->
                Triple(
                    "DEGRADED (降级)",
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                )
            pluginState.health.name.contains("BROKEN", ignoreCase = true) ->
                Triple(
                    "BROKEN",
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

/** A list row shows identity, health, and enabled state; details belong on the nested screen. */
@Composable
private fun SourceManagementItem(
    plugin: InstalledPlugin,
    busy: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val displayName = plugin.manifest?.name ?: plugin.state.pluginId

    ElevatedCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        ListItem(
            supportingContent = {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text =
                            "id: ${plugin.state.pluginId} · v${plugin.state.activeVersion ?: "未启用"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HealthStatusBadge(pluginState = plugin.state)
                }
            },
            leadingContent = {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter =
                                painterResource(
                                    MaterialSymbolsOutlinedR.drawable
                                        .materialsymbols_ic_extension_outlined
                                ),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            },
            trailingContent = {
                Switch(
                    checked = !plugin.state.disabled && plugin.state.activeVersion != null,
                    onCheckedChange = { onToggle() },
                    enabled = !busy && plugin.state.activeVersion != null,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
