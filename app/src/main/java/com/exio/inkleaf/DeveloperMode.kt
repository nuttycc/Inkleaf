package com.exio.inkleaf

import android.content.Context
import com.ms.square.debugoverlay.DebugOverlay
import com.ms.square.debugoverlay.OverlayMode

internal object DeveloperMode {
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
        configure(enabled)
    }

    fun configure(enabled: Boolean) {
        DebugOverlay.configure {
            overlayMode = if (enabled) OverlayMode.FullMetrics() else OverlayMode.Hidden()
        }
    }

    private const val PREFERENCES = "developer_mode"
    private const val ENABLED = "enabled"
}
