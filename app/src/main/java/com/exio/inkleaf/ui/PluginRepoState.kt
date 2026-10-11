package com.exio.inkleaf.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.plugin.PluginDownloadSource
import com.exio.inkleaf.plugin.PluginRepoContract
import com.exio.inkleaf.plugin.PluginRepoEntry
import com.exio.inkleaf.plugin.PluginRepoIndex
import com.exio.inkleaf.plugin.PluginRepoRepository
import java.time.Duration

/**
 * The repository view shared by the source list and the repository screen. The cached index records
 * the URL it was fetched from, so the list on screen and the address that produced it cannot drift
 * apart between visits.
 */
@Stable
class PluginRepoState(private val repository: PluginRepoRepository) {
    var index by mutableStateOf<PluginRepoIndex?>(null)
        private set
    var url by mutableStateOf(PluginRepoContract.DEFAULT_REPO_URL)
        private set
    var fetchedAtMs by mutableStateOf<Long?>(null)
        private set

    /** Shows the last good index immediately, then re-fetches quietly once it passes its lifetime. */
    suspend fun restore() {
        val cached = repository.cachedIndex() ?: return
        apply(cached.index, cached.sourceUrl ?: url, cached.fetchedAtMs)
        if (repository.isCacheExpired(cached)) {
            runCatching {
                repository.fetchIndex(cached.sourceUrl ?: PluginRepoContract.DEFAULT_REPO_URL)
            }.onSuccess { refreshed -> apply(refreshed, url, repository.cachedIndex()?.fetchedAtMs) }
        }
    }

    /** A failed fetch leaves the previous index on screen; the caller reports the error. */
    suspend fun load(newUrl: String) {
        val fetched = repository.fetchIndex(newUrl.trim())
        apply(fetched, newUrl.trim(), repository.cachedIndex()?.fetchedAtMs)
    }

    private fun apply(index: PluginRepoIndex, sourceUrl: String, atMs: Long?) {
        this.index = index
        url = sourceUrl
        fetchedAtMs = atMs
    }
}

@Composable
internal fun rememberPluginRepoState(): PluginRepoState {
    val application = LocalContext.current.applicationContext as InkleafApplication
    val state = remember(application.pluginRepoRepository) { PluginRepoState(application.pluginRepoRepository) }
    LaunchedEffect(state) { state.restore() }
    return state
}

internal fun relativeAge(thenMs: Long): String {    val minutes = Duration.ofMillis(System.currentTimeMillis() - thenMs).toMinutes()
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        else -> "${minutes / (24 * 60)} 天前"
    }
}

/** One line describing which repository is on screen and how current it is. */
internal fun repoSummary(repo: PluginRepoState): String {
    val index = repo.index ?: return "仓库目录尚未加载"
    val age = repo.fetchedAtMs?.let { " · 缓存于 ${relativeAge(it)}" } ?: ""
    return "${index.repoName ?: "插件仓库"} · ${index.plugins.size} 个插件$age"
}

/** A repository entry is a download source with the integrity claims the index published. */
internal fun PluginRepoEntry.downloadSource(): PluginDownloadSource =
    PluginDownloadSource(
        url = packageUrl,
        expectedSha256 = sha256,
        expectedSizeBytes = sizeBytes,
    )
