package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginCapabilities
import com.exio.inkleaf.plugin.PluginHealth
import com.exio.inkleaf.plugin.PluginManifest
import com.exio.inkleaf.plugin.PluginState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DiscoverSourcesTest {
    /** Checks that discovery excludes disabled, unhealthy and inactive sources from its usable list. */
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

    /** Requires browse capability even when a source is otherwise usable. */
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

    /**
     * Checks that the browse signature is sorted, includes versions and excludes search-only
     * sources.
     */
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

    /** Drops removed source IDs without selecting newly available sources implicitly. */
    @Test
    fun `search signature is sorted and includes every usable active version`() {
        val plugins =
            listOf(
                plugin("z", capabilities = listOf(PluginCapabilities.BROWSE)),
                plugin("a", activeVersion = "2", capabilities = listOf(PluginCapabilities.SEARCH)),
                plugin("c", capabilities = emptyList()),
                plugin(
                    "disabled",
                    disabled = true,
                    capabilities = listOf(PluginCapabilities.SEARCH),
                ),
                plugin(
                    "unhealthy",
                    health = PluginHealth.RUNTIME_UNHEALTHY,
                    capabilities = listOf(PluginCapabilities.SEARCH),
                ),
                plugin(
                    "inactive",
                    activeVersion = null,
                    capabilities = listOf(PluginCapabilities.SEARCH),
                ),
            )

        assertEquals(listOf("a:2", "c:1", "z:1"), DiscoverSources.searchSignature(plugins))
        assertEquals(
            DiscoverSources.searchSignature(plugins),
            DiscoverSources.searchSignature(plugins.reversed()),
        )
    }

    @Test
    fun `installing a search-only source changes only the search signature`() {
        val before = listOf(plugin("browse", capabilities = listOf(PluginCapabilities.BROWSE)))
        val after = before + plugin("search", capabilities = listOf(PluginCapabilities.SEARCH))

        assertNotEquals(
            DiscoverSources.searchSignature(before),
            DiscoverSources.searchSignature(after),
        )
        assertEquals(
            DiscoverSources.browseSignature(before),
            DiscoverSources.browseSignature(after),
        )
    }

    @Test
    fun `updating a search-only source changes only the search signature`() {
        val browse = plugin("browse", capabilities = listOf(PluginCapabilities.BROWSE))
        val search = plugin("search", capabilities = listOf(PluginCapabilities.SEARCH))
        val before = listOf(browse, search)
        val after = listOf(browse, search.copy(state = search.state.copy(activeVersion = "2")))

        assertNotEquals(
            DiscoverSources.searchSignature(before),
            DiscoverSources.searchSignature(after),
        )
        assertEquals(
            DiscoverSources.browseSignature(before),
            DiscoverSources.browseSignature(after),
        )
    }

    @Test
    fun `retain selection narrows to the available ids`() {
        assertEquals(setOf("a"), DiscoverSources.retainSelection(setOf("a", "b"), setOf("a", "c")))
    }

    /** Treats a null selection as all usable sources while preserving their order. */
    @Test
    fun `search targets default to every usable id when nothing is selected`() {
        assertEquals(
            listOf("a", "b"),
            DiscoverSources.searchTargets(selected = null, usableIds = listOf("a", "b")),
        )
    }

    /** Intersects an explicit selection with usable IDs without adding unselected sources. */
    @Test
    fun `search targets narrow an explicit selection to the usable ids`() {
        assertEquals(
            listOf("b"),
            DiscoverSources.searchTargets(selected = setOf("a", "b"), usableIds = listOf("b", "c")),
        )
    }

    /**
     * Creates an installed source with configurable state and capabilities without writing plugin
     * files.
     */
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
