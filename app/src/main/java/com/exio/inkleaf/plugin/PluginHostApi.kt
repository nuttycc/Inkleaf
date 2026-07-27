package com.exio.inkleaf.plugin

import java.io.File
import java.io.IOException
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer

@Serializable
data class PluginHttpRequest(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val bodyBase64: String? = null,
)

@Serializable
data class PluginHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val bodyBase64: String? = null,
    val bodyHandle: String? = null,
    val bodySizeBytes: Long,
)

@Serializable
data class PluginHttpRead(
    val handle: String,
    val offset: Long = 0L,
    val maxBytes: Int = 384 * 1024,
)

@Serializable
data class PluginHttpChunk(
    val handle: String,
    val offset: Long,
    val bodyBase64: String,
    val eof: Boolean,
)

@Serializable data class PluginKvGet(val key: String)

@Serializable data class PluginKvSet(val key: String, val value: JsonElement)

@Serializable data class PluginKvDelete(val key: String)

/** Input for settings.get; id matches PluginSettingDescriptor.id from describe(). */
@Serializable data class PluginSettingGet(val id: String)

/**
 * Host-written, plugin-read-only access to source settings.
 *
 * This stays separate from plugin-owned, read-write kv storage so plugin code cannot silently
 * change a user's choice. Null means there is no stored value or descriptor default.
 */
fun interface PluginSettingsReader {
    suspend fun read(pluginId: String, settingId: String): String?
}

@Serializable
data class PluginLogEntry(
    val level: String,
    val message: String,
    val fields: Map<String, String> = emptyMap(),
)

@Serializable data class PluginCookieSet(val url: String, val setCookie: String)

private data class PluginBodyHandle(
    val pluginId: String,
    val body: ByteArray,
    val createdAtMs: Long,
)

/** Host capability implementation used by one plugin isolate. */
class PluginHostSession(
    private val pluginId: String,
    pluginDirectory: File,
    private val json: Json = defaultJson,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val logger: PluginLogger =
        FilePluginLogger(pluginId, pluginDirectory.resolve("logs"), json, clockMs),
    private val globalHttpSemaphore: Semaphore = DEFAULT_GLOBAL_HTTP_SEMAPHORE,
    private val settingsReader: PluginSettingsReader = PluginSettingsReader { _, _ -> null },
) : PluginRpcHostHandler, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val kv = PluginKvStore(pluginDirectory.resolve("kv.json"), json)
    private val cookieJar by lazy {
        PersistentPluginCookieJar(pluginDirectory.resolve("cookies.json"), json)
    }
    private val httpSemaphore = Semaphore(PluginRuntimePolicy.MAX_PLUGIN_HTTP_CONCURRENCY)
    private val bodyHandles = ConcurrentHashMap<String, PluginBodyHandle>()
    private val httpClientDelegate = lazy {
        OkHttpClient.Builder()
            .dns(PluginNetworkPolicy.publicDns)
            .proxy(Proxy.NO_PROXY)
            .cookieJar(cookieJar)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val httpClient: OkHttpClient
        get() = httpClientDelegate.value

    override suspend fun handle(method: String, params: JsonElement): JsonElement {
        if (closed.get()) throw hostError("Plugin host session is closed")
        return when (method) {
            "http.request" ->
                json.encodeToJsonElement(httpRequest(json.decodeFromJsonElement(params)))
            "http.read" -> json.encodeToJsonElement(readBody(json.decodeFromJsonElement(params)))
            "http.close" -> {
                val handle =
                    params.jsonObject["handle"]?.jsonPrimitive?.contentOrNull
                        ?: throw hostError("http.close requires handle")
                bodyHandles.remove(handle)
                buildJsonObject { put("closed", true) }
            }
            "kv.get" -> {
                val request = json.decodeFromJsonElement<PluginKvGet>(params)
                kv.get(request.key) ?: JsonNull
            }
            "kv.set" -> {
                val request = json.decodeFromJsonElement<PluginKvSet>(params)
                kv.set(request.key, request.value)
                buildJsonObject { put("stored", true) }
            }
            "kv.delete" -> {
                val request = json.decodeFromJsonElement<PluginKvDelete>(params)
                buildJsonObject { put("deleted", kv.delete(request.key)) }
            }
            "kv.keys" -> json.encodeToJsonElement(kv.keys())
            "settings.get" -> {
                val request = json.decodeFromJsonElement<PluginSettingGet>(params)
                settingsReader.read(pluginId, request.id)?.let(::JsonPrimitive) ?: JsonNull
            }
            "cookie.list" -> json.encodeToJsonElement(cookieJar.snapshot())
            "cookie.set" -> {
                val request = json.decodeFromJsonElement<PluginCookieSet>(params)
                cookieJar.set(request)
                buildJsonObject { put("stored", true) }
            }
            "cookie.clear" -> {
                cookieJar.clear()
                buildJsonObject { put("cleared", true) }
            }
            "clock.now" -> buildJsonObject { put("epochMs", clockMs()) }
            "clock.sleep" -> {
                val duration =
                    params.jsonObject["durationMs"]?.jsonPrimitive?.longOrNull
                        ?: throw hostError("clock.sleep requires durationMs")
                if (duration < 0L || duration > PluginRuntimePolicy.HARD_DEADLINE_MS) {
                    throw quotaError("clock.sleep duration is outside the host limit")
                }
                delay(duration)
                buildJsonObject { put("slept", duration) }
            }
            "log" -> {
                val entry = json.decodeFromJsonElement<PluginLogEntry>(params)
                logger.append(entry)
                buildJsonObject { put("logged", true) }
            }
            else ->
                throw PluginRpcException(
                    PluginRpcError(PluginErrorCode.PLUGIN_PROTOCOL, "Unknown host method: $method")
                )
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            bodyHandles.clear()
            if (httpClientDelegate.isInitialized()) {
                httpClient.dispatcher.cancelAll()
                httpClient.dispatcher.executorService.shutdownNow()
                httpClient.connectionPool.evictAll()
            }
        }
    }

    private suspend fun httpRequest(request: PluginHttpRequest): PluginHttpResponse {
        if (closed.get()) throw hostError("Plugin host session is closed")
        val url = request.url.toHttpUrlOrNull() ?: throw invalidArgumentError("Invalid HTTP URL")
        if (url.scheme != "http" && url.scheme != "https") {
            throw invalidArgumentError("Only http and https URLs are allowed")
        }
        if (
            request.method.length !in 1..16 ||
                !request.method.all { it in 'A'..'Z' || it in 'a'..'z' }
        ) {
            throw invalidArgumentError("Invalid HTTP method")
        }
        if (!validHttpHeaders(request.headers)) throw invalidArgumentError("Invalid HTTP headers")
        val body =
            request.bodyBase64?.let {
                try {
                    Base64.getDecoder().decode(it)
                } catch (error: IllegalArgumentException) {
                    throw invalidArgumentError("bodyBase64 is invalid", error)
                }
            }
        if (body != null && body.size > PluginRuntimePolicy.MAX_HTTP_RESPONSE_BYTES) {
            throw quotaError("HTTP request body exceeds the host limit")
        }
        val builder = Request.Builder().url(url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val method = request.method.uppercase(Locale.US)
        val contentType =
            request.headers.entries
                .firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
                ?.value
        val requestBody =
            when {
                body != null -> body.toRequestBody(contentType)
                method in METHODS_REQUIRING_BODY -> ByteArray(0).toRequestBody(contentType)
                else -> null
            }
        try {
            builder.method(method, requestBody)
        } catch (error: IllegalArgumentException) {
            throw invalidArgumentError(
                error.message ?: "Invalid HTTP method/body combination",
                error,
            )
        }
        return globalHttpSemaphore.withPermit {
            httpSemaphore.withPermit {
                val client =
                    synchronized(lifecycleLock) {
                        if (closed.get()) throw hostError("Plugin host session is closed")
                        httpClientDelegate.value
                    }
                executeHttp(client, builder.build())
            }
        }
    }

    private suspend fun executeHttp(client: OkHttpClient, request: Request): PluginHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            try {
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    PluginRpcException(
                                        PluginRpcError(
                                            PluginErrorCode.NETWORK,
                                            e.message ?: "HTTP request failed",
                                            retryable = true,
                                        ),
                                        e,
                                    )
                                )
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val result = response.use { readResponse(it) }
                                if (continuation.isActive) continuation.resume(result)
                            } catch (error: Throwable) {
                                if (continuation.isActive) {
                                    val mapped =
                                        if (error is PluginRpcException) {
                                            error
                                        } else {
                                            PluginRpcException(
                                                PluginRpcError(
                                                    PluginErrorCode.NETWORK,
                                                    error.message ?: "HTTP request failed",
                                                    retryable = true,
                                                ),
                                                error,
                                            )
                                        }
                                    continuation.resumeWithException(mapped)
                                }
                            }
                        }
                    }
                )
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        PluginRpcException(
                            PluginRpcError(
                                PluginErrorCode.HOST_UNAVAILABLE,
                                "HTTP client is closed",
                                retryable = true,
                            ),
                            error,
                        )
                    )
                }
            }
        }

    private fun readResponse(response: Response): PluginHttpResponse {
        val responseBody = response.body
        val bytes =
            responseBody?.readBounded(PluginRuntimePolicy.MAX_HTTP_RESPONSE_BYTES.toLong())
                ?: ByteArray(0)
        val headers =
            response.headers.toMultimap().mapValues { (_, values) -> values.joinToString(",") }
        if (bytes.size <= HTTP_INLINE_BODY_BYTES) {
            return PluginHttpResponse(
                statusCode = response.code,
                headers = headers,
                bodyBase64 = Base64.getEncoder().encodeToString(bytes),
                bodySizeBytes = bytes.size.toLong(),
            )
        }
        val handle = "b-${UUID.randomUUID()}"
        bodyHandles[handle] = PluginBodyHandle(pluginId, bytes, clockMs())
        trimBodyHandles()
        return PluginHttpResponse(
            statusCode = response.code,
            headers = headers,
            bodyHandle = handle,
            bodySizeBytes = bytes.size.toLong(),
        )
    }

    private fun readBody(request: PluginHttpRead): PluginHttpChunk {
        val handle =
            bodyHandles[request.handle]
                ?: throw PluginRpcException(
                    PluginRpcError(PluginErrorCode.NOT_FOUND, "HTTP body handle not found")
                )
        if (handle.pluginId != pluginId) {
            throw PluginRpcException(
                PluginRpcError(PluginErrorCode.NOT_FOUND, "HTTP body handle not found")
            )
        }
        if (request.offset < 0L || request.offset > handle.body.size) {
            throw invalidArgumentError("HTTP body offset is invalid")
        }
        val size = request.maxBytes.coerceIn(1, HTTP_CHUNK_BYTES)
        val start = request.offset.toInt()
        val end = (start + size).coerceAtMost(handle.body.size)
        val chunk = handle.body.copyOfRange(start, end)
        return PluginHttpChunk(
            handle = request.handle,
            offset = end.toLong(),
            bodyBase64 = Base64.getEncoder().encodeToString(chunk),
            eof = end == handle.body.size,
        )
    }

    private fun trimBodyHandles() {
        val cutoff = clockMs() - 5L * 60L * 1000L
        bodyHandles.entries.removeIf { it.value.createdAtMs < cutoff }
        if (bodyHandles.size <= 16) return
        bodyHandles.entries
            .sortedBy { it.value.createdAtMs }
            .take(bodyHandles.size - 16)
            .forEach { bodyHandles.remove(it.key) }
    }

    private fun invalidArgumentError(message: String, cause: Throwable? = null) =
        PluginRpcException(PluginRpcError(PluginErrorCode.INVALID_ARGUMENT, message), cause)

    private fun quotaError(message: String) =
        PluginRpcException(PluginRpcError(PluginErrorCode.QUOTA_EXCEEDED, message))

    private fun hostError(message: String) =
        PluginRpcException(
            PluginRpcError(PluginErrorCode.HOST_UNAVAILABLE, message, retryable = true)
        )

    private fun validHttpHeaders(headers: Map<String, String>): Boolean =
        headers.size <= 64 &&
            headers.keys.all {
                it.isNotBlank() && it.length <= 256 && HEADER_NAME_PATTERN.matches(it)
            } &&
            headers.values.all { value ->
                value.length <= 16 * 1024 && value.all { it == '\t' || it in '\u0020'..'\u007e' }
            }

    private companion object {
        const val HTTP_INLINE_BODY_BYTES = 512 * 1024
        const val HTTP_CHUNK_BYTES = 384 * 1024
        val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
        val DEFAULT_GLOBAL_HTTP_SEMAPHORE =
            Semaphore(PluginRuntimePolicy.MAX_GLOBAL_HTTP_CONCURRENCY)
        val HEADER_NAME_PATTERN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        val defaultJson = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}

private fun ByteArray.toRequestBody(contentType: String?) =
    toRequestBody(contentType?.toMediaTypeOrNull())

private fun ResponseBody.readBounded(maxBytes: Long): ByteArray {
    val source = source()
    val output = Buffer()
    var total = 0L
    while (true) {
        val read = source.read(output, 64 * 1024L)
        if (read < 0L) break
        total += read
        if (total > maxBytes) {
            throw PluginRpcException(
                PluginRpcError(
                    PluginErrorCode.QUOTA_EXCEEDED,
                    "HTTP response exceeds the host limit",
                )
            )
        }
    }
    return output.readByteArray()
}

@Serializable
private data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
    val persistent: Boolean = true,
)

private class PersistentPluginCookieJar(
    private val file: File,
    private val json: Json,
) : CookieJar {
    private val lock = Any()
    private val cookies = mutableListOf<StoredCookie>()

    init {
        synchronized(lock) {
            if (file.isFile) {
                cookies +=
                    try {
                        json.decodeFromString<List<StoredCookie>>(
                            file.readText(StandardCharsets.UTF_8)
                        )
                    } catch (error: Throwable) {
                        throw PluginRpcException(
                            PluginRpcError(
                                PluginErrorCode.HOST_UNAVAILABLE,
                                "Cookie state is corrupt",
                            ),
                            error,
                        )
                    }
            }
            removeExpired(System.currentTimeMillis())
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        synchronized(lock) {
            removeExpired(System.currentTimeMillis())
            cookies.mapNotNull { it.toCookie()?.takeIf { cookie -> cookie.matches(url) } }
        }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) =
        synchronized(lock) {
            cookies.forEach { cookie ->
                this.cookies.removeIf {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                if (cookie.expiresAt >= System.currentTimeMillis()) {
                    this.cookies += cookie.toStored()
                }
            }
            persist()
        }

    fun snapshot(): List<Map<String, String>> =
        synchronized(lock) {
            removeExpired(System.currentTimeMillis())
            cookies.map {
                mapOf(
                    "name" to it.name,
                    "domain" to it.domain,
                    "path" to it.path,
                    "value" to it.value,
                )
            }
        }

    fun clear() =
        synchronized(lock) {
            cookies.clear()
            persist()
        }

    fun set(request: PluginCookieSet) =
        synchronized(lock) {
            val url =
                request.url.toHttpUrlOrNull()
                    ?: throw PluginRpcException(
                        PluginRpcError(PluginErrorCode.INVALID_ARGUMENT, "Invalid cookie URL")
                    )
            val cookie =
                Cookie.parse(url, request.setCookie)
                    ?: throw PluginRpcException(
                        PluginRpcError(PluginErrorCode.INVALID_ARGUMENT, "Invalid Set-Cookie value")
                    )
            saveFromResponse(url, listOf(cookie))
        }

    private fun removeExpired(now: Long) {
        cookies.removeIf { it.expiresAt != Long.MAX_VALUE && it.expiresAt <= now }
    }

    private fun persist() {
        writeAtomically(file, json.encodeToString(cookies))
    }
}

private fun StoredCookie.toCookie(): Cookie? =
    runCatching {
            Cookie.Builder()
                .name(name)
                .value(value)
                .apply { if (hostOnly) hostOnlyDomain(domain) else domain(domain) }
                .path(path)
                .apply { if (persistent) expiresAt(expiresAt) }
                .apply {
                    if (secure) secure()
                    if (httpOnly) httpOnly()
                }
                .build()
        }
        .getOrNull()

private fun Cookie.toStored() =
    StoredCookie(
        name = name,
        value = value,
        domain = domain,
        path = path,
        expiresAt = expiresAt,
        secure = secure,
        httpOnly = httpOnly,
        hostOnly = hostOnly,
        persistent = persistent,
    )

private class PluginKvStore(
    private val file: File,
    private val json: Json,
) {
    private val lock = Any()

    fun get(key: String): JsonElement? =
        synchronized(lock) {
            validateKey(key)
            read()[key]
        }

    fun set(key: String, value: JsonElement) =
        synchronized(lock) {
            validateKey(key)
            val valueBytes =
                json
                    .encodeToString(JsonElement.serializer(), value)
                    .toByteArray(StandardCharsets.UTF_8)
                    .size
            if (valueBytes > PluginRuntimePolicy.MAX_KV_VALUE_BYTES) {
                throw PluginRpcException(
                    PluginRpcError(
                        PluginErrorCode.QUOTA_EXCEEDED,
                        "KV value exceeds the host limit",
                    )
                )
            }
            val next = read().toMutableMap()
            next[key] = value
            val encoded = json.encodeToString(JsonElement.serializer(), JsonObject(next))
            if (
                encoded.toByteArray(StandardCharsets.UTF_8).size >
                    PluginRuntimePolicy.MAX_KV_NAMESPACE_BYTES
            ) {
                throw PluginRpcException(
                    PluginRpcError(
                        PluginErrorCode.QUOTA_EXCEEDED,
                        "KV namespace exceeds the host limit",
                    )
                )
            }
            write(encoded)
        }

    fun delete(key: String): Boolean =
        synchronized(lock) {
            validateKey(key)
            val next = read().toMutableMap()
            val removed = next.remove(key) != null
            if (removed) write(json.encodeToString(JsonElement.serializer(), JsonObject(next)))
            removed
        }

    fun keys(): List<String> = synchronized(lock) { read().keys.sorted() }

    private fun read(): JsonObject {
        if (!file.isFile) return JsonObject(emptyMap())
        return runCatching {
                json.parseToJsonElement(file.readText(StandardCharsets.UTF_8)).jsonObject
            }
            .getOrElse {
                throw PluginRpcException(
                    PluginRpcError(PluginErrorCode.HOST_UNAVAILABLE, "KV state is corrupt"),
                    it,
                )
            }
    }

    private fun write(value: String) {
        writeAtomically(file, value)
    }

    private fun validateKey(key: String) {
        if (key.isBlank() || key.length > 256) {
            throw PluginRpcException(
                PluginRpcError(PluginErrorCode.INVALID_ARGUMENT, "Invalid KV key")
            )
        }
    }
}

interface PluginLogger {
    fun append(entry: PluginLogEntry)
}

private class FilePluginLogger(
    private val pluginId: String,
    private val directory: File,
    private val json: Json,
    private val clockMs: () -> Long,
) : PluginLogger {
    private val lock = Any()
    private val file
        get() = directory.resolve("events.jsonl")

    private var entryCount = if (file.isFile) file.useLines { lines -> lines.count() } else 0

    override fun append(entry: PluginLogEntry) =
        synchronized(lock) {
            val safeFields = entry.fields.mapValues { (key, value) -> redact(key, value) }
            val line =
                json.encodeToString(
                    buildJsonObject {
                        put("pluginId", pluginId)
                        put("timestampMs", clockMs())
                        put("level", entry.level.take(16))
                        put("message", entry.message.take(16 * 1024))
                        put("fields", json.encodeToJsonElement(safeFields))
                    }
                ) + "\n"
            directory.mkdirs()
            file.appendText(line, StandardCharsets.UTF_8)
            entryCount++
            trim()
        }

    private fun trim() {
        if (!file.isFile) return
        if (entryCount <= MAX_LOG_ENTRIES && file.length() <= MAX_LOG_BYTES) return
        val lines = file.readLines(StandardCharsets.UTF_8)
        var bytes = 0L
        val retained = ArrayDeque<String>()
        lines.asReversed().forEach { line ->
            val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size + 1L
            if (retained.size >= MAX_LOG_ENTRIES || bytes + lineBytes > MAX_LOG_BYTES)
                return@forEach
            retained.addFirst(line)
            bytes += lineBytes
        }
        writeAtomically(file, retained.joinToString("\n", postfix = "\n"))
        entryCount = retained.size
    }

    private fun redact(key: String, value: String): String {
        val sensitive =
            listOf("authorization", "cookie", "set-cookie", "password", "token", "secret")
        return if (sensitive.any { key.contains(it, ignoreCase = true) }) "[REDACTED]"
        else value.take(16 * 1024)
    }

    private companion object {
        const val MAX_LOG_ENTRIES = 2_000
        const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    }
}

private fun writeAtomically(file: File, value: String) {
    file.parentFile?.let { parent ->
        if (!parent.mkdirs() && !parent.isDirectory)
            throw IOException("Unable to create ${parent.path}")
    }
    val temp = file.resolveSibling("${file.name}.tmp-${UUID.randomUUID()}")
    try {
        temp.writeText(value, StandardCharsets.UTF_8)
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        if (temp.exists()) temp.delete()
    }
}
