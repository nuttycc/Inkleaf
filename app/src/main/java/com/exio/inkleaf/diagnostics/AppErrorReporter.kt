package com.exio.inkleaf.diagnostics

import android.content.Context
import android.util.Log
import com.exio.inkleaf.BuildConfig
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AppErrorReport(val summary: String, val details: String)

/** Records a complete error while returning concise feedback that the UI can present and copy. */
object AppErrorReporter {
    private val logMutex = Mutex()

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
                appVersion = BuildConfig.VERSION_NAME,
            )
        Log.e(TAG, "$operation failed", error)
        withContext(Dispatchers.IO) {
            try {
                logMutex.withLock {
                    val logFile = File(context.applicationContext.filesDir, LOG_FILE_NAME)
                    val existing = if (logFile.isFile) logFile.readText() else ""
                    logFile.writeText(retainErrorLogEntries(existing, report.details))
                }
            } catch (logError: CancellationException) {
                throw logError
            } catch (logError: Throwable) {
                Log.e(TAG, "Unable to persist app error", logError)
            }
        }
        return report
    }

    private const val TAG = "AppErrorReporter"
    private const val LOG_FILE_NAME = "errors.log"
}

internal fun buildAppErrorReport(
    operation: String,
    error: Throwable,
    metadata: Map<String, String>,
    timestamp: String,
    appVersion: String,
): AppErrorReport {
    val errorName = error::class.java.simpleName.ifBlank { error::class.java.name }
    val reason =
        (error.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: errorName)
            .take(MAX_ERROR_SUMMARY_CHARS)
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

internal fun retainErrorLogEntries(existing: String, newEntry: String): String {
    val existingEntries =
        existing.split(ERROR_LOG_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    val safeNewEntry = newEntry.trim().replace(ERROR_LOG_SEPARATOR, ESCAPED_ERROR_LOG_SEPARATOR)
    val entries = (existingEntries + safeNewEntry).takeLast(MAX_ERROR_LOG_ENTRIES)
    return entries.joinToString(ERROR_LOG_SEPARATOR, postfix = "\n")
}

private const val ERROR_LOG_SEPARATOR = "\n\n=== Inkleaf error ===\n\n"
private const val ESCAPED_ERROR_LOG_SEPARATOR = "\n\n=== Inkleaf error marker ===\n\n"
private const val MAX_ERROR_LOG_ENTRIES = 100
private const val MAX_ERROR_SUMMARY_CHARS = 160
