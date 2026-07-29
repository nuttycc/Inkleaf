package com.exio.inkleaf.diagnostics

import android.content.Context
import android.os.StrictMode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Debug-only StrictMode capture. The listener never blocks or terminates the app. */
internal object DebugStrictModeDiagnosticInstaller {
    private val listenerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "inkleaf-strictmode-diagnostics").apply { isDaemon = true }
    }
    private val reporterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reporting = AtomicBoolean(false)

    fun install(context: Context) {
        val appContext = context.applicationContext
        val listener = StrictMode.OnThreadViolationListener { violation ->
            report(appContext, "Thread policy violation", violation)
        }
        val vmListener = StrictMode.OnVmViolationListener { violation ->
            report(appContext, "VM policy violation", violation)
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

    private fun report(context: Context, title: String, violation: Throwable) {
        if (!reporting.compareAndSet(false, true)) return
        reporterScope.launch {
            try {
                DiagnosticRepository.get(context).record(
                    type = DiagnosticEventType.STRICT_MODE,
                    title = title,
                    error = violation,
                    metadata = mapOf("violationType" to violation.javaClass.simpleName),
                )
            } finally {
                reporting.set(false)
            }
        }
    }
}
