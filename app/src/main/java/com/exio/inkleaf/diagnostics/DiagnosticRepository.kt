package com.exio.inkleaf.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.content
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class DiagnosticEventType {
    ERROR,
    CRASH,
    EXIT,
    BREADCRUMB,
    NETWORK,
    PLUGIN,
    STRICT_MODE,
}

data class DiagnosticEvent(
    val id: String,
    val timestamp: String,
    val sessionId: String,
    val type: DiagnosticEventType,
    val title: String,
    val message: String? = null,
    val stackTrace: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val read: Boolean = false,
)

/**
 * Owns the small on-device diagnostic journal used by both normal failures and startup recovery.
 *
 * The crash path intentionally bypasses coroutines and the normal writer lock. A process may be
 * dying while another thread holds that lock, so the only reliable work there is an atomic file.
 */
class DiagnosticRepository private constructor(private val context: Context) {
    private val appContext = context.applicationContext
    private val writeMutex = Mutex()
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    private val _unreadCriticalCount = MutableStateFlow(0)
    private val breadcrumbs = ArrayDeque<DiagnosticEvent>()

    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()
    val unreadCriticalCount: StateFlow<Int> = _unreadCriticalCount.asStateFlow()
    val sessionId: String = UUID.randomUUID().toString()

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                importEmergencyFilesLocked()
                collectHistoricalExitReasonsLocked()
                loadEventsLocked()
            }
        }
    }

    suspend fun record(
        type: DiagnosticEventType,
        title: String,
        error: Throwable? = null,
        message: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ): DiagnosticEvent =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                appendLocked(newEvent(type, title, error, message, metadata))
            }
        }

    suspend fun breadcrumb(title: String, metadata: Map<String, String> = emptyMap()): DiagnosticEvent =
        record(DiagnosticEventType.BREADCRUMB, title, metadata = metadata)

    /** Writes the only diagnostic artifact that is safe to create from an uncaught exception handler. */
    fun recordEmergency(
        type: DiagnosticEventType,
        title: String,
        thread: Thread,
        error: Throwable,
    ) {
        runCatching {
            val event =
                newEvent(
                    type = type,
                    title = title,
                    error = error,
                    metadata = mapOf("thread" to thread.name),
                )
            emergencyDirectory().mkdirs()
            val target = File(emergencyDirectory(), "crash-${event.id}.json")
            val temporary = File(emergencyDirectory(), ".${target.name}.tmp")
            FileOutputStream(temporary).use { stream ->
                stream.write(event.toJsonLine().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                temporary.delete()
            }
        }.onFailure { Log.e(TAG, "Unable to persist emergency crash", it) }
    }

    fun recordEmergencyCrash(thread: Thread, error: Throwable) =
        recordEmergency(DiagnosticEventType.CRASH, "Uncaught exception", thread, error)

    suspend fun markAllRead() {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val updated = readEventsLocked().map { event ->
                    if (event.type == DiagnosticEventType.CRASH || event.type == DiagnosticEventType.EXIT) {
                        event.copy(read = true)
                    } else {
                        event
                    }
                }
                rewriteLocked(updated)
            }
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                eventsFile().delete()
                emergencyDirectory().listFiles()?.forEach(File::delete)
                breadcrumbs.clear()
                publish(emptyList())
            }
        }
    }

    /** Exports only diagnostic data. Callers can add optional app-specific attachments to another ZIP. */
    suspend fun exportZip(output: OutputStream) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                    addZipFile(zip, eventsFile(), "events.jsonl")
                    emergencyDirectory().listFiles()?.sortedBy(File::name)?.forEach { file ->
                        addZipFile(zip, file, "emergency/${file.name}")
                    }
                    val summary =
                        buildJsonObject {
                            put("sessionId", sessionId)
                            put("packageName", appContext.packageName)
                            put("appVersion", appContext.appVersionNameOrUnknown())
                            put("sdkInt", Build.VERSION.SDK_INT)
                            put("exportedAt", Instant.now().toString())
                        }
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(summary.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    private fun appendLocked(event: DiagnosticEvent): DiagnosticEvent {
        val retained = retainDiagnosticEvents(readEventsLocked() + event)
        rewriteLocked(retained)
        if (event.type == DiagnosticEventType.BREADCRUMB) {
            breadcrumbs.addLast(event)
            while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeFirst()
        }
        return event
    }

    private fun rewriteLocked(events: List<DiagnosticEvent>) {
        directory().mkdirs()
        val temporary = File(directory(), ".events.tmp")
        FileOutputStream(temporary).bufferedWriter(Charsets.UTF_8).use { writer ->
            events.forEach { event ->
                writer.appendLine(event.toJsonLine())
            }
        }
        val journal = eventsFile()
        val backup = File(directory(), ".events.previous")
        // File.renameTo cannot replace an existing target on every Android filesystem. The
        // previous journal remains recoverable until the complete temporary journal is in place.
        backup.delete()
        if (journal.exists() && !journal.renameTo(backup)) {
            temporary.delete()
            throw IllegalStateException("Unable to stage diagnostic journal replacement")
        }
        if (!temporary.renameTo(journal)) {
            if (backup.exists()) backup.renameTo(journal)
            temporary.delete()
            throw IllegalStateException("Unable to replace diagnostic journal")
        }
        backup.delete()
        publish(events)
    }

    private fun loadEventsLocked() {
        val loaded = retainDiagnosticEvents(readEventsLocked())
        breadcrumbs.clear()
        loaded.filter { it.type == DiagnosticEventType.BREADCRUMB }.takeLast(MAX_BREADCRUMBS).forEach(breadcrumbs::addLast)
        publish(loaded)
    }

    private fun publish(events: List<DiagnosticEvent>) {
        _events.value = events.sortedByDescending { it.timestamp }
        _unreadCriticalCount.value =
            events.count { !it.read && (it.type == DiagnosticEventType.CRASH || it.type == DiagnosticEventType.EXIT) }
    }

    private fun readEventsLocked(): List<DiagnosticEvent> =
        eventsFile().takeIf(File::isFile)?.useLines { lines ->
            lines.mapNotNull { line -> runCatching { diagnosticEventFromJson(line) }.getOrNull() }.toList()
        }.orEmpty()

    private fun importEmergencyFilesLocked() {
        emergencyDirectory().listFiles { file -> file.extension == "json" }?.sortedBy(File::name)?.forEach { file ->
            val event = runCatching { diagnosticEventFromJson(file.readText()) }.getOrNull() ?: return@forEach
            val current = readEventsLocked()
            if (current.none { it.id == event.id }) rewriteLocked(retainDiagnosticEvents(current + event))
            file.delete()
        }
    }

    private fun collectHistoricalExitReasonsLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = appContext.getSystemService(ActivityManager::class.java) ?: return
        val existingKeys = readEventsLocked().mapNotNull { it.metadata[EXIT_KEY] }.toSet()
        manager.getHistoricalProcessExitReasons(null, 0, MAX_EXIT_REASONS).forEach { exit ->
            val key = "${exit.timestamp}:${exit.reason}:${exit.pid}"
            if (key in existingKeys) return@forEach
            val trace = runCatching { exit.traceInputStream?.bufferedReader()?.use { it.readText().take(MAX_TRACE_CHARS) } }.getOrNull()
            appendLocked(
                newEvent(
                    type = DiagnosticEventType.EXIT,
                    title = "Process exit (${exit.reason})",
                    message = exit.description,
                    metadata =
                        mapOf(
                            EXIT_KEY to key,
                            "reason" to exit.reason.toString(),
                            "status" to exit.status.toString(),
                            "importance" to exit.importance.toString(),
                            "process" to (exit.processName ?: "unknown"),
                            "pssKb" to exit.pss.toString(),
                            "rssKb" to exit.rss.toString(),
                        ),
                    stackTrace = trace,
                )
            )
        }
    }

    private fun newEvent(
        type: DiagnosticEventType,
        title: String,
        error: Throwable? = null,
        message: String? = null,
        metadata: Map<String, String> = emptyMap(),
        stackTrace: String? = null,
    ): DiagnosticEvent =
        DiagnosticEvent(
            id = UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            sessionId = sessionId,
            type = type,
            title = title.take(MAX_TEXT_CHARS),
            message = (message ?: error?.message)?.take(MAX_TEXT_CHARS),
            stackTrace = (stackTrace ?: error?.stackTraceToString())?.take(MAX_TRACE_CHARS),
            metadata = metadata.mapValues { (key, value) -> redactDiagnosticValue(key, value).take(MAX_TEXT_CHARS) },
        )

    private fun directory(): File = File(appContext.filesDir, DIRECTORY_NAME)
    private fun emergencyDirectory(): File = File(directory(), EMERGENCY_DIRECTORY_NAME)
    private fun eventsFile(): File = File(directory(), EVENTS_FILE_NAME)

    companion object {
        private const val TAG = "Diagnostics"
        private const val DIRECTORY_NAME = "diagnostics"
        private const val EMERGENCY_DIRECTORY_NAME = "emergency"
        private const val EVENTS_FILE_NAME = "events.jsonl"
        private const val EXIT_KEY = "exitKey"
        private const val MAX_BREADCRUMBS = 50
        private const val MAX_EXIT_REASONS = 50
        private const val MAX_TEXT_CHARS = 4_096
        private const val MAX_TRACE_CHARS = 32 * 1024

        @Volatile private var instance: DiagnosticRepository? = null

        fun get(context: Context): DiagnosticRepository =
            instance ?: synchronized(this) {
                instance ?: DiagnosticRepository(context.applicationContext).also { instance = it }
            }
    }
}

internal fun retainDiagnosticEvents(events: List<DiagnosticEvent>): List<DiagnosticEvent> {
    val newestFirst = events.sortedByDescending { it.timestamp }
    val critical =
        newestFirst
            .filter { it.type == DiagnosticEventType.CRASH || it.type == DiagnosticEventType.EXIT }
            .take(MAX_RETAINED_CRITICAL)
    val remainder =
        newestFirst.filterNot { event -> event in critical }
    val retained = linkedSetOf<DiagnosticEvent>()
    var bytes = 0
    fun add(event: DiagnosticEvent) {
        val size = event.toJsonLine().toByteArray(Charsets.UTF_8).size + 1
        if (retained.size < MAX_RETAINED_EVENTS && bytes + size <= MAX_RETAINED_BYTES) {
            retained += event
            bytes += size
        }
    }
    critical.forEach(::add)
    remainder.forEach(::add)
    return retained.sortedBy { it.timestamp }
}

internal fun redactDiagnosticValue(key: String, value: String): String {
    val normalized = key.lowercase()
    if (listOf("token", "cookie", "authorization", "password", "secret", "apikey", "api_key").any(normalized::contains)) {
        return "[redacted]"
    }
    return value.substringBefore('?')
}

private fun DiagnosticEvent.toJsonLine(): String =
    buildJsonObject {
        put("id", id)
        put("timestamp", timestamp)
        put("sessionId", sessionId)
        put("type", type.name)
        put("title", title)
        message?.let { put("message", it) }
        stackTrace?.let { put("stackTrace", it) }
        put("read", read)
        put("metadata", JsonObject(metadata.mapValues { JsonPrimitive(it.value) }))
    }.toString()

private fun addZipFile(zip: ZipOutputStream, file: File, entryName: String) {
    if (!file.isFile) return
    zip.putNextEntry(ZipEntry(entryName))
    file.inputStream().use { input -> input.copyTo(zip) }
    zip.closeEntry()
}

private fun diagnosticEventFromJson(line: String): DiagnosticEvent {
    val json = Json.parseToJsonElement(line).jsonObject
    fun text(name: String): String = requireNotNull(json[name]?.jsonPrimitive?.contentOrNull) { "Missing $name" }
    val metadata = json["metadata"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
    return DiagnosticEvent(
        id = text("id"),
        timestamp = text("timestamp"),
        sessionId = text("sessionId"),
        type = DiagnosticEventType.valueOf(text("type")),
        title = text("title"),
        message = json["message"]?.jsonPrimitive?.contentOrNull,
        stackTrace = json["stackTrace"]?.jsonPrimitive?.contentOrNull,
        metadata = metadata,
        read = json["read"]?.jsonPrimitive?.content == "true",
    )
}

private const val MAX_RETAINED_EVENTS = 1_000
private const val MAX_RETAINED_BYTES = 10 * 1024 * 1024
private const val MAX_RETAINED_CRITICAL = 20
