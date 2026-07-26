package com.exio.inkleaf.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginDownloadSource
import com.exio.inkleaf.plugin.PluginInstallResult
import com.exio.inkleaf.plugin.PluginInstallStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Secondary screen for plugin source installation and lifecycle management. */
@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as InkleafApplication
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var plugins by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        plugins = withContext(Dispatchers.IO) { application.pluginManager.installed() }
    }

    fun handleInstallResult(result: PluginInstallResult) {
        val pluginName = result.pluginId ?: "未知插件"
        val versionText = result.version?.let { "@$it" } ?: ""
        when (result.status) {
            PluginInstallStatus.INSTALLED, PluginInstallStatus.ALREADY_INSTALLED -> {
                val message = if (result.activatable) {
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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("漫画源管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        enabled = !busy,
                    ) {
                        Text("导入插件包")
                    }
                    if (busy) CircularProgressIndicator()
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("插件 URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            launchOperation {
                                val result = application.pluginManager.installUrl(PluginDownloadSource(url), activate = true)
                                handleInstallResult(result)
                            }
                        },
                        enabled = !busy && url.isNotBlank(),
                    ) {
                        Text("从 URL 安装")
                    }
                }
            }

            item { HorizontalDivider() }

            item {
                Text(
                    "已安装来源",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (plugins.isEmpty()) {
                item {
                    Text(
                        "还没有启用的漫画源。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(plugins, key = { it.state.pluginId }) { plugin ->
                    SourceManagementItem(
                        plugin = plugin,
                        busy = busy,
                        onToggle = {
                            launchOperation {
                                application.pluginManager.setEnabled(plugin.state.pluginId, plugin.state.disabled)
                            }
                        },
                        onActivate = { version ->
                            launchOperation {
                                application.pluginManager.activate(plugin.state.pluginId, version)
                            }
                        },
                        onRollback = {
                            launchOperation { application.pluginManager.rollback(plugin.state.pluginId) }
                        },
                        onUninstall = {
                            launchOperation { application.pluginManager.uninstall(plugin.state.pluginId) }
                        },
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SourceManagementItem(
    plugin: InstalledPlugin,
    busy: Boolean,
    onToggle: () -> Unit,
    onActivate: (String) -> Unit,
    onRollback: () -> Unit,
    onUninstall: () -> Unit,
) {
    var showUninstallConfirm by remember { mutableStateOf(false) }
    val displayName = plugin.manifest?.name ?: plugin.state.pluginId

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "id: ${plugin.state.pluginId} · v${plugin.state.activeVersion ?: "未启用"} · ${plugin.state.health}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = !plugin.state.disabled && plugin.state.activeVersion != null,
                onCheckedChange = { onToggle() },
                enabled = !busy && plugin.state.activeVersion != null,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plugin.state.previousVersion != null) {
                OutlinedButton(onClick = onRollback, enabled = !busy) {
                    Text("回滚")
                }
            }
            OutlinedButton(
                onClick = { showUninstallConfirm = true },
                enabled = !busy,
            ) {
                Text("卸载")
            }
        }

        plugin.state.versions
            .filter { it.compatible && it.version != plugin.state.activeVersion }
            .forEach { versionRecord ->
                TextButton(
                    onClick = { onActivate(versionRecord.version) },
                    enabled = !busy,
                ) {
                    Text("启用版本 ${versionRecord.version}")
                }
            }
    }

    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text("卸载漫画源") },
            text = { Text("卸载将移除插件文件、插件配置、Cookie 和日志。阅读历史、书签及已保存记录会保留，但该来源将暂时不可用。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUninstallConfirm = false
                        onUninstall()
                    },
                ) {
                    Text("卸载", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }
}
