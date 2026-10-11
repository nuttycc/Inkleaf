package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginEntryState
import com.exio.inkleaf.plugin.PluginHealth
import com.exio.inkleaf.plugin.PluginRepoEntry
import com.exio.inkleaf.plugin.PluginRepoIndex
import com.exio.inkleaf.plugin.PluginUpdatePlanner

/** The single action one merged source row offers. */
enum class SourceRowAction {
    /** Nothing installed for this id: the row itself performs the install. */
    Install,

    /** Installed, and the repository advertises a strictly newer version. */
    Update,

    /** Installed: the row opens the source's detail screen. */
    Manage,
}

/** One row of the source lifecycle list: a plugin id, its identity, and its one action. */
data class SourceLifecycleRow(
    val pluginId: String,
    val name: String,
    val action: SourceRowAction,
    val statusText: String,
    val description: String?,
)

/**
 * The repository index and the installed set are two views of the same entity, so the list shows
 * one row per plugin id instead of one section per view. Repository order is kept because the
 * index author's ordering is the only ordering signal the catalog has; plugins installed from a
 * file or an arbitrary URL have no catalog position and are appended as 本地导入.
 */
object SourceLifecycleRows {

    fun build(index: PluginRepoIndex?, installed: List<InstalledPlugin>): List<SourceLifecycleRow> {
        val byId = installed.associateBy { it.state.pluginId }
        val entries = index?.plugins.orEmpty()
        val catalogRows =
            entries.map { entry ->
                val plugin = byId[entry.id]
                val entryState = PluginUpdatePlanner.state(entry, plugin)
                SourceLifecycleRow(
                    pluginId = entry.id,
                    name = displayName(plugin, entry.name),
                    action =
                        when (entryState) {
                            PluginEntryState.NotInstalled -> SourceRowAction.Install
                            is PluginEntryState.UpdateAvailable -> SourceRowAction.Update
                            else -> SourceRowAction.Manage
                        },
                    statusText = statusText(plugin, entryState, entry),
                    description = plugin?.manifest?.description ?: entry.description,
                )
            }
        val advertisedIds = entries.mapTo(hashSetOf()) { it.id }
        val localRows =
            installed.filterNot { it.state.pluginId in advertisedIds }.map { plugin ->
                SourceLifecycleRow(
                    pluginId = plugin.state.pluginId,
                    name = displayName(plugin, plugin.state.pluginId),
                    action = SourceRowAction.Manage,
                    statusText = statusText(plugin, entryState = null, entry = null),
                    description = plugin.manifest?.description,
                )
            }
        return catalogRows + localRows
    }

    /** Counts the sources browsing can actually offer, matching the discover screen's filter. */
    fun usableCount(installed: List<InstalledPlugin>): Int =
        installed.count {
            it.state.activeVersion != null &&
                !it.state.disabled &&
                it.state.health == PluginHealth.HEALTHY
        }

    private fun displayName(plugin: InstalledPlugin?, fallback: String): String =
        plugin?.manifest?.name ?: fallback

    private fun statusText(
        plugin: InstalledPlugin?,
        entryState: PluginEntryState?,
        entry: PluginRepoEntry?,
    ): String {
        if (plugin == null) {
            return entry?.let { "未安装 · v${it.version}" } ?: "未安装"
        }
        val state = plugin.state
        val lifecycle =
            when {
                entryState is PluginEntryState.UpdateAvailable ->
                    "有更新 · 当前 v${entryState.installedVersion}，仓库 v${entryState.repositoryVersion}"
                state.activeVersion == null ->
                    entry?.let { "已安装，未激活 · 仓库 v${it.version}" } ?: "已安装，未激活"
                entry == null -> "本地导入 · v${state.activeVersion}"
                else -> "已安装 · v${state.activeVersion}"
            }
        // Health outranks 已停用: a plugin that broke and was disabled must not read as a
        // choice the user made.
        val condition =
            when (state.health) {
                PluginHealth.STORAGE_CORRUPT -> "状态损坏"
                PluginHealth.RUNTIME_UNHEALTHY -> "运行异常"
                PluginHealth.HEALTHY -> if (state.disabled) "已停用" else null
            }
        return if (condition == null) lifecycle else "$lifecycle · $condition"
    }
}
