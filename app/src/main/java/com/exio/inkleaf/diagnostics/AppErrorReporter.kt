package com.exio.inkleaf.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

data class AppErrorReport(val summary: String, val details: String)

/**
 * Compatibility facade for UI code that needs a copyable error report.
 *
 * New diagnostics remain structured in [DiagnosticRepository], while this API deliberately keeps
 * the existing concise Chinese feedback and plain-text details expected by source action screens.
 */
object AppErrorReporter {
    suspend fun report(
        context: Context,
        operation: String,
        error: Throwable,
        metadata: Map<String, String> = emptyMap(),
    ): AppErrorReport {
        val report =
            buildAppErrorReport(
                operation = operation,
                error = error,
                metadata = metadata,
                timestamp = Instant.now().toString(),
                appVersion = context.appVersionNameOrUnknown(),
            )
        Log.e(TAG, "$operation failed", error)
        try {
            DiagnosticRepository.get(context).record(
                type = DiagnosticEventType.ERROR,
                title = operation,
                error = error,
                metadata = metadata,
            )
        } catch (writeError: CancellationException) {
            throw writeError
        } catch (writeError: Throwable) {
            Log.e(TAG, "Unable to persist app error", writeError)
        }
        return report
    }

    private const val TAG = "AppErrorReporter"
}

internal fun Context.appVersionNameOrUnknown(): String =
    runCatching {
            val packageInfo =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0)
                }
            packageInfo.versionName
        }
        .getOrNull()
        .orEmpty()
        .ifBlank { "unknown" }

internal fun buildAppErrorReport(
    operation: String,
    error: Throwable,
    metadata: Map<String, String>,
    timestamp: String,
    appVersion: String,
): AppErrorReport {
    val errorName = error::class.java.simpleName.ifBlank { error::class.java.name }
    val reason =
        (error.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: errorName).take(
            MAX_ERROR_SUMMARY_CHARS
        )
    val details = buildString {
        appendLine("Time: $timestamp")
        appendLine("App version: $appVersion")
        appendLine("Operation: $operation")
        metadata.forEach { (key, value) -> appendLine("$key: $value") }
        appendLine("Exception: ${error::class.java.name}")
        appendLine("Message: ${error.message?.takeIf(String::isNotBlank) ?: "(no message)"}")
        appendLine("Causes:")
        error.causeChain().forEachIndexed { index, cause ->
            val message = cause.message?.takeIf(String::isNotBlank) ?: "(no message)"
            appendLine("  $index. ${cause::class.java.name}: $message")
        }
        appendLine("Stack trace:")
        append(error.stackTraceToString())
    }
    return AppErrorReport(summary = "操作失败：$reason", details = details)
}

private fun Throwable.causeChain(): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val causes = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        causes += current
        current = current.cause
    }
    return causes
}

/** Retained for compatibility with old on-device reports and focused JVM tests. */
internal fun retainErrorLogEntries(existing: String, newEntry: String): String {
    val separator = "\n\n=== Inkleaf error ===\n\n"
    val escaped = "\n\n=== Inkleaf error marker ===\n\n"
    val entries = existing.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
    val safeNewEntry = newEntry.trim().replace(separator, escaped)
    return (entries + safeNewEntry).takeLast(100).joinToString(separator, postfix = "\n")
}

private const val MAX_ERROR_SUMMARY_CHARS = 160
