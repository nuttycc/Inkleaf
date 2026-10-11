package com.exio.inkleaf.plugin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginUsabilityTest {

    @Test
    fun `active enabled healthy source is usable`() {
        assertTrue(plugin(activeVersion = "1.0", disabled = false, health = PluginHealth.HEALTHY).isUsable)
    }

    @Test
    fun `source without an active version is not usable`() {
        assertFalse(plugin(activeVersion = null, disabled = false, health = PluginHealth.HEALTHY).isUsable)
    }

    @Test
    fun `disabled source is not usable`() {
        assertFalse(plugin(activeVersion = "1.0", disabled = true, health = PluginHealth.HEALTHY).isUsable)
    }

    @Test
    fun `unhealthy source is not usable`() {
        assertFalse(
            plugin(activeVersion = "1.0", disabled = false, health = PluginHealth.RUNTIME_UNHEALTHY).isUsable
        )
        assertFalse(
            plugin(activeVersion = "1.0", disabled = false, health = PluginHealth.STORAGE_CORRUPT).isUsable
        )
    }

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
