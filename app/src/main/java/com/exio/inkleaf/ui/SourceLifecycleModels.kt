package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginEntryState
import com.exio.inkleaf.plugin.PluginHealth
import com.exio.inkleaf.plugin.PluginRepoEntry
import com.exio.inkleaf.plugin.PluginRepoIndex
import com.exio.inkleaf.plugin.PluginUpdatePlanner
import com.exio.inkleaf.plugin.isUsable

/** The single action one source row offers. */
enum class SourceRowAction {
    /** Nothing installed for this id: the row installs it from the repository. */
    Install,

    /** Installed, and the repository advertises a strictly newer version. */
    Update,

    /** Installed: the row opens the source's detail screen. */
    Manage,
}

/** One row of a source list: a plugin id, its identity, its state in words, and its one action. */
data class SourceLifecycleRow(
    val pluginId: String,
    val name: String,
    val action: SourceRowAction,
    val statusText: String,
    val description: String?,
)

/**
 * Turns a repository index and the installed set into rows. Which of the two the list shows is a
 * decision per screen — the source list is about the device, the repository screen is about the
 * index — but a plugin id means the same thing in both, so the join and the wording live here.
 */
object SourceLifecycleRows {

    /** Sources on the device, annotated with what the cached index says about them. */
    fun installedRows(
        index: PluginRepoIndex?,
        installed: List<InstalledPlugin>,
    ): List<SourceLifecycleRow> {
        val entries = index?.plugins.orEmpty()
        val advertisedIds = entries.mapTo(hashSetOf()) { it.id }
        val localOnly = installed.filterNot { it.state.pluginId in advertisedIds }
        val fromIndex =
            entries.filter { entry -> installed.any { it.state.pluginId == entry.id } }.map { entry ->
                val plugin = installed.first { it.state.pluginId == entry.id }
                row(plugin, entry)
            }
        // Locally imported sources have no catalog position; they lead because they are what the
        // user already committed to.
        return localOnly.map { row(it, entry = null) } + fromIndex
    }

    /** Everything the index advertises, marked with local state. */
    fun catalogRows(
        index: PluginRepoIndex?,
        installed: List<InstalledPlugin>,
    ): List<SourceLifecycleRow> {
        val byId = installed.associateBy { it.state.pluginId }
        return index?.plugins.orEmpty().map { entry -> row(byId[entry.id], entry) }
    }

    /** Counts the sources browsing can actually offer, matching the discover screen's filter. */
    fun usableCount(installed: List<InstalledPlugin>): Int = installed.count { it.isUsable }

    private fun row(plugin: InstalledPlugin?, entry: PluginRepoEntry?): SourceLifecycleRow {
        val entryState = if (plugin != null && entry != null) PluginUpdatePlanner.state(entry, plugin) else null
        return SourceLifecycleRow(
            pluginId = plugin?.state?.pluginId ?: entry!!.id,
            name = plugin?.manifest?.name ?: entry?.name ?: plugin?.state?.pluginId.orEmpty(),
            action =
                when {
                    plugin == null -> SourceRowAction.Install
                    entryState is PluginEntryState.UpdateAvailable -> SourceRowAction.Update
                    else -> SourceRowAction.Manage
                },
            statusText = statusText(plugin, entryState, entry),
            description = plugin?.manifest?.description ?: entry?.description,
        )
    }

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
