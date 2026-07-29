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
        // 过滤系统框架噪声：栈里不含 com.exio.inkleaf 的违规来自系统/厂商代码
        // （OnePlus OplusUIFirstManager / OplusBinderProxyManager / Android ContextImpl 等），
        // 应用层改不了，留在诊断里只会淹没真实信号。
        if (!violation.stackTraceToString().contains("com.exio.inkleaf")) return
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
