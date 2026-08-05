package com.exio.inkleaf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.exio.inkleaf.replaceFileAtomically
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Owns all rebuildable reader files under cacheDir: local working copies, online page bytes,
 * identity manifests, and reader thumbnails. User records and saved online snapshots live elsewhere.
 */
object ReaderCache {
    private const val BOOKS_DIR = "books"
    private const val THUMBS_DIR = "thumbs"
    private const val ONLINE_PAGES_DIR = "online-pages"
    private const val ONLINE_THUMBS_MAX_BYTES = 64L * 1024L * 1024L
    private val onlineThumbnailWriteMutex = Mutex()
    private val onlineThumbnailGeneration = AtomicLong()
    private val onlinePageCacheLock = Any()
    private val budgetEnforcementMutex = Mutex()
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val budgetRequests = Channel<Context>(Channel.CONFLATED)
    @Volatile private var onlinePageCacheInstance: OnlinePageCache? = null

    init {
        maintenanceScope.launch {
            for (context in budgetRequests) {
                try {
                    enforceBudget(context, keep = null)
                    // The dedicated online-thumbnail cap stays enforced through the conflated
                    // pipeline instead of the thumbnail write path, so eviction never runs
                    // while onlineThumbnailWriteMutex is held.
                    enforceOnlineThumbnailBudget(context)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.w(error, "Cache budget enforcement failed")
                }
            }
        }
    }

    internal data class Usage(
        val localCopiesBytes: Long,
        val onlinePagesBytes: Long,
        val manifestsBytes: Long,
        val localThumbnailsBytes: Long,
        val onlineThumbnailsBytes: Long,
    ) {
        val onlineBodyBytes: Long
            get() = onlinePagesBytes + manifestsBytes

        val thumbnailsBytes: Long
            get() = localThumbnailsBytes + onlineThumbnailsBytes

        val totalBytes: Long
            get() = localCopiesBytes + onlineBodyBytes + thumbnailsBytes

        val reclaimableOnlineBytes: Long
            get() = onlineBodyBytes + onlineThumbnailsBytes
    }

    /** 旧版固定文件名副本，升级后冷启动时清一次 */
    private const val LEGACY_CACHE_FILE = "current_comic.zip"

    /** 半成品 .tmp 的保留期：太新的可能正被别的协程写入，不碰 */
    private const val TMP_STALE_MS = 60 * 60 * 1000L

    internal fun onlinePageCache(context: Context): OnlinePageCache {
        onlinePageCacheInstance?.let { return it }
        return synchronized(onlinePageCacheLock) {
            onlinePageCacheInstance
                ?: OnlinePageCache(
                        rootDirectory =
                            File(context.applicationContext.cacheDir, ONLINE_PAGES_DIR),
                        onStorageChanged = {
                            scheduleBudgetEnforcement(context.applicationContext)
                        },
                    )
                    .also { onlinePageCacheInstance = it }
        }
    }

    fun booksDir(context: Context): File = File(context.cacheDir, BOOKS_DIR).apply { mkdirs() }

    /** 某本书的 zip 副本文件。文件名内嵌原文件的"大小+修改时间"作为 校验键：原文件被替换后键变化，旧副本自然失配（由 wipeBook 清走）， 不需要额外的元数据文件 */
    fun cachedZipFile(context: Context, comicId: Long, sourceSize: Long, sourceMtime: Long): File =
        File(booksDir(context), "$comicId-$sourceSize-$sourceMtime.zip")

    fun thumbsDir(context: Context, comicId: Long): File =
        File(File(context.cacheDir, THUMBS_DIR), comicId.toString())

    private fun thumbFile(
        context: Context,
        comicId: Long,
        page: Int,
        pageIdentity: String?,
    ): File =
        File(
            thumbsDir(context, comicId),
            ReaderPageCacheKey.thumbnailFileName(page, pageIdentity),
        )

    /** 读某页缩略图的磁盘缓存；从未落盘过返回 null。RGB_565：缩略图无透明需求、内存减半 */
    suspend fun readThumbnail(
        context: Context,
        comicId: Long,
        page: Int,
        pageIdentity: String? = null,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            val file = thumbFile(context, comicId, page, pageIdentity)
            if (file.exists()) {
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    },
                )
            } else {
                null
            }
        }

    /** 缩略图落盘供下次开书复用。失败无所谓（磁盘满等）：缓存只是加速，下次重新解码 */
    suspend fun writeThumbnail(
        context: Context,
        comicId: Long,
        page: Int,
        pageIdentity: String? = null,
        bitmap: Bitmap,
    ) {
        withContext(Dispatchers.IO) {
            val stored =
                runCatching {
                    val file = thumbFile(context, comicId, page, pageIdentity)
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                    }
                }
                    .getOrDefault(false)
            if (stored) scheduleBudgetEnforcement(context)
        }
    }

    private fun onlineThumbFile(
        context: Context,
        namespace: String,
        page: Int,
        pageIdentity: String,
    ): File {
        val namespaceToken =
            ReaderPageCacheKey.sha256Hex(namespace.toByteArray(Charsets.UTF_8))
        return File(
            File(File(context.cacheDir, THUMBS_DIR), "online-$namespaceToken"),
            ReaderPageCacheKey.thumbnailFileName(page, pageIdentity),
        )
    }

    suspend fun readOnlineThumbnail(
        context: Context,
        namespace: String,
        page: Int,
        pageIdentity: String,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            val file = onlineThumbFile(context, namespace, page, pageIdentity)
            if (!file.exists()) return@withContext null
            val decoded =
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 },
                )
            if (decoded == null) {
                file.delete()
            } else {
                file.setLastModified(System.currentTimeMillis())
            }
            decoded
        }

    suspend fun writeOnlineThumbnail(
        context: Context,
        namespace: String,
        page: Int,
        pageIdentity: String,
        bitmap: Bitmap,
    ) {
        val generation = onlineThumbnailGeneration.get()
        withContext(Dispatchers.IO) {
            val stored =
                onlineThumbnailWriteMutex.withLock {
                    if (generation != onlineThumbnailGeneration.get()) return@withLock false
                    val stored =
                        runCatching {
                            val destination =
                                onlineThumbFile(context, namespace, page, pageIdentity)
                            destination.parentFile?.mkdirs()
                            val temporary =
                                File(
                                    destination.parentFile,
                                    "${destination.name}.${UUID.randomUUID()}.tmp",
                                )
                            try {
                                temporary.outputStream().use { output ->
                                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
                                    output.flush()
                                }
                                replaceFileAtomically(temporary.toPath(), destination.toPath())
                                destination.setLastModified(System.currentTimeMillis())
                            } finally {
                                temporary.delete()
                            }
                        }.isSuccess
                    stored
                }
            if (stored) scheduleBudgetEnforcement(context)
        }
    }

    private fun enforceOnlineThumbnailBudget(context: Context) {
        val root = File(context.cacheDir, THUMBS_DIR)
        val files =
            root.listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isDirectory && it.name.startsWith("online-") }
                .flatMap { it.listFiles().orEmpty().asSequence() }
                .filter {
                    // .tmp files belong to in-flight writes; eviction must never delete an
                    // active temporary. Stale .tmp leftovers are cleaned on cold start.
                    it.isFile && it.name.endsWith(".jpg")
                }
                .toList()
        var total = files.sumOf(File::length)
        val pageCache = onlinePageCache(context)
        for (file in files.sortedBy(File::lastModified)) {
            if (total <= ONLINE_THUMBS_MAX_BYTES) break
            if (pageCache.isProtectedOnlineThumbnail(file)) continue
            val length = file.length()
            if (file.delete()) total -= length
            file.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
        }
    }

    /** 作废一本书的全部派生缓存：zip 副本（任意校验键）+ 缩略图目录 */
    fun wipeBook(context: Context, comicId: Long) {
        booksDir(context).listFiles()?.forEach { f ->
            if (f.name.startsWith("$comicId-")) f.delete()
        }
        thumbsDir(context, comicId).deleteRecursively()
    }

    internal fun usage(context: Context): Usage {
        val pageCache = onlinePageCache(context)
        val thumbsRoot = File(context.cacheDir, THUMBS_DIR)
        val thumbnailFiles = thumbsRoot.walkTopDown().filter(File::isFile).toList()
        val (onlineThumbnails, localThumbnails) =
            thumbnailFiles.partition { file ->
                runCatching {
                        file.relativeTo(thumbsRoot)
                            .invariantSeparatorsPath
                            .substringBefore('/')
                            .startsWith("online-")
                    }
                    .getOrDefault(false)
            }
        return Usage(
            localCopiesBytes = booksDir(context).listFiles().orEmpty().filter(File::isFile).sumOf(File::length),
            onlinePagesBytes = pageCache.pageFiles().sumOf(File::length),
            manifestsBytes = pageCache.manifestFiles().sumOf(File::length),
            localThumbnailsBytes = localThumbnails.sumOf(File::length),
            onlineThumbnailsBytes = onlineThumbnails.sumOf(File::length),
        )
    }

    /** Evicts the oldest unprotected reader-cache entries until the unified budget is met. */
    suspend fun enforceBudget(context: Context, keep: File?) {
        budgetEnforcementMutex.withLock {
            val budget = CacheSettingsRepository(context).limit.first().bytes(context)
            withContext(Dispatchers.IO) {
                var total = usage(context).totalBytes
                if (total <= budget) return@withContext

                val pageCache = onlinePageCache(context)
                val thumbsRoot = File(context.cacheDir, THUMBS_DIR)
                val keptComicId = keep?.name?.substringBefore('-')?.toLongOrNull()
                val candidates =
                    buildList {
                        booksDir(context)
                            .listFiles()
                            .orEmpty()
                            .filter { it.isFile && it != keep }
                            .forEach { add(EvictionEntry(it, EvictionKind.LOCAL_COPY)) }
                        pageCache.pageFiles()
                            .filterNot(pageCache::isProtected)
                            .forEach { add(EvictionEntry(it, EvictionKind.FILE)) }
                        pageCache.manifestFiles()
                            .filterNot(pageCache::isProtected)
                            .forEach { add(EvictionEntry(it, EvictionKind.FILE)) }
                        thumbsRoot
                            .walkTopDown()
                            .filter(File::isFile)
                            .filterNot { it.name.endsWith(".tmp") }
                            .filterNot { file ->
                                val topDirectory =
                                    runCatching {
                                            file.relativeTo(thumbsRoot)
                                                .invariantSeparatorsPath
                                                .substringBefore('/')
                                        }
                                        .getOrNull()
                                (keptComicId != null && topDirectory == keptComicId.toString()) ||
                                    pageCache.isProtectedOnlineThumbnail(file)
                            }
                            .forEach { add(EvictionEntry(it, EvictionKind.FILE)) }
                    }
                    .sortedBy { it.file.lastModified() }

                for (entry in candidates) {
                    if (total <= budget) break
                    if (!entry.file.exists()) continue
                    val reclaimed =
                        when (entry.kind) {
                            EvictionKind.FILE -> {
                                val length = entry.file.length()
                                if (entry.file.delete()) length else 0L
                            }

                            EvictionKind.LOCAL_COPY -> {
                                val comicId = entry.file.name.substringBefore('-').toLongOrNull()
                                val thumbnailDirectory = comicId?.let { thumbsDir(context, it) }
                                val derivedBefore =
                                    thumbnailDirectory?.let(::directorySize) ?: 0L
                                val length = entry.file.length()
                                if (!entry.file.delete()) 0L
                                else {
                                    thumbnailDirectory?.deleteRecursively()
                                    val derivedAfter =
                                        thumbnailDirectory?.let(::directorySize) ?: 0L
                                    length + (derivedBefore - derivedAfter).coerceAtLeast(0L)
                                }
                            }
                        }
                    total = (total - reclaimed).coerceAtLeast(0L)
                    deleteEmptyParents(entry.file.parentFile, context.cacheDir)
                }
            }
        }
    }

    private fun scheduleBudgetEnforcement(context: Context) {
        budgetRequests.trySend(context.applicationContext)
    }

    suspend fun clearOnlineCache(context: Context): Long {
        val before = withContext(Dispatchers.IO) { usage(context).reclaimableOnlineBytes }
        onlinePageCache(context).clear()
        withContext(Dispatchers.IO) {
            onlineThumbnailWriteMutex.withLock {
                onlineThumbnailGeneration.incrementAndGet()
                val remaining =
                    File(context.cacheDir, THUMBS_DIR)
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory && it.name.startsWith("online-") }
                    .filter { !it.deleteRecursively() && it.exists() }
                if (remaining.isNotEmpty()) {
                    throw IOException("Unable to remove all online reader thumbnails")
                }
            }
        }
        val after = withContext(Dispatchers.IO) { usage(context).reclaimableOnlineBytes }
        return (before - after).coerceAtLeast(0L)
    }

    /** 冷启动清理：旧版固定名副本 + 上次进程被杀留下的过期半成品 */
    fun cleanupOnColdStart(context: Context) {
        File(context.cacheDir, LEGACY_CACHE_FILE).delete()
        val staleBefore = System.currentTimeMillis() - TMP_STALE_MS
        onlinePageCache(context).cleanupOnColdStart(staleBefore)
        booksDir(context).listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp") && f.lastModified() < staleBefore) f.delete()
        }
        File(context.cacheDir, THUMBS_DIR)
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.name.startsWith("online-") }
            .flatMap { it.listFiles().orEmpty().asSequence() }
            .filter { it.isFile && it.name.endsWith(".tmp") && it.lastModified() < staleBefore }
            .forEach(File::delete)
    }

    private fun directorySize(directory: File): Long =
        directory.walkTopDown().filter(File::isFile).sumOf(File::length)

    private data class EvictionEntry(val file: File, val kind: EvictionKind)

    private enum class EvictionKind { LOCAL_COPY, FILE }
}
