package com.exio.inkleaf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginDownloadSource
import com.exio.inkleaf.plugin.PluginInstallResult
import com.exio.inkleaf.plugin.PluginRepoContract
import com.exio.inkleaf.plugin.PluginRepoEntry
import com.exio.inkleaf.plugin.PluginRepoIndex
import java.time.Duration

/**
 * Plugin repository section (Workbench): the index URL is an editable, prefilled field; the
 * plugin list is one outlined group of dense rows. The persisted cache is restored on entry and
 * silently refreshed once it passes the cache lifetime.
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
    var repoUrl by rememberSaveable { mutableStateOf(PluginRepoContract.DEFAULT_REPO_URL) }
    var repoIndex by remember { mutableStateOf<PluginRepoIndex?>(null) }
    var fetchedAtMs by remember { mutableStateOf<Long?>(null) }

    suspend fun persistAndShow(index: PluginRepoIndex) {
        repoIndex = index
        fetchedAtMs = application.pluginRepoRepository.cachedIndex()?.fetchedAtMs
    }

    fun loadIndex() {
        launchOperation {
            val fetched = application.pluginRepoRepository.fetchIndex(repoUrl.trim())
            persistAndShow(fetched)
            onFeedback("已从插件仓库读取 ${fetched.plugins.size} 个插件", false)
        }
    }

    // Restore the persisted index immediately; a stale cache (>4h) silently refreshes against
    // the URL it was fetched from, keeping the cached list visible if the refresh fails.
    LaunchedEffect(Unit) {
        val cached = application.pluginRepoRepository.cachedIndex() ?: return@LaunchedEffect
        repoIndex = cached.index
        fetchedAtMs = cached.fetchedAtMs
        if (application.pluginRepoRepository.isCacheExpired(cached)) {
            runCatching {
                application.pluginRepoRepository.fetchIndex(cached.sourceUrl ?: PluginRepoContract.DEFAULT_REPO_URL)
            }.onSuccess { refreshed -> persistAndShow(refreshed) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "插件仓库",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { loadIndex() }, enabled = !busy) {
                Text(if (repoIndex == null) "加载" else "重新加载")
            }
        }

        InkleafPrecisionField(
            value = repoUrl,
            onValueChange = { repoUrl = it },
            label = "仓库索引 URL",
            singleLine = true,
        )

        val index = repoIndex
        Text(
            text =
                when {
                    index == null -> "点击“加载”读取此索引中的漫画源插件。"
                    else -> {
                        val age =
                            fetchedAtMs?.let { " · 缓存于 ${relativeAge(it)}" } ?: ""
                        "${index.repoName ?: "插件仓库"} · ${index.plugins.size} 个插件$age"
                    }
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (index != null) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
            ) {
                index.plugins.forEachIndexed { position, entry ->
                    if (position > 0) HorizontalDivider()
                    RepoEntryRow(
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
}

/** One dense plugin row: identity and status left, the single install action right. */
@Composable
private fun RepoEntryRow(
    entry: PluginRepoEntry,
    installed: InstalledPlugin?,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val installedVersion = installed?.state?.activeVersion
    val statusText =
        when {
            installed == null -> "未安装"
            installedVersion == null -> "已安装（未激活）"
            installedVersion == entry.version -> "已安装"
            else -> "已安装 v$installedVersion"
        }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "v${entry.version} · $statusText",
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (installed == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
            }
            if (installed == null) {
                Button(
                    onClick = onInstall,
                    enabled = !busy,
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text("安装")
                }
            } else {
                OutlinedButton(
                    onClick = onUninstall,
                    enabled = !busy,
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text("卸载")
                }
            }
        }
        entry.description?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun relativeAge(thenMs: Long): String {
    val minutes = Duration.ofMillis(System.currentTimeMillis() - thenMs).toMinutes()
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        else -> "${minutes / (24 * 60)} 天前"
    }
}
