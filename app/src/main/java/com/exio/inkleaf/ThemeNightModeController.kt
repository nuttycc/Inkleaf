package com.exio.inkleaf

import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.exio.inkleaf.data.DarkMode
import com.exio.inkleaf.data.ThemeSettings
import com.exio.inkleaf.data.planThemeApplication
import com.exio.inkleaf.data.resolveDark

internal object ThemeNightModeController {
    /**
     * Synchronizes the stored preference before content is composed.
     *
     * Returns true when applying the preference changes the effective uiMode and AppCompat will
     * recreate the Activity. The caller must keep the splash screen visible and avoid composing the
     * old Activity in that case.
     */
    fun synchronizeStartup(activity: AppCompatActivity, darkMode: DarkMode): Boolean {
        val currentIsDark = activity.resources.configuration.isDark()
        val targetIsDark = darkMode.resolveDark(isSystemCurrentlyDark())
        applyAppCompatMode(darkMode)
        return currentIsDark != targetIsDark
    }

    /** Applies a committed theme and guarantees that the Activity is recreated at most once. */
    fun applyCommittedTheme(
        activity: AppCompatActivity,
        applied: ThemeSettings,
        committed: ThemeSettings,
    ) {
        val plan =
            planThemeApplication(
                applied = applied,
                draft = committed,
                currentIsDark = activity.resources.configuration.isDark(),
                systemIsDark = isSystemCurrentlyDark(),
            )
        if (plan.updateNightMode) {
            applyAppCompatMode(committed.darkMode)
        }
        if (plan.recreateExplicitly) {
            activity.recreate()
        }
    }

    private fun applyAppCompatMode(darkMode: DarkMode) {
        AppCompatDelegate.setDefaultNightMode(darkMode.toAppCompatMode())
    }
}

internal fun isSystemCurrentlyDark(): Boolean = Resources.getSystem().configuration.isDark()

private fun Configuration.isDark(): Boolean =
    uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

private fun DarkMode.toAppCompatMode(): Int =
    when (this) {
        DarkMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        DarkMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        DarkMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }
