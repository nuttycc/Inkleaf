package com.exio.inkleaf.plugin

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class PluginBrowseCacheKey(
    val pluginId: String,
    val pluginVersion: String,
    val feedId: String,
    val filters: Map<String, String>,
) {
    internal fun stableValue(): String = buildString {
        fun appendPart(value: String) {
            append(value.length).append(':').append(value)
        }
        appendPart(pluginId)
        appendPart(pluginVersion)
        appendPart(feedId)
        filters.toSortedMap().forEach { (name, value) ->
            appendPart(name)
            appendPart(value)
        }
    }
}

data class PluginBrowseCacheSnapshot(
    val page: PluginSearchPage,
    val fetchedAtMs: Long,
    val revision: String,
)

/**
 * Disposable first-page cache for plugin browse feeds.
 *
 * Memory entries make tab/feed switching immediate. Disk entries survive process recreation, while
 * later pages remain owned by the screen-scoped ViewModel.
 */
class PluginBrowseRepository(
    private val cacheDirectory: File,
    private val remoteBrowse: suspend (String, PluginBrowseRequest) -> PluginSearchPage,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val maxMemoryEntries: Int = DEFAULT_MAX_MEMORY_ENTRIES,
    private val maxDiskEntries: Int = DEFAULT_MAX_DISK_ENTRIES,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pluginLocks = Array(LOCK_STRIPE_COUNT) { Mutex() }
    private val memoryLock = Any()
    private val memory =
        object : LinkedHashMap<PluginBrowseCacheKey, PluginBrowseCacheSnapshot>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<PluginBrowseCacheKey, PluginBrowseCacheSnapshot>?
            ): Boolean = size > maxMemoryEntries
        }

    suspend fun readFirstPage(key: PluginBrowseCacheKey): PluginBrowseCacheSnapshot? =
        lockFor(key.pluginId).withLock { withContext(Dispatchers.IO) { readInternal(key) } }

    fun isFresh(snapshot: PluginBrowseCacheSnapshot): Boolean = isFresh(snapshot.fetchedAtMs)

    fun isFresh(fetchedAtMs: Long): Boolean = clockMs() - fetchedAtMs in 0 until ttlMs

    /** Refreshes a first page once for all callers that observed the same cache generation. */
    suspend fun refreshFirstPage(
        key: PluginBrowseCacheKey,
        request: PluginBrowseRequest,
        expectedRevision: String?,
        force: Boolean = false,
    ): PluginBrowseCacheSnapshot {
        require(request.cursor == null) { "Only the first browse page can be cached" }
        require(request.feedId == key.feedId && request.filters == key.filters) {
            "Browse request does not match its cache key"
        }
        return lockFor(key.pluginId).withLock {
            val current = withContext(Dispatchers.IO) { readInternal(key) }
            if (current != null && current.revision != expectedRevision) return@withLock current
            if (!force && current != null && isFresh(current)) return@withLock current

            val refreshed =
                PluginBrowseCacheSnapshot(
                    page = remoteBrowse(key.pluginId, request),
                    fetchedAtMs = clockMs(),
                    revision = UUID.randomUUID().toString(),
                )
            withContext(Dispatchers.IO) { writeInternal(key, refreshed) }
            refreshed
        }
    }

    suspend fun loadPage(pluginId: String, request: PluginBrowseRequest): PluginSearchPage =
        remoteBrowse(pluginId, request)

    /**
     * Removes all browse cache entries for one source.
     *
     * Settings are absent from the cache key, so changing a route or image mode must invalidate
     * entries that could otherwise remain visible for 15 minutes. Disk names are SHA-256 cache-key
     * hashes; reading at most 64 envelopes is acceptable for this infrequent operation.
     */
    suspend fun clear(pluginId: String) {
        lockFor(pluginId).withLock {
            withContext(Dispatchers.IO) {
                synchronized(memoryLock) {
                    memory.keys.filter { it.pluginId == pluginId }.forEach { memory.remove(it) }
                }
                cacheDirectory
                    .listFiles { file -> file.isFile && file.extension == "json" }
                    ?.forEach { file ->
                        val envelope =
                            runCatching {
                                    json.decodeFromString(
                                        BrowseCacheEnvelope.serializer(),
                                        file.readText(StandardCharsets.UTF_8),
                                    )
                                }
                                .getOrNull()
                        // Corrupt cache entries have no value and can be removed at the same time.
                        if (envelope == null || envelope.pluginId == pluginId) file.delete()
                    }
            }
        }
    }

    private fun lockFor(pluginId: String): Mutex =
        pluginLocks[(pluginId.hashCode() and Int.MAX_VALUE) % pluginLocks.size]

    private fun readInternal(key: PluginBrowseCacheKey): PluginBrowseCacheSnapshot? {
        synchronized(memoryLock) { memory[key] }
            ?.let {
                return it
            }
        val file = fileFor(key)
        if (!file.isFile) return null

        val envelope =
            runCatching {
                    json.decodeFromString(
                        BrowseCacheEnvelope.serializer(),
                        file.readText(StandardCharsets.UTF_8),
                    )
                }
                .getOrNull()
        if (
            envelope == null ||
                envelope.schemaVersion != CACHE_SCHEMA_VERSION ||
                !envelope.matches(key)
        ) {
            file.delete()
            return null
        }

        val snapshot =
            PluginBrowseCacheSnapshot(envelope.page, envelope.fetchedAtMs, envelope.revision)
        synchronized(memoryLock) { memory[key] = snapshot }
        return snapshot
    }

    private fun writeInternal(key: PluginBrowseCacheKey, snapshot: PluginBrowseCacheSnapshot) {
        synchronized(memoryLock) { memory[key] = snapshot }
        runCatching {
            if (!cacheDirectory.mkdirs() && !cacheDirectory.isDirectory) {
                throw IOException("Unable to create plugin browse cache directory")
            }
            val envelope =
                BrowseCacheEnvelope(
                    pluginId = key.pluginId,
                    pluginVersion = key.pluginVersion,
                    feedId = key.feedId,
                    filters = key.filters.toSortedMap(),
                    fetchedAtMs = snapshot.fetchedAtMs,
                    revision = snapshot.revision,
                    page = snapshot.page,
                )
            writeAtomically(
                fileFor(key),
                json.encodeToString(BrowseCacheEnvelope.serializer(), envelope),
            )
            pruneDiskCache()
        }
    }

    private fun fileFor(key: PluginBrowseCacheKey): File {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(key.stableValue().toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return cacheDirectory.resolve("$digest.json")
    }

    private fun pruneDiskCache() {
        cacheDirectory
            .listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending(File::lastModified)
            ?.drop(maxDiskEntries)
            ?.forEach(File::delete)
    }

    private fun writeAtomically(file: File, value: String) {
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

    @Serializable
    private data class BrowseCacheEnvelope(
        val schemaVersion: Int = CACHE_SCHEMA_VERSION,
        val pluginId: String,
        val pluginVersion: String,
        val feedId: String,
        val filters: Map<String, String>,
        val fetchedAtMs: Long,
        val revision: String,
        val page: PluginSearchPage,
    ) {
        fun matches(key: PluginBrowseCacheKey): Boolean =
            pluginId == key.pluginId &&
                pluginVersion == key.pluginVersion &&
                feedId == key.feedId &&
                filters == key.filters
    }

    companion object {
        const val DEFAULT_TTL_MS = 15 * 60 * 1_000L
        private const val CACHE_SCHEMA_VERSION = 1
        private const val DEFAULT_MAX_MEMORY_ENTRIES = 32
        private const val DEFAULT_MAX_DISK_ENTRIES = 64
        private const val LOCK_STRIPE_COUNT = 32
    }
}
