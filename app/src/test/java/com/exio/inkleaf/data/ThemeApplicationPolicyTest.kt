package com.exio.inkleaf.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeApplicationPolicyTest {
    @Test
    fun `palette-only change recreates explicitly without touching night mode`() {
        val applied = ThemeSettings(seed = ThemeSeed.INK, darkMode = DarkMode.LIGHT)
        val draft = applied.copy(seed = ThemeSeed.ROUGE)

        val plan =
            planThemeApplication(
                applied = applied,
                draft = draft,
                currentIsDark = false,
                systemIsDark = true,
            )

        assertFalse(plan.updateNightMode)
        assertTrue(plan.recreateExplicitly)
    }

    @Test
    fun `effective night-mode change lets AppCompat own recreation`() {
        val applied = ThemeSettings(darkMode = DarkMode.LIGHT)
        val draft = applied.copy(darkMode = DarkMode.DARK)

        val plan =
            planThemeApplication(
                applied = applied,
                draft = draft,
                currentIsDark = false,
                systemIsDark = false,
            )

        assertTrue(plan.updateNightMode)
        assertFalse(plan.recreateExplicitly)
    }

    @Test
    fun `combined palette and effective night-mode change still recreates only once`() {
        val applied = ThemeSettings(seed = ThemeSeed.INK, darkMode = DarkMode.LIGHT)
        val draft = applied.copy(seed = ThemeSeed.AMBER, darkMode = DarkMode.DARK)

        val plan =
            planThemeApplication(
                applied = applied,
                draft = draft,
                currentIsDark = false,
                systemIsDark = false,
            )

        assertTrue(plan.updateNightMode)
        assertFalse(plan.recreateExplicitly)
    }

    @Test
    fun `night-mode preference change with same appearance recreates once explicitly`() {
        val applied = ThemeSettings(darkMode = DarkMode.LIGHT)
        val draft = applied.copy(darkMode = DarkMode.SYSTEM)

        val plan =
            planThemeApplication(
                applied = applied,
                draft = draft,
                currentIsDark = false,
                systemIsDark = false,
            )

        assertTrue(plan.updateNightMode)
        assertTrue(plan.recreateExplicitly)
    }

    @Test
    fun `system preference resolves against the device appearance`() {
        assertTrue(DarkMode.SYSTEM.resolveDark(systemIsDark = true))
        assertFalse(DarkMode.SYSTEM.resolveDark(systemIsDark = false))
        assertTrue(DarkMode.DARK.resolveDark(systemIsDark = false))
        assertFalse(DarkMode.LIGHT.resolveDark(systemIsDark = true))
    }

    @Test
    fun `unchanged draft cannot request a recreation`() {
        val settings = ThemeSettings()

        assertThrows(IllegalArgumentException::class.java) {
            planThemeApplication(
                applied = settings,
                draft = settings,
                currentIsDark = false,
                systemIsDark = false,
            )
        }
    }
}
