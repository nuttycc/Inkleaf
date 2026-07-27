package com.exio.inkleaf.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PluginSettingsRepositoryTest {
    @Test
    fun `descriptor snapshot drops settings removed by a plugin update`() {
        val values = mapOf("current" to "kept", "removed" to "secret")

        val snapshot = descriptorBackedValues(values, setOf("current"))

        assertEquals(mapOf("current" to "kept"), snapshot)
    }

    @Test
    fun `descriptor snapshot reuses an already authoritative map`() {
        val values = mapOf("current" to "kept")

        val snapshot = descriptorBackedValues(values, setOf("current", "optional"))

        assertSame(values, snapshot)
    }
}
