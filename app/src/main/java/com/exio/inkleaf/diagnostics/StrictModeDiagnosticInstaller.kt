package com.exio.inkleaf.diagnostics

import android.content.Context

/** Release builds intentionally do not install StrictMode policies. */
internal object StrictModeDiagnosticInstaller {
    fun install(context: Context) {
        // Debug source sets contribute the implementation. Reflection keeps release free of it
        // without compiling duplicate class names from main and debug into one variant.
        runCatching {
            val installer = Class.forName(DEBUG_INSTALLER_CLASS)
            val instance = installer.getField("INSTANCE").get(null)
            installer.getMethod("install", Context::class.java).invoke(instance, context)
        }
    }

    private const val DEBUG_INSTALLER_CLASS =
        "com.exio.inkleaf.diagnostics.DebugStrictModeDiagnosticInstaller"
}
