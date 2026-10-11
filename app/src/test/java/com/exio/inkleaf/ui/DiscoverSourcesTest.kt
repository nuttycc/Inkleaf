package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginCapabilities
import com.exio.inkleaf.plugin.PluginHealth
import com.exio.inkleaf.plugin.PluginManifest
import com.exio.inkleaf.plugin.PluginState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverSourcesTest {
    @Test
    fun `usable keeps only healthy active sources`() {
        val plugins =
            listOf(
                plugin("a", capabilities = listOf(PluginCapabilities.BROWSE)),
                plugin("b", capabilities = listOf(PluginCapabilities.BROWSE), disabled = true),
                plugin("c", capabilities = listOf(PluginCapabilities.BROWSE), health = PluginHealth.RUNTIME_UNHEALTHY),
                plugin("d", activeVersion = null, capabilities = listOf(PluginCapabilities.BROWSE)),
            )

        assertEquals(listOf("a"), DiscoverSources.usable(plugins).map { it.state.pluginId })
    }

    @Test
    fun `browsable keeps usable sources that declare browse`() {
        val plugins =
            listOf(
                plugin("a", capabilities = listOf(PluginCapabilities.BROWSE)),
                plugin("b", capabilities = listOf(PluginCapabilities.SEARCH)),
                plugin("c", capabilities = emptyList()),
            )

        assertEquals(listOf("a"), DiscoverSources.browsable(plugins).map { it.state.pluginId })
    }

    @Test
    fun `browse signature is sorted and carries the active version`() {
        val plugins =
            listOf(
                plugin("z", version = "2", capabilities = listOf(PluginCapabilities.BROWSE)),
                plugin("a", version = "1", capabilities = listOf(PluginCapabilities.BROWSE)),
                plugin("s", version = "1", capabilities = listOf(PluginCapabilities.SEARCH)),
            )

        assertEquals(listOf("a:1", "z:2"), DiscoverSources.browseSignature(plugins))
    }

    @Test
    fun `retain selection narrows to the available ids`() {
        assertEquals(setOf("a"), DiscoverSources.retainSelection(setOf("a", "b"), setOf("a", "c")))
    }

    @Test
    fun `search targets default to every usable id when nothing is selected`() {
        assertEquals(
            listOf("a", "b"),
            DiscoverSources.searchTargets(selected = null, usableIds = listOf("a", "b")),
        )
    }

    @Test
    fun `search targets narrow an explicit selection to the usable ids`() {
        assertEquals(
            listOf("b"),
            DiscoverSources.searchTargets(selected = setOf("a", "b"), usableIds = listOf("b", "c")),
        )
    }

    private fun plugin(
        id: String,
        version: String = "1",
        activeVersion: String? = version,
        disabled: Boolean = false,
        health: PluginHealth = PluginHealth.HEALTHY,
        capabilities: List<String>,
    ) =
        InstalledPlugin(
            state =
                PluginState(
                    pluginId = id,
                    activeVersion = activeVersion,
                    disabled = disabled,
                    health = health,
                ),
            directory = File(id),
            activeDirectory = activeVersion?.let { File("$id/$it") },
            manifest =
                PluginManifest(
                    manifestVersion = 1,
                    id = id,
                    name = id,
                    version = version,
                    apiVersion = "1",
                    capabilities = capabilities,
                ),
        )
}
