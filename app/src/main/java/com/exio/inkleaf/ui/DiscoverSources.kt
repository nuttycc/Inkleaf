package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginCapabilities
import com.exio.inkleaf.plugin.isUsable

/**
 * Pure derivations over the installed source set that the discovery surface needs.
 *
 * Feed loading, searching and the source chips each want a different slice of the same list;
 * keeping the filters here stops those slices from drifting apart.
 */
internal object DiscoverSources {
    /** Sources healthy enough to run anything, in install order. */
    fun usable(plugins: List<InstalledPlugin>): List<InstalledPlugin> = plugins.filter { it.isUsable }

    /** Sources that are both healthy and declare browse capability — what feeds can be loaded from. */
    fun browsable(plugins: List<InstalledPlugin>): List<InstalledPlugin> =
        usable(plugins).filter { PluginCapabilities.BROWSE in it.manifest?.capabilities.orEmpty() }

    /** Changes only when the browsable source set or its active versions change. */
    fun browseSignature(plugins: List<InstalledPlugin>): List<String> =
        browsable(plugins).map { "${it.state.pluginId}:${it.state.activeVersion}" }.sorted()

    /** Narrows a prior selection to the ids still available. */
    fun retainSelection(current: Set<String>, available: Set<String>): Set<String> =
        current.intersect(available)

    /** Sources a search may query: the selection (or all) narrowed to the usable ids. */
    fun searchTargets(selected: Set<String>?, usableIds: List<String>): List<String> {
        val available = usableIds.toSet()
        return (selected ?: available).filter { it in available }
    }
}
