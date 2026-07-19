package com.exio.inkleaf.data

/** Resolves the user's preference against the device mode without reading Android state directly. */
internal fun DarkMode.resolveDark(systemIsDark: Boolean): Boolean = when (this) {
    DarkMode.SYSTEM -> systemIsDark
    DarkMode.LIGHT -> false
    DarkMode.DARK -> true
}

/** Describes the single recreation path needed after an atomic theme commit. */
internal data class ThemeApplyPlan(
    val updateNightMode: Boolean,
    val recreateExplicitly: Boolean,
)

internal fun planThemeApplication(
    applied: ThemeSettings,
    draft: ThemeSettings,
    currentIsDark: Boolean,
    systemIsDark: Boolean,
): ThemeApplyPlan {
    require(applied != draft) { "Theme application requires a changed draft" }

    val nightModeChanged = applied.darkMode != draft.darkMode
    val targetIsDark = draft.darkMode.resolveDark(systemIsDark)
    return ThemeApplyPlan(
        updateNightMode = nightModeChanged,
        // AppCompat recreates only when the effective uiMode changes.
        recreateExplicitly = !nightModeChanged || currentIsDark == targetIsDark,
    )
}
