package com.exio.inkleaf.data.enhancement

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.storage.StorageManager
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class EnhancedImageDiskCacheWriteToken(
    val comicId: Long,
    val modelId: String,
    val comicGeneration: Long,
    val modelGeneration: Long,
)

internal enum class EnhancedImagePinnedWriteResult {
    WRITTEN,
    INVALIDATED,
    LOW_STORAGE,
    FAILED,
}

/** Stores enhanced pages in transient or user-pinned private app storage. */
internal class EnhancedImageDiskCache private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = EnhancedImageDiskCacheStore(
        cacheDir = appContext.cacheDir,
        filesDir = appContext.filesDir,
        codec = BitmapPngCodec,
    )
    private val storageManager = appContext.getSystemService(StorageManager::class.java)
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

    suspend fun writeTransientUnlessPinned(
        key: EnhancementPageKey,
        bitmap: Bitmap,
        token: EnhancedImageDiskCacheWriteToken = writeToken(key),
    ): Boolean = mutationMutex.withLock {
        if (!isCurrent(token, key)) return@withLock false
        val entry = key.toDiskEntry()
        if (store.containsPinned(entry)) return@withLock false
        store.writeTransient(entry, bitmap)
    }

    suspend fun writePinnedResult(
        key: EnhancementPageKey,
        bitmap: Bitmap,
        token: EnhancedImageDiskCacheWriteToken = writeToken(key),
    ): EnhancedImagePinnedWriteResult = mutationMutex.withLock {
        if (!isCurrent(token, key)) {
            return@withLock EnhancedImagePinnedWriteResult.INVALIDATED
        }
        if (!hasPinnedHeadroom(bitmap)) {
            return@withLock EnhancedImagePinnedWriteResult.LOW_STORAGE
        }
        if (store.writePinned(key.toDiskEntry(), bitmap)) {
            EnhancedImagePinnedWriteResult.WRITTEN
        } else {
            EnhancedImagePinnedWriteResult.FAILED
        }
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
        val usableBytes = allocatableBytes(store.transientStorageRoot())
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
        val pinnedRoot = store.pinnedStorageRoot()
        val usableBytes = allocatableBytes(pinnedRoot)
        if (usableBytes <= 0L) return false
        val totalBytes = pinnedRoot.totalSpace
        val reserveBytes = (totalBytes / PINNED_STORAGE_RESERVE_DIVISOR)
            .coerceIn(MIN_PINNED_STORAGE_RESERVE_BYTES, MAX_PINNED_STORAGE_RESERVE_BYTES)
        return usableBytes - bitmap.allocationByteCount.toLong() >= reserveBytes
    }

    /**
     * Prefer [StorageManager.getAllocatableBytes], which includes clearable cached data.
     * Fall back to [File.usableSpace] when the volume UUID is unavailable.
     */
    private fun allocatableBytes(root: File): Long {
        val manager = storageManager ?: return root.usableSpace
        return try {
            val uuid: UUID = manager.getUuidForPath(root)
            manager.getAllocatableBytes(uuid)
        } catch (_: IOException) {
            root.usableSpace
        } catch (_: IllegalArgumentException) {
            root.usableSpace
        }
    }

    private fun EnhancementPageKey.toDiskEntry() = EnhancedImageDiskCacheEntry(
        comicId = comicId,
        modelId = modelId,
        sourceRevision = sourceRevision,
        cacheKey = value,
    )

    private object BitmapPngCodec : EnhancedImageDiskCacheCodec<Bitmap> {
        override fun decode(file: File): Bitmap? {
            return try {
                BufferedInputStream(FileInputStream(file)).use { input ->
                    input.mark(ENHANCED_BITMAP_CACHE_HEADER_SIZE)
                    when (val header = readEnhancedBitmapCacheHeader(input)) {
                        EnhancedBitmapCacheHeader.Invalid -> null
                        EnhancedBitmapCacheHeader.Missing -> {
                            input.reset()
                            decodeBitmap(input, storedConfig = null)
                        }

                        is EnhancedBitmapCacheHeader.Present ->
                            decodeBitmap(input, header.colorConfig)
                    }
                }
            } catch (error: OutOfMemoryError) {
                throw EnhancedImageDiskCacheDecodeUnavailableException(error)
            }
        }

        override fun encode(value: Bitmap, output: OutputStream): Boolean {
            if (value.isRecycled) return false
            val storedConfig = if (value.config == Bitmap.Config.RGB_565) {
                EnhancedBitmapCacheColorConfig.RGB_565
            } else {
                EnhancedBitmapCacheColorConfig.ARGB_8888
            }
            writeEnhancedBitmapCacheHeader(output, storedConfig)
            return value.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
        }

        override fun isValid(file: File): Boolean {
            return try {
                val payload = readPayloadInfo(file) ?: return false
                val bounds = openPayload(file, payload.offset).use { input ->
                    BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { options ->
                        BitmapFactory.decodeStream(input, null, options)
                    }
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

                // Validate the same preferred config used by decode(), but keep the probe tiny so
                // containsPinned()/promoteToPinned() cannot allocate a poster-sized bitmap.
                val probe = openPayload(file, payload.offset).use { input ->
                    BitmapFactory.decodeStream(
                        input,
                        null,
                        BitmapFactory.Options().apply {
                            inSampleSize = validationSampleSize(bounds.outWidth, bounds.outHeight)
                            inPreferredConfig = payload.colorConfig.toBitmapConfig()
                        },
                    )
                } ?: return false
                try {
                    payload.colorConfig != EnhancedBitmapCacheColorConfig.RGB_565 ||
                            probe.config == Bitmap.Config.RGB_565
                } finally {
                    probe.recycle()
                }
            } catch (_: OutOfMemoryError) {
                false
            }
        }

        private fun EnhancedBitmapCacheColorConfig?.toBitmapConfig(): Bitmap.Config = when (this) {
            EnhancedBitmapCacheColorConfig.RGB_565 -> Bitmap.Config.RGB_565
            EnhancedBitmapCacheColorConfig.ARGB_8888,
            null -> Bitmap.Config.ARGB_8888
        }

        private fun decodeBitmap(
            input: InputStream,
            storedConfig: EnhancedBitmapCacheColorConfig?,
        ): Bitmap? {
            val bitmap = BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply {
                    inPreferredConfig = storedConfig.toBitmapConfig()
                },
            )
            if (
                storedConfig == EnhancedBitmapCacheColorConfig.RGB_565 &&
                bitmap != null &&
                bitmap.config != Bitmap.Config.RGB_565
            ) {
                bitmap.recycle()
                return null
            }
            return bitmap
        }

        private fun readPayloadInfo(file: File): PngPayloadInfo? =
            BufferedInputStream(FileInputStream(file)).use { input ->
                when (val header = readEnhancedBitmapCacheHeader(input)) {
                    EnhancedBitmapCacheHeader.Invalid -> null
                    EnhancedBitmapCacheHeader.Missing -> PngPayloadInfo(
                        colorConfig = null,
                        offset = 0L,
                    )

                    is EnhancedBitmapCacheHeader.Present -> PngPayloadInfo(
                        colorConfig = header.colorConfig,
                        offset = ENHANCED_BITMAP_CACHE_HEADER_SIZE.toLong(),
                    )
                }
            }

        private fun openPayload(file: File, offset: Long): BufferedInputStream {
            val input = BufferedInputStream(FileInputStream(file))
            try {
                var remaining = offset
                while (remaining > 0L) {
                    val skipped = input.skip(remaining)
                    if (skipped > 0L) {
                        remaining -= skipped
                    } else if (input.read() >= 0) {
                        remaining -= 1L
                    } else {
                        throw IOException("Truncated enhanced bitmap cache header")
                    }
                }
                return input
            } catch (error: Throwable) {
                runCatching { input.close() }
                throw error
            }
        }

        private fun validationSampleSize(width: Int, height: Int): Int {
            var sample = 1
            while (scaledPixelCount(width, height, sample) > MAX_VALIDATION_PIXELS) {
                if (sample > (Int.MAX_VALUE ushr 1)) return Int.MAX_VALUE
                sample = sample shl 1
            }
            return sample
        }

        private fun scaledPixelCount(width: Int, height: Int, sample: Int): Long {
            val scaledWidth = (width.toLong() + sample - 1L) / sample.toLong()
            val scaledHeight = (height.toLong() + sample - 1L) / sample.toLong()
            return scaledWidth * scaledHeight
        }

        private data class PngPayloadInfo(
            val colorConfig: EnhancedBitmapCacheColorConfig?,
            val offset: Long,
        )

        private const val MAX_VALIDATION_PIXELS = 64L * 64L
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
