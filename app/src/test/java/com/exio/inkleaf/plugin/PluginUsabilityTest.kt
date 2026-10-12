package com.exio.inkleaf.plugin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginUsabilityTest {

    /** Establishes that an enabled, healthy source with an active version is eligible for use. */
    @Test
    fun `active enabled healthy source is usable`() {
        assertTrue(plugin(activeVersion = "1.0", disabled = false, health = PluginHealth.HEALTHY).isUsable)
    }

    /** Excludes installed sources whose runtime has no active version. */
    @Test
    fun `source without an active version is not usable`() {
        assertFalse(plugin(activeVersion = null, disabled = false, health = PluginHealth.HEALTHY).isUsable)
    }

    /** Ensures the disabled flag overrides an otherwise usable source state. */
    @Test
    fun `disabled source is not usable`() {
        assertFalse(plugin(activeVersion = "1.0", disabled = true, health = PluginHealth.HEALTHY).isUsable)
    }

    /** Excludes both runtime failures and corrupt storage from usable sources. */
    @Test
    fun `unhealthy source is not usable`() {
        assertFalse(
            plugin(activeVersion = "1.0", disabled = false, health = PluginHealth.RUNTIME_UNHEALTHY).isUsable
        )
        assertFalse(
            plugin(activeVersion = "1.0", disabled = false, health = PluginHealth.STORAGE_CORRUPT).isUsable
        )
    }

    /**
     * Creates a source from state alone so usability tests do not depend on a manifest or
     * filesystem.
     */
    private fun plugin(
        activeVersion: String?,
        disabled: Boolean,
        health: PluginHealth,
    ) = InstalledPlugin(
        state =
            PluginState(
                pluginId = "demo",
                activeVersion = activeVersion,
                disabled = disabled,
                health = health,
            ),
        directory = File("plugins/demo"),
        activeDirectory = null,
        manifest = null,
    )
}
