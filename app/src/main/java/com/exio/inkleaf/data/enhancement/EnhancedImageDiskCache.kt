package com.exio.inkleaf.data.enhancement

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.OutputStream

internal data class EnhancedImageDiskCacheWriteToken(
    val comicId: Long,
    val modelId: String,
    val comicGeneration: Long,
    val modelGeneration: Long,
)

/** Stores enhanced pages in transient or user-pinned private app storage. */
internal class EnhancedImageDiskCache private constructor(context: Context) {
    private val store = EnhancedImageDiskCacheStore(
        cacheDir = context.cacheDir,
        filesDir = context.filesDir,
        codec = BitmapPngCodec,
    )
    private val mutationMutex = Mutex()
    private val generationLock = Any()
    private val comicGenerations = mutableMapOf<Long, Long>()
    private val modelGenerations = mutableMapOf<String, Long>()

    suspend fun read(key: EnhancementPageKey): Bitmap? = store.read(key.toDiskEntry())

    fun writeToken(key: EnhancementPageKey): EnhancedImageDiskCacheWriteToken =
        synchronized(generationLock) {
            EnhancedImageDiskCacheWriteToken(
                comicId = key.comicId,
                modelId = key.modelId,
                comicGeneration = comicGenerations.getOrDefault(key.comicId, 0L),
                modelGeneration = modelGenerations.getOrDefault(key.modelId, 0L),
            )
        }

    suspend fun writeTransient(
        key: EnhancementPageKey,
        bitmap: Bitmap,
        token: EnhancedImageDiskCacheWriteToken = writeToken(key),
    ): Boolean = mutationMutex.withLock {
        if (!isCurrent(token, key)) return@withLock false
        store.writeTransient(key.toDiskEntry(), bitmap)
    }

    suspend fun writePinned(
        key: EnhancementPageKey,
        bitmap: Bitmap,
        token: EnhancedImageDiskCacheWriteToken = writeToken(key),
    ): Boolean = mutationMutex.withLock {
        if (!isCurrent(token, key) || !hasPinnedHeadroom(bitmap)) return@withLock false
        store.writePinned(key.toDiskEntry(), bitmap)
    }

    suspend fun containsPinned(key: EnhancementPageKey): Boolean = mutationMutex.withLock {
        store.containsPinned(key.toDiskEntry())
    }

    suspend fun promoteToPinned(
        key: EnhancementPageKey,
        token: EnhancedImageDiskCacheWriteToken = writeToken(key),
    ): Boolean = mutationMutex.withLock {
        if (!isCurrent(token, key)) return@withLock false
        store.promoteToPinned(key.toDiskEntry())
    }

    suspend fun enforceTransientBudget(maxBytes: Long) =
        mutationMutex.withLock { store.enforceTransientBudget(maxBytes) }

    fun transientBudgetBytes(): Long {
        val usableBytes = store.transientUsableSpace()
        return if (usableBytes > 0L) {
            (usableBytes / TRANSIENT_STORAGE_DIVISOR)
                .coerceAtMost(MAX_TRANSIENT_CACHE_BYTES)
                .coerceAtLeast(1L)
        } else {
            FALLBACK_TRANSIENT_CACHE_BYTES
        }
    }

    suspend fun deleteComic(comicId: Long) {
        synchronized(generationLock) {
            comicGenerations[comicId] = comicGenerations.getOrDefault(comicId, 0L) + 1L
        }
        mutationMutex.withLock { store.deleteComic(comicId) }
    }

    suspend fun deleteModel(modelId: String) {
        synchronized(generationLock) {
            modelGenerations[modelId] = modelGenerations.getOrDefault(modelId, 0L) + 1L
        }
        mutationMutex.withLock { store.deleteModel(modelId) }
    }

    private fun isCurrent(
        token: EnhancedImageDiskCacheWriteToken,
        key: EnhancementPageKey,
    ): Boolean {
        if (token.comicId != key.comicId || token.modelId != key.modelId) return false
        return synchronized(generationLock) {
            token.comicGeneration == comicGenerations.getOrDefault(key.comicId, 0L) &&
                    token.modelGeneration == modelGenerations.getOrDefault(key.modelId, 0L)
        }
    }

    private fun hasPinnedHeadroom(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return false
        val usableBytes = store.pinnedUsableSpace()
        if (usableBytes <= 0L) return true
        val totalBytes = store.pinnedTotalSpace()
        val reserveBytes = (totalBytes / PINNED_STORAGE_RESERVE_DIVISOR)
            .coerceIn(MIN_PINNED_STORAGE_RESERVE_BYTES, MAX_PINNED_STORAGE_RESERVE_BYTES)
        return usableBytes - bitmap.allocationByteCount.toLong() >= reserveBytes
    }

    private fun EnhancementPageKey.toDiskEntry() = EnhancedImageDiskCacheEntry(
        comicId = comicId,
        modelId = modelId,
        sourceRevision = sourceRevision,
        cacheKey = value,
    )

    private object BitmapPngCodec : EnhancedImageDiskCacheCodec<Bitmap> {
        override fun decode(file: File): Bitmap? = try {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (error: OutOfMemoryError) {
            throw EnhancedImageDiskCacheDecodeUnavailableException(error)
        }

        override fun encode(value: Bitmap, output: OutputStream): Boolean =
            !value.isRecycled && value.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)

        override fun isValid(file: File): Boolean {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            return bounds.outWidth > 0 && bounds.outHeight > 0
        }
    }

    companion object {
        private const val PNG_QUALITY = 100
        private const val TRANSIENT_STORAGE_DIVISOR = 20L
        private const val MAX_TRANSIENT_CACHE_BYTES = 1024L * 1024 * 1024
        private const val FALLBACK_TRANSIENT_CACHE_BYTES = 128L * 1024 * 1024
        private const val PINNED_STORAGE_RESERVE_DIVISOR = 100L
        private const val MIN_PINNED_STORAGE_RESERVE_BYTES = 256L * 1024 * 1024
        private const val MAX_PINNED_STORAGE_RESERVE_BYTES = 1024L * 1024 * 1024

        @Volatile
        private var instance: EnhancedImageDiskCache? = null

        fun getInstance(context: Context): EnhancedImageDiskCache =
            instance ?: synchronized(this) {
                instance ?: EnhancedImageDiskCache(context.applicationContext)
                    .also { instance = it }
            }
    }
}
