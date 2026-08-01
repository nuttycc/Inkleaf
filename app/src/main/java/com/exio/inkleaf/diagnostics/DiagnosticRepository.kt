package com.exio.inkleaf.diagnostics

import android.app.ActivityManager
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class DiagnosticEventType {
    ERROR,
    CRASH,
    EXIT,
    BREADCRUMB,
    NETWORK,
    PLUGIN,
    STRICT_MODE,
}

enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
    FATAL,
}

data class DiagnosticEvent(
    val id: String,
    val timestamp: String,
    val sessionId: String,
    val type: DiagnosticEventType,
    val severity: DiagnosticSeverity = defaultDiagnosticSeverity(type),
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
                File(directory(), PREVIOUS_EVENTS_FILE_NAME).delete()
                File(directory(), TEMPORARY_EVENTS_FILE_NAME).delete()
                emergencyDirectory().listFiles()?.forEach(File::delete)
                clearPluginLogFiles()
                breadcrumbs.clear()
                publish(emptyList())
            }
        }
    }

    /**
     * Exports the complete local diagnostic bundle. This method closes [output] after finishing the
     * ZIP, which is the expected ownership model for a SAF output stream.
     */
    suspend fun exportZip(output: OutputStream) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                    addZipFile(zip, eventsFile(), "events.jsonl")
                    val events = readEventsLocked()
                    addZipEvents(zip, events, "exits.jsonl") { it.type == DiagnosticEventType.EXIT }
                    addZipEvents(zip, events, "breadcrumbs.jsonl") { it.type == DiagnosticEventType.BREADCRUMB }
                    addZipEvents(zip, events, "network.jsonl") { it.type == DiagnosticEventType.NETWORK }
                    emergencyDirectory().listFiles()?.sortedBy { it.name }?.forEach { file ->
                        addZipFile(zip, file, "emergency/${file.name}")
                    }
                    addPluginLogFiles(zip)
                    val summary =
                        buildJsonObject {
                            put("sessionId", sessionId)
                            put("packageName", appContext.packageName)
                            put("appVersion", appContext.appVersionNameOrUnknown())
                            put("sdkInt", Build.VERSION.SDK_INT)
                            put("deviceManufacturer", Build.MANUFACTURER)
                            put("deviceModel", Build.MODEL)
                            put("deviceFingerprint", Build.FINGERPRINT)
                            put("exportedAt", Instant.now().toString())
                        }
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(summary.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * 写入 cacheDir 临时文件并构造系统分享 Intent。调用方负责 `startActivity(chooser)`。
     *
     * 不走 SAF：避免"先选目录再上传到电脑"两步操作。临时文件留在 cacheDir，系统可在低内存时回收。
     */
    suspend fun createShareIntent(context: Context): Intent =
        withContext(Dispatchers.IO) {
            val exportDir = File(context.cacheDir, "diagnostic-exports").apply { mkdirs() }
            val exportFile = File(exportDir, "inkleaf-diagnostics-${System.currentTimeMillis()}.zip")
            FileOutputStream(exportFile).use { exportZip(it) }

            val contentUri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    exportFile,
                )
            val sendIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    clipData = ClipData.newUri(context.contentResolver, exportFile.name, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            Intent.createChooser(sendIntent, "分享诊断包").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

    /**
     * 通过 MediaStore 写入公共 Downloads 目录。返回最终 Uri。
     *
     * minSdk=29，可直接用 `MediaStore.Downloads` collection + `RELATIVE_PATH`，
     * 应用写入自己 scope 内的 Downloads 不需要 WRITE_EXTERNAL_STORAGE 权限。
     * 写入完成后立即可被 USB 拉取 / 网盘同步 / 文件管理器查看。
     */
    suspend fun saveToDownloads(context: Context): Uri =
        withContext(Dispatchers.IO) {
            val fileName = "inkleaf-diagnostics-${System.currentTimeMillis()}.zip"
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    // 相对路径：Downloads 根目录下的 Inkleaf 子目录，便于用户查找
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/Inkleaf")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: throw IOException("无法创建下载条目")
            try {
                resolver.openOutputStream(uri)?.use { exportZip(it) }
                    ?: throw IOException("无法打开输出流")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
            uri
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
        val temporary = File(directory(), TEMPORARY_EVENTS_FILE_NAME)
        FileOutputStream(temporary).use { stream ->
            events.forEach { event ->
                stream.write(event.toJsonLine().toByteArray(Charsets.UTF_8))
                stream.write(NEWLINE_BYTES)
            }
            stream.fd.sync()
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

    private fun restorePreviousJournalLocked() {
        restoreDiagnosticJournal(
            journal = eventsFile(),
            previous = File(directory(), PREVIOUS_EVENTS_FILE_NAME),
            readStrictEvents = ::readStrictDiagnosticEvents,
        )
    }

    private fun publish(events: List<DiagnosticEvent>) {
        _events.value = events.sortedByDescending { it.timestamp }
        _unreadCriticalCount.value = countUnreadCriticalDiagnostics(events)
    }

    private fun readEventsLocked(): List<DiagnosticEvent> {
        restorePreviousJournalLocked()
        return readEvents(eventsFile())
    }

    private fun readEvents(file: File): List<DiagnosticEvent> =
        file.takeIf { it.isFile }?.useLines { lines ->
            lines.mapNotNull { line -> runCatching { diagnosticEventFromJson(line) }.getOrNull() }.toList()
        }.orEmpty()

    private fun importEmergencyFilesLocked() {
        emergencyDirectory().listFiles { file -> file.extension == "json" }?.sortedBy { it.name }?.forEach { file ->
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
                            "process" to exit.processName,
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
            severity = defaultDiagnosticSeverity(type, metadata),
            title = title.take(MAX_TEXT_CHARS),
            message = (message ?: error?.message)?.take(MAX_TEXT_CHARS),
            stackTrace = (stackTrace ?: error?.stackTraceToString())?.take(MAX_TRACE_CHARS),
            metadata = metadata.mapValues { (key, value) -> redactDiagnosticValue(key, value).take(MAX_TEXT_CHARS) },
        )

    private fun directory(): File = File(appContext.filesDir, DIRECTORY_NAME)
    private fun emergencyDirectory(): File = File(directory(), EMERGENCY_DIRECTORY_NAME)
    private fun eventsFile(): File = File(directory(), EVENTS_FILE_NAME)

    private fun addPluginLogFiles(zip: ZipOutputStream) {
        val pluginsDirectory = File(appContext.filesDir, "plugins")
        if (!pluginsDirectory.isDirectory) return
        pluginLogFiles(pluginsDirectory).forEach { file ->
            val lines = file.useLines { lines -> lines.mapNotNull(::sanitizePluginLogLine).toList() }
            if (lines.isEmpty()) return@forEach
            val relativePath = file.toRelativeString(pluginsDirectory).replace(File.separatorChar, '/')
            zip.putNextEntry(ZipEntry("plugins/$relativePath"))
            lines.forEach { line ->
                zip.write(line.toByteArray(Charsets.UTF_8))
                zip.write('\n'.code)
            }
            zip.closeEntry()
        }
    }

    private fun clearPluginLogFiles() {
        val pluginsDirectory = File(appContext.filesDir, "plugins")
        if (!pluginsDirectory.isDirectory) return
        pluginLogFiles(pluginsDirectory).forEach(File::delete)
    }

    companion object {
        private const val TAG = "Diagnostics"
        private const val DIRECTORY_NAME = "diagnostics"
        private const val EMERGENCY_DIRECTORY_NAME = "emergency"
        private const val EVENTS_FILE_NAME = "events.jsonl"
        private const val PREVIOUS_EVENTS_FILE_NAME = ".events.previous"
        private const val TEMPORARY_EVENTS_FILE_NAME = ".events.tmp"
        private const val EXIT_KEY = "exitKey"
        private const val MAX_BREADCRUMBS = 50
        private const val MAX_EXIT_REASONS = 50
        private const val MAX_TEXT_CHARS = 4_096
        private const val MAX_TRACE_CHARS = 32 * 1024
        private val NEWLINE_BYTES = byteArrayOf('\n'.code.toByte())

        @Volatile private var instance: DiagnosticRepository? = null

        fun get(context: Context): DiagnosticRepository =
            instance ?: synchronized(this) {
                instance ?: DiagnosticRepository(context.applicationContext).also { instance = it }
            }
    }
}

internal fun readStrictDiagnosticEvents(file: File): List<DiagnosticEvent>? =
    file.takeIf(File::isFile)?.useLines { lines ->
        val events = mutableListOf<DiagnosticEvent>()
        for (line in lines) {
            if (line.isBlank()) continue
            val event = runCatching { diagnosticEventFromJson(line) }.getOrNull() ?: return@useLines null
            events += event
        }
        events
    }

/**
 * Recovers the complete previous journal after a power loss between staging and replacement.
 * A readable current journal always wins, because it is newer than the staged backup.
 */
internal fun restoreDiagnosticJournal(
    journal: File,
    previous: File,
    readStrictEvents: (File) -> List<DiagnosticEvent>?,
): Boolean {
    if (!previous.isFile) return false
    val currentEvents = journal.takeIf(File::isFile)?.let(readStrictEvents)
    if (!currentEvents.isNullOrEmpty()) {
        previous.delete()
        return false
    }
    if (readStrictEvents(previous).isNullOrEmpty()) return false
    if (journal.exists() && !journal.delete()) return false
    return previous.renameTo(journal)
}

internal fun retainDiagnosticEvents(events: List<DiagnosticEvent>): List<DiagnosticEvent> {
    val newestFirst = events.sortedByDescending { it.timestamp }
    val critical =
        newestFirst
            .filter { it.type == DiagnosticEventType.CRASH || it.type == DiagnosticEventType.EXIT }
            .take(MAX_RETAINED_CRITICAL)
    val recentBreadcrumbs =
        newestFirst.filter { it.type == DiagnosticEventType.BREADCRUMB }.take(MAX_RETAINED_BREADCRUMBS)
    val remainder =
        newestFirst.filterNot { event ->
            event.type == DiagnosticEventType.BREADCRUMB || event in critical
        }
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
    recentBreadcrumbs.forEach(::add)
    remainder.forEach(::add)
    return retained.sortedBy { it.timestamp }
}

internal fun defaultDiagnosticSeverity(
    type: DiagnosticEventType,
    metadata: Map<String, String> = emptyMap(),
): DiagnosticSeverity =
    when (type) {
        DiagnosticEventType.CRASH -> DiagnosticSeverity.FATAL
        DiagnosticEventType.ERROR -> DiagnosticSeverity.ERROR
        DiagnosticEventType.STRICT_MODE -> DiagnosticSeverity.WARNING
        DiagnosticEventType.NETWORK -> {
            val status = metadata["status"]?.toIntOrNull()
            if (metadata["failureType"] != null || (status != null && status >= 400)) {
                DiagnosticSeverity.WARNING
            } else {
                DiagnosticSeverity.INFO
            }
        }
        DiagnosticEventType.EXIT -> exitDiagnosticSeverity(metadata["reason"]?.toIntOrNull())
        DiagnosticEventType.BREADCRUMB,
        DiagnosticEventType.PLUGIN -> DiagnosticSeverity.INFO
    }

internal fun countUnreadCriticalDiagnostics(events: List<DiagnosticEvent>): Int =
    events.count { event ->
        !event.read &&
            (event.severity == DiagnosticSeverity.ERROR || event.severity == DiagnosticSeverity.FATAL)
    }

private fun exitDiagnosticSeverity(reason: Int?): DiagnosticSeverity =
    when (reason) {
        4, 5, 6, 7 -> DiagnosticSeverity.ERROR
        0, 2, 3, 9, 12, 13 -> DiagnosticSeverity.WARNING
        else -> DiagnosticSeverity.INFO
    }

internal fun redactDiagnosticValue(key: String, value: String): String {
    if (isSensitiveDiagnosticKey(key)) {
        return "[redacted]"
    }
    return value.substringBefore('?')
}

/**
 * Reports failures exposed by an async boundary, where CoroutineExceptionHandler cannot observe
 * them. The original error remains visible to the awaiting caller for its normal fallback path.
 */
suspend fun <T> Deferred<T>.awaitReported(
    context: Context,
    operation: String,
    metadata: Map<String, String> = emptyMap(),
): T =
    try {
        await()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        DiagnosticRepository.get(context).record(
            type = DiagnosticEventType.ERROR,
            title = operation,
            error = error,
            metadata = metadata,
        )
        throw error
    }

private fun DiagnosticEvent.toJsonLine(): String =
    buildJsonObject {
        put("id", id)
        put("timestamp", timestamp)
        put("sessionId", sessionId)
        put("type", type.name)
        put("severity", severity.name)
        put("title", title)
        message?.let { put("message", it) }
        stackTrace?.let { put("stackTrace", it) }
        put("read", read)
        put("metadata", JsonObject(metadata.mapValues { JsonPrimitive(it.value) }))
    }.toString()

internal fun diagnosticEventJsonLine(event: DiagnosticEvent): String = event.toJsonLine()

private fun addZipFile(zip: ZipOutputStream, file: File, entryName: String) {
    if (!file.isFile) return
    zip.putNextEntry(ZipEntry(entryName))
    file.inputStream().use { input -> input.copyTo(zip) }
    zip.closeEntry()
}

private fun addZipEvents(
    zip: ZipOutputStream,
    events: List<DiagnosticEvent>,
    entryName: String,
    predicate: (DiagnosticEvent) -> Boolean,
) {
    zip.putNextEntry(ZipEntry(entryName))
    events.filter(predicate).forEach { event ->
        zip.write(event.toJsonLine().toByteArray(Charsets.UTF_8))
        zip.write('\n'.code)
    }
    zip.closeEntry()
}

private fun pluginLogFiles(pluginsDirectory: File): Sequence<File> =
    pluginsDirectory.walkTopDown().filter { file ->
        file.isFile && file.toRelativeString(pluginsDirectory).split(File.separatorChar).contains("logs")
    }

/**
 * Rewrites plugin JSONL into an export-only summary. Plugin messages are unstructured text and may
 * contain credentials, so no original message is exported. Field values are redacted recursively
 * whenever their key is sensitive.
 */
internal fun sanitizePluginLogLine(line: String): String? =
    runCatching {
        val source = Json.parseToJsonElement(line).jsonObject
        buildJsonObject {
            source["pluginId"]?.let { put("pluginId", it) }
            source["timestampMs"]?.let { put("timestampMs", it) }
            source["level"]?.let { put("level", it) }
            put("message", "[omitted from diagnostic export]")
            source["fields"]?.let { put("fields", redactPluginLogElement(it)) }
        }.toString()
    }.getOrNull()

private fun redactPluginLogElement(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject ->
            JsonObject(
                element.mapValues { (key, value) ->
                    if (isSensitiveDiagnosticKey(key)) JsonPrimitive("[redacted]")
                    else if (isUrlDiagnosticKey(key) && value is JsonPrimitive && value.isString) {
                        JsonPrimitive(redactDiagnosticUrl(value.content))
                    }
                    else redactPluginLogElement(value)
                }
            )

        is JsonArray -> JsonArray(element.map(::redactPluginLogElement))
        else -> element
    }

private fun isSensitiveDiagnosticKey(key: String): Boolean {
    val normalized = key.lowercase()
    return listOf("token", "cookie", "authorization", "password", "secret", "apikey", "api_key")
        .any(normalized::contains)
}

private fun isUrlDiagnosticKey(key: String): Boolean {
    val normalized = key.lowercase()
    return normalized == "url" || normalized == "uri" || normalized.endsWith("url") || normalized.endsWith("uri")
}

internal fun redactDiagnosticUrl(value: String): String {
    value.toHttpUrlOrNull()?.let { url ->
        return url.newBuilder().query(null).fragment(null).build().toString()
    }
    return runCatching {
        URI(value).let { uri ->
            URI(uri.scheme, uri.authority, uri.path, null, null).toString()
        }
    }.getOrElse { value.substringBefore('?').substringBefore('#') }
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
        severity =
            json["severity"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { DiagnosticSeverity.valueOf(it) }.getOrNull() }
                ?: defaultDiagnosticSeverity(
                    type = DiagnosticEventType.valueOf(text("type")),
                    metadata = metadata,
                ),
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
private const val MAX_RETAINED_BREADCRUMBS = 50
