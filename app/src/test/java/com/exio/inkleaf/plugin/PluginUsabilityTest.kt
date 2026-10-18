package com.exio.inkleaf.plugin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginUsabilityTest {

    /** Establishes that a healthy source with an active version is eligible for use. */
    @Test
    fun `active healthy source is usable`() {
        assertTrue(plugin(activeVersion = "1.0", health = PluginHealth.HEALTHY).isUsable)
    }

    /** Excludes installed sources whose runtime has no active version. */
    @Test
    fun `source without an active version is not usable`() {
        assertFalse(plugin(activeVersion = null, health = PluginHealth.HEALTHY).isUsable)
    }

    /** Excludes both runtime failures and corrupt storage from usable sources. */
    @Test
    fun `unhealthy source is not usable`() {
        assertFalse(plugin(activeVersion = "1.0", health = PluginHealth.RUNTIME_UNHEALTHY).isUsable)
        assertFalse(plugin(activeVersion = "1.0", health = PluginHealth.STORAGE_CORRUPT).isUsable)
    }

    /**
     * Creates a source from state alone so usability tests do not depend on a manifest or
     * filesystem.
     */
    private fun plugin(
        activeVersion: String?,
        health: PluginHealth,
    ) = InstalledPlugin(
        state =
            PluginState(
                pluginId = "demo",
                activeVersion = activeVersion,
                health = health,
            ),
        directory = File("plugins/demo"),
        activeDirectory = null,
        manifest = null,
    )
}
