package com.exio.inkleaf.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.plugin.ComicSummary
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginDownloadSource
import com.exio.inkleaf.plugin.PluginSearchRequest
import com.exio.inkleaf.plugin.PluginSearchResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Native-rendered discovery and plugin management surface for the v1 bridge. */
@Composable
fun PluginDiscoverScreen(
    onOpenComic: (String, ComicSummary) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as InkleafApplication
    val scope = rememberCoroutineScope()
    var plugins by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var url by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selectedPluginId by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<PluginSearchResult>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val installed = withContext(Dispatchers.IO) { application.pluginManager.installed() }
        plugins = installed
        selectedPluginId = selectedPluginId?.takeIf { id -> plugins.any { it.state.pluginId == id } }
            ?: plugins.firstOrNull()?.state?.pluginId
    }

    fun launchOperation(operation: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            message = null
            try {
                operation()
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message ?: "插件操作失败"
                try {
                    refresh()
                } catch (refreshError: CancellationException) {
                    throw refreshError
                } catch (_: Throwable) {
                    // Keep the original operation error visible when storage refresh fails.
                }
            } finally {
                if (currentCoroutineContext().isActive) busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            launchOperation {
                val result = application.pluginManager.installUri(uri, activate = false)
                message = "${result.status}: ${result.pluginId ?: "未知插件"}@${result.version ?: "未知版本"}"
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("发现") }, navigationIcon = {}) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "漫画源由插件提供，界面和数据仍由 Inkleaf 原生渲染。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !busy) {
                    Text("导入插件包")
                }
                if (busy) CircularProgressIndicator()
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("插件 URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Button(
                onClick = {
                    launchOperation {
                        val result = application.pluginManager.installUrl(PluginDownloadSource(url), activate = false)
                        message = "${result.status}: ${result.pluginId ?: "未知插件"}@${result.version ?: "未知版本"}"
                    }
                },
                enabled = !busy && url.isNotBlank(),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text("从 URL 安装") }

            message?.let { value ->
                Text(
                    value,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            HorizontalDivider()
            Text("已安装来源", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            if (plugins.isEmpty()) {
                Text("还没有启用的漫画源。", modifier = Modifier.padding(horizontal = 16.dp))
            }
            plugins.forEach { plugin ->
                PluginManagementItem(
                    plugin = plugin,
                    selected = selectedPluginId == plugin.state.pluginId,
                    onSelect = { selectedPluginId = plugin.state.pluginId },
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

            HorizontalDivider()
            Text("全局搜索", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索漫画") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Button(
                onClick = {
                    launchOperation {
                        results = application.pluginCatalog.search(PluginSearchRequest(query = query))
                    }
                },
                enabled = !busy && query.isNotBlank() && plugins.any { !it.state.disabled && it.state.activeVersion != null },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text("搜索") }
            results.forEach { result ->
                val error = result.error
                if (error != null) {
                    Text(
                        "${result.pluginId}: ${error.code} · ${error.message}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    Text(
                        result.pluginId,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    result.page?.items.orEmpty().forEach { comic ->
                        TextButton(
                            onClick = { onOpenComic(result.pluginId, comic) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        ) {
                            Text(comic.title, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PluginManagementItem(
    plugin: InstalledPlugin,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onActivate: (String) -> Unit,
    onRollback: () -> Unit,
    onUninstall: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        TextButton(onClick = onSelect) {
            Text("${plugin.state.pluginId} ${if (selected) "· 已选中" else ""}")
        }
        Text(
            "版本 ${plugin.state.activeVersion ?: "未启用"} · ${plugin.state.health} " +
                if (plugin.state.disabled) "· 已禁用" else "· 已启用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onToggle,
                enabled = plugin.state.activeVersion != null,
            ) { Text(if (plugin.state.disabled) "启用" else "禁用") }
            if (plugin.state.previousVersion != null) OutlinedButton(onClick = onRollback) { Text("回滚") }
            OutlinedButton(onClick = onUninstall) { Text("卸载") }
        }
        plugin.state.versions
            .filter { it.compatible && it.version != plugin.state.activeVersion }
            .forEach { version ->
                TextButton(onClick = { onActivate(version.version) }) {
                    Text("启用版本 ${version.version}")
                }
            }
    }
}
