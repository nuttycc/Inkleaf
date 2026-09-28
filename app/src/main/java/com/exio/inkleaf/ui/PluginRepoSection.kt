package com.exio.inkleaf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.outlined.R as MaterialSymbolsOutlinedR
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.R
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginDownloadSource
import com.exio.inkleaf.plugin.PluginInstallResult
import com.exio.inkleaf.plugin.PluginRepoContract
import com.exio.inkleaf.plugin.PluginRepoIndex
import com.exio.inkleaf.plugin.PluginRepoEntry

/**
 * Plugin repository section: loads the bundled repo index on demand and offers
 * per-entry install/uninstall against the locally installed plugin set.
 */
@Composable
internal fun PluginRepoSection(
    installed: List<InstalledPlugin>,
    busy: Boolean,
    launchOperation: (suspend () -> Unit) -> Unit,
    onFeedback: (message: String, isError: Boolean) -> Unit,
    onInstallResult: (PluginInstallResult) -> Unit,
) {
    val application = LocalContext.current.applicationContext as InkleafApplication
    var repoIndex by remember { mutableStateOf<PluginRepoIndex?>(null) }

    fun loadIndex() {
        launchOperation {
            val fetched = application.pluginRepoRepository.fetchIndex(PluginRepoContract.DEFAULT_REPO_URL)
            repoIndex = fetched
            onFeedback("已从插件仓库读取 ${fetched.plugins.size} 个插件", false)
        }
    }

    // Restore the persisted index immediately; a stale cache (>4h) silently refreshes in place,
    // keeping the cached list visible if the refresh fails.
    LaunchedEffect(Unit) {
        val cached = application.pluginRepoRepository.cachedIndex() ?: return@LaunchedEffect
        repoIndex = cached.index
        if (application.pluginRepoRepository.isCacheExpired(cached)) {
            runCatching {
                application.pluginRepoRepository.fetchIndex(PluginRepoContract.DEFAULT_REPO_URL)
            }
                .onSuccess { refreshed -> repoIndex = refreshed }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "插件仓库",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { loadIndex() }, enabled = !busy) {
                Text(if (repoIndex == null) "加载插件列表" else "重新加载")
            }
        }

        val index = repoIndex
        if (index == null) {
            RepoHintCard(
                text = "点击“加载插件列表”从内置仓库获取可安装的漫画源插件。",
            )
        } else {
            Text(
                text = index.repoName ?: "内置插件仓库",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            index.plugins.forEach { entry ->
                RepoEntryItem(
                    entry = entry,
                    installed = installed.firstOrNull { it.state.pluginId == entry.id },
                    busy = busy,
                    onInstall = {
                        launchOperation {
                            val result =
                                application.pluginManager.installUrl(
                                    PluginDownloadSource(
                                        url = entry.packageUrl,
                                        expectedSha256 = entry.sha256,
                                        expectedSizeBytes = entry.sizeBytes,
                                    ),
                                    activate = true,
                                )
                            onInstallResult(result)
                        }
                    },
                    onUninstall = {
                        launchOperation {
                            val removed = application.pluginManager.uninstall(entry.id)
                            onFeedback(
                                if (removed) "已卸载 ${entry.name}" else "卸载 ${entry.name} 失败",
                                !removed,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RepoHintCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Box(modifier = Modifier.padding(24.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RepoEntryItem(
    entry: PluginRepoEntry,
    installed: InstalledPlugin?,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        ListItem(
            supportingContent = {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "v${entry.version} · id: ${entry.id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    entry.description?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val installedVersion = installed?.state?.activeVersion
                    Text(
                        text =
                            when {
                                installed == null -> "未安装"
                                installedVersion == null -> "已安装（未激活）"
                                installedVersion == entry.version -> "已安装"
                                else -> "已安装 v$installedVersion"
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color =
                            if (installed == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    )
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
                if (installed == null) {
                    Button(onClick = onInstall, enabled = !busy) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("安装")
                    }
                } else {
                    OutlinedButton(onClick = onUninstall, enabled = !busy) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("卸载")
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
