package com.exio.inkleaf.diagnostics

import android.os.StrictMode
import java.util.concurrent.Executors
import timber.log.Timber

/** Debug-only StrictMode capture. The listener never blocks or terminates the app. */
internal object DebugStrictModeDiagnosticInstaller {
    private val listenerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "inkleaf-strictmode-diagnostics").apply { isDaemon = true }
    }
    fun install() {
        val listener = StrictMode.OnThreadViolationListener { violation ->
            report("Thread policy violation", violation)
        }
        val vmListener = StrictMode.OnVmViolationListener { violation ->
            report("VM policy violation", violation)
        }
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .penaltyListener(listenerExecutor, listener)
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .penaltyListener(listenerExecutor, vmListener)
                .build()
        )
    }

    private fun report(title: String, violation: Throwable) {
        // Ignore framework and vendor violations that application code cannot fix.
        if (!violation.stackTraceToString().contains("com.exio.inkleaf")) return
        Timber.w(violation, "%s: %s", title, violation.javaClass.simpleName)
    }
}
