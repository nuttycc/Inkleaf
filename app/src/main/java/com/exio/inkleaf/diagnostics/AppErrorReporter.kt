package com.exio.inkleaf.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import timber.log.Timber

data class AppErrorReport(val summary: String, val details: String)

/** Builds copyable UI feedback and sends the underlying failure to Timber. */
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
        Timber.e(error, "%s failed", operation)
        return report
    }
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

private const val MAX_ERROR_SUMMARY_CHARS = 160
