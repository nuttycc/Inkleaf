package com.exio.inkleaf.data

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class OnlinePageLoadPriority { FOREGROUND, SPECULATIVE }

/** A secret-free, hashed identity for one online chapter revision. */
internal data class OnlinePageCacheIdentity private constructor(
    val pluginIdHash: String,
    val pluginVersion: String,
    val pluginVersionHash: String,
    val accessScopeHash: String,
    val sourceIdHash: String,
    val chapterIdHash: String,
    val revisionHash: String,
) {
    val readerCacheKeyPrefix: String =
        "online-" +
            ReaderPageCacheKey.sourceRevision(
                    listOf(
                        pluginIdHash,
                        pluginVersionHash,
                        accessScopeHash,
                        sourceIdHash,
                        chapterIdHash,
                        revisionHash,
                    )
                )
                .take(24)

    internal val stableChapterToken: String =
        ReaderPageCacheKey.sourceRevision(
            listOf(
                pluginIdHash,
                pluginVersionHash,
                accessScopeHash,
                sourceIdHash,
                chapterIdHash,
            )
        )

    companion object {
        fun create(
            pluginId: String,
            pluginVersion: String,
            accessScope: String,
            sourceId: String,
            chapterId: String,
            revision: String,
        ): OnlinePageCacheIdentity {
            require(pluginId.isNotBlank()) { "pluginId must not be blank" }
            require(pluginVersion.isNotBlank()) { "pluginVersion must not be blank" }
            require(accessScope.isNotBlank()) { "accessScope must not be blank" }
            require(sourceId.isNotBlank()) { "sourceId must not be blank" }
            require(chapterId.isNotBlank()) { "chapterId must not be blank" }
            require(revision.isNotBlank()) { "revision must not be blank" }
            return OnlinePageCacheIdentity(
                pluginIdHash = hash(pluginId),
                pluginVersion = pluginVersion,
                pluginVersionHash = hash(pluginVersion),
                accessScopeHash = hash(accessScope),
                sourceIdHash = hash(sourceId),
                chapterIdHash = hash(chapterId),
                revisionHash = hash(revision),
            )
        }

        private fun hash(value: String): String =
            ReaderPageCacheKey.sha256Hex(value.toByteArray(StandardCharsets.UTF_8))
    }
}

internal data class OnlinePageCacheKey(
    val chapter: OnlinePageCacheIdentity,
    val pageIdentityHash: String,
) {
    companion object {
        fun create(chapter: OnlinePageCacheIdentity, pageIdentity: String): OnlinePageCacheKey {
            require(pageIdentity.isNotBlank()) { "pageIdentity must not be blank" }
            return OnlinePageCacheKey(
                chapter = chapter,
                pageIdentityHash =
                    ReaderPageCacheKey.sha256Hex(
                        pageIdentity.toByteArray(StandardCharsets.UTF_8)
                    ),
            )
        }
    }
}

/**
 * Application-owned online page cache. It keeps fetch coordinates in memory and persists only
 * original response bytes plus a secret-free identity manifest.
 */
internal class OnlinePageCache(
    internal val rootDirectory: File,
    private val maxPageBytes: Long = DEFAULT_MAX_PAGE_BYTES,
    private val onStorageChanged: suspend () -> Unit = {},
) {
    private val pagesDirectory = File(rootDirectory, PAGES_DIR)
    private val manifestsDirectory = File(rootDirectory, MANIFESTS_DIR)
    private val clearPendingMarker =
        File(rootDirectory.absoluteFile.parentFile, ".${rootDirectory.name}$CLEAR_PENDING_SUFFIX")
    private val foregroundDownloads = Semaphore(MAX_FOREGROUND_DOWNLOADS)
    private val speculativeDownloads = Semaphore(MAX_SPECULATIVE_DOWNLOADS)
    private val flightScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flightLock = Any()
    private val flights = mutableMapOf<String, Flight>()
    private val commitLock = Any()
    private var generation = 0L
    private val invalidatedPages = HashSet<String>()
    private val protectionLock = Any()
    private val protections = mutableMapOf<String, Protection>()
    suspend fun getOrLoad(
        key: OnlinePageCacheKey,
        priority: OnlinePageLoadPriority,
        loader: suspend () -> ByteArray,
    ): ByteArray {
        val flightId = pageFile(key).absolutePath
        val expectedGeneration = synchronized(commitLock) { generation }
        val candidate = Flight(priority)
        candidate.job =
            flightScope.async(start = CoroutineStart.LAZY) {
                runFlight(candidate, key, expectedGeneration, loader)
            }
        candidate.job.invokeOnCompletion {
            synchronized(flightLock) {
                if (flights[flightId] === candidate) flights.remove(flightId)
            }
        }
        val flight =
            synchronized(flightLock) {
                val existing = flights[flightId]
                if (existing != null) {
                    existing.register(priority)
                    existing
                } else {
                    candidate.register(priority)
                    flights[flightId] = candidate
                    candidate
                }
            }
        if (flight === candidate) candidate.job.start() else candidate.job.cancel()
        try {
            return flight.job.await()
        } finally {
            releaseWaiter(flightId, flight, priority)
        }
    }

    private suspend fun runFlight(
        flight: Flight,
        key: OnlinePageCacheKey,
        expectedGeneration: Long,
        loader: suspend () -> ByteArray,
    ): ByteArray {
        readPage(key)?.let { return it }
        val effectivePriority =
            synchronized(flightLock) {
                if (flight.foregroundWaiters > 0) {
                    OnlinePageLoadPriority.FOREGROUND
                } else {
                    flight.initialPriority
                }
            }
        val semaphore =
            if (effectivePriority == OnlinePageLoadPriority.FOREGROUND) {
                foregroundDownloads
            } else {
                speculativeDownloads
            }
        semaphore.acquire()
        val bytes =
            try {
                loader()
            } finally {
                semaphore.release()
            }
        if (bytes.isNotEmpty() && bytes.size.toLong() <= maxPageBytes) {
            try {
                writePage(key, bytes, expectedGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A disk failure must not discard bytes already returned by the source.
            }
        }
        return bytes
    }

    private fun releaseWaiter(
        flightId: String,
        flight: Flight,
        priority: OnlinePageLoadPriority,
    ) {
        val jobToCancel =
            synchronized(flightLock) {
                flight.waiters -= 1
                if (priority == OnlinePageLoadPriority.FOREGROUND) {
                    flight.foregroundWaiters -= 1
                }
                if (flight.waiters == 0 && !flight.job.isCompleted) {
                    if (flights[flightId] === flight) flights.remove(flightId)
                    flight.job
                } else {
                    null
                }
            }
        jobToCancel?.cancel()
    }

    fun invalidate(key: OnlinePageCacheKey) {
        synchronized(invalidatedPages) { invalidatedPages += pageFile(key).absolutePath }
    }

    fun protectChapter(identity: OnlinePageCacheIdentity) {
        synchronized(protectionLock) {
            val token = protectionToken(identity)
            val existing = protections[token]
            protections[token] =
                if (existing == null) {
                    Protection(
                        pageRoot = revisionDirectory(pagesDirectory, identity).absolutePath,
                        manifestRoot = revisionDirectory(manifestsDirectory, identity).absolutePath,
                        thumbnailDirectoryName =
                            "online-" +
                                ReaderPageCacheKey.sha256Hex(
                                    identity.readerCacheKeyPrefix.toByteArray(StandardCharsets.UTF_8)
                                ),
                        count = 1,
                    )
                } else {
                    existing.copy(count = existing.count + 1)
                }
        }
    }

    fun releaseChapter(identity: OnlinePageCacheIdentity) {
        synchronized(protectionLock) {
            val token = protectionToken(identity)
            val existing = protections[token] ?: return
            if (existing.count <= 1) protections.remove(token)
            else protections[token] = existing.copy(count = existing.count - 1)
        }
    }

    fun isProtected(file: File): Boolean {
        val path = file.absolutePath
        return synchronized(protectionLock) {
            protections.values.any { protection ->
                path.isInside(protection.pageRoot) || path.isInside(protection.manifestRoot)
            }
        }
    }

    fun isProtectedOnlineThumbnail(file: File): Boolean =
        synchronized(protectionLock) {
            protections.values.any { it.thumbnailDirectoryName == file.parentFile?.name }
        }

    suspend fun writeManifest(
        identity: OnlinePageCacheIdentity,
        pageIdentities: List<String>,
        fetchedAtMs: Long,
    ) {
        val expectedGeneration = synchronized(commitLock) { generation }
        val manifest =
            OnlinePageManifest(
                pluginVersion = identity.pluginVersion,
                accessScopeHash = identity.accessScopeHash,
                sourceIdHash = identity.sourceIdHash,
                chapterIdHash = identity.chapterIdHash,
                revisionHash = identity.revisionHash,
                pageIdentityHashes =
                    pageIdentities.map { pageIdentity ->
                        ReaderPageCacheKey.sha256Hex(
                            pageIdentity.toByteArray(StandardCharsets.UTF_8)
                        )
                    },
                pageCount = pageIdentities.size,
                fetchedAtMs = fetchedAtMs,
            )
        withContext(Dispatchers.IO) {
            runCatching {
                val destination = manifestFile(identity)
                destination.parentFile?.mkdirs()
                val temporary = temporaryFile(destination)
                try {
                    temporary.writeText(
                        MANIFEST_JSON.encodeToString(manifest),
                        StandardCharsets.UTF_8,
                    )
                    if (commitTemporary(temporary, destination, expectedGeneration)) {
                        destination.setLastModified(fetchedAtMs)
                        enforceManifestBudget()
                    }
                } finally {
                    temporary.delete()
                }
            }
        }
        notifyStorageChanged()
    }

    suspend fun clear() {
        val speculativeOwners =
            synchronized(flightLock) {
                flights
                    .filterValues { it.foregroundWaiters == 0 }
                    .toList()
                    .onEach { (flightId, flight) ->
                        if (flights[flightId] === flight) flights.remove(flightId)
                    }
                    .map { it.second.job }
                    .distinct()
            }
        speculativeOwners.forEach { it.cancel() }
        val cleared = withContext(Dispatchers.IO) { clearRoot() }
        if (!cleared) {
            throw IOException("Unable to remove all online page cache files")
        }
        synchronized(invalidatedPages) { invalidatedPages.clear() }
    }

    fun cleanupOnColdStart(staleBeforeMs: Long) {
        if (clearPendingMarker.exists() && !clearRoot()) {
            Log.w(
                TAG,
                "Unable to complete deferred online cache clear; " +
                    "the pending marker keeps the online cache disabled until the next cold start",
            )
        }
        rootDirectory
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(TEMP_SUFFIX) && it.lastModified() < staleBeforeMs }
            .forEach(File::delete)
    }

    internal fun pageFile(key: OnlinePageCacheKey): File =
        File(revisionDirectory(pagesDirectory, key.chapter), "${key.pageIdentityHash}$PAGE_SUFFIX")

    internal fun pageFiles(): Sequence<File> =
        pagesDirectory.walkTopDown().filter { it.isFile && it.name.endsWith(PAGE_SUFFIX) }

    internal fun manifestFiles(): Sequence<File> =
        manifestsDirectory.walkTopDown().filter { it.isFile && it.name == MANIFEST_FILE }

    private suspend fun readPage(key: OnlinePageCacheKey): ByteArray? =
        withContext(Dispatchers.IO) {
            if (clearPendingMarker.exists()) return@withContext null
            val file = pageFile(key)
            val invalidated =
                synchronized(invalidatedPages) { invalidatedPages.remove(file.absolutePath) }
            if (invalidated) {
                if (file.exists() && !file.delete()) {
                    synchronized(invalidatedPages) { invalidatedPages += file.absolutePath }
                }
                return@withContext null
            }
            if (!file.isFile || file.length() !in 1..maxPageBytes) {
                if (file.exists()) file.delete()
                return@withContext null
            }
            runCatching { file.readBytes() }
                .onSuccess { file.setLastModified(System.currentTimeMillis()) }
                .getOrElse {
                    file.delete()
                    null
                }
        }

    private suspend fun writePage(
        key: OnlinePageCacheKey,
        bytes: ByteArray,
        expectedGeneration: Long,
    ) {
        val destination = pageFile(key)
        val committed =
            withContext(Dispatchers.IO) {
                destination.parentFile?.mkdirs()
                val temporary = temporaryFile(destination)
                try {
                    temporary.outputStream().buffered().use { output ->
                        output.write(bytes)
                        output.flush()
                    }
                    commitTemporary(temporary, destination, expectedGeneration)
                } finally {
                    temporary.delete()
                }
            }
        if (committed) {
            synchronized(invalidatedPages) { invalidatedPages.remove(destination.absolutePath) }
            notifyStorageChanged()
        }
    }

    private suspend fun notifyStorageChanged() {
        try {
            onStorageChanged()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Cache accounting is best-effort and cannot invalidate successfully loaded bytes.
        }
    }

    private fun commitTemporary(
        temporary: File,
        destination: File,
        expectedGeneration: Long,
    ): Boolean =
        synchronized(commitLock) {
            if (generation != expectedGeneration || clearPendingMarker.exists()) {
                return@synchronized false
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            destination.setLastModified(System.currentTimeMillis())
            true
        }

    private fun clearRoot(): Boolean =
        synchronized(commitLock) {
            clearPendingMarker.parentFile?.mkdirs()
            if (!clearPendingMarker.exists() &&
                !runCatching(clearPendingMarker::createNewFile).getOrDefault(false)
            ) {
                return@synchronized false
            }
            generation += 1L
            if (!runCatching(rootDirectory::deleteRecursively).getOrDefault(false)) {
                return@synchronized false
            }
            !clearPendingMarker.exists() || clearPendingMarker.delete()
        }

    private fun enforceManifestBudget() {
        val manifests = manifestFiles().sortedBy(File::lastModified).toList()
        var total = manifests.sumOf(File::length)
        for (manifest in manifests) {
            if (total <= MANIFESTS_MAX_BYTES) break
            if (isProtected(manifest)) continue
            val length = manifest.length()
            if (manifest.delete()) total -= length
            deleteEmptyParents(manifest.parentFile, manifestsDirectory)
        }
    }

    private fun revisionDirectory(base: File, identity: OnlinePageCacheIdentity): File =
        listOf(
                identity.pluginIdHash,
                identity.pluginVersionHash,
                identity.accessScopeHash,
                identity.sourceIdHash,
                identity.chapterIdHash,
                identity.revisionHash,
            )
            .fold(base) { directory, segment -> File(directory, segment) }

    private fun manifestFile(identity: OnlinePageCacheIdentity): File =
        File(revisionDirectory(manifestsDirectory, identity), MANIFEST_FILE)

    private fun protectionToken(identity: OnlinePageCacheIdentity): String =
        "${identity.stableChapterToken}:${identity.revisionHash}"

    private fun temporaryFile(destination: File): File =
        File(destination.parentFile, "${destination.name}.${UUID.randomUUID()}$TEMP_SUFFIX")

    private fun String.isInside(root: String): Boolean =
        this == root || startsWith(root + File.separator)

    private class Flight(val initialPriority: OnlinePageLoadPriority) {
        lateinit var job: Deferred<ByteArray>
        var waiters: Int = 0
        var foregroundWaiters: Int = 0

        fun register(priority: OnlinePageLoadPriority) {
            waiters += 1
            if (priority == OnlinePageLoadPriority.FOREGROUND) foregroundWaiters += 1
        }
    }

    private data class Protection(
        val pageRoot: String,
        val manifestRoot: String,
        val thumbnailDirectoryName: String,
        val count: Int,
    )

    @Serializable
    private data class OnlinePageManifest(
        val schemaVersion: Int = MANIFEST_SCHEMA_VERSION,
        val pluginVersion: String,
        val accessScopeHash: String,
        val sourceIdHash: String,
        val chapterIdHash: String,
        val revisionHash: String,
        val pageIdentityHashes: List<String>,
        val pageCount: Int,
        val fetchedAtMs: Long,
    )

    private companion object {
        const val TAG = "OnlinePageCache"
        const val MAX_FOREGROUND_DOWNLOADS = 1
        const val MAX_SPECULATIVE_DOWNLOADS = 2
        const val PAGES_DIR = "pages"
        const val MANIFESTS_DIR = "manifests"
        const val MANIFEST_FILE = "manifest.json"
        const val PAGE_SUFFIX = ".page"
        const val TEMP_SUFFIX = ".tmp"
        const val CLEAR_PENDING_SUFFIX = ".clear-pending"
        const val MANIFEST_SCHEMA_VERSION = 1
        const val DEFAULT_MAX_PAGE_BYTES = 32L * 1024L * 1024L
        const val MANIFESTS_MAX_BYTES = 16L * 1024L * 1024L
        val MANIFEST_JSON = Json { encodeDefaults = true }
    }
}
