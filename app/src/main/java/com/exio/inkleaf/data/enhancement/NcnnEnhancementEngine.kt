package com.exio.inkleaf.data.enhancement

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.annotation.Keep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

enum class EnhancementInferenceBackend { VULKAN, CPU, DISK_CACHE }

enum class EnhancementRequestPriority { CURRENT_PAGE, PREFETCH, BULK_CACHE }

internal fun calculateInferenceSampleSize(
    width: Int,
    height: Int,
    maxPixels: Long,
): Int {
    require(width > 0 && height > 0)
    require(maxPixels > 0)
    var sampleSize = 1L
    while (true) {
        val sampledWidth = (width.toLong() + sampleSize - 1L) / sampleSize
        val sampledHeight = (height.toLong() + sampleSize - 1L) / sampleSize
        if (sampledWidth * sampledHeight <= maxPixels) {
            return sampleSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        sampleSize *= 2L
    }
}

internal fun calculateMaxInputPixels(maxMemoryBytes: Long, scale: Int = 2): Long {
    require(scale > 0)
    val estimatedBytesPerInputPixel = 16L + 8L * scale * scale
    val inferenceBudget = (maxMemoryBytes / INFERENCE_HEAP_DIVISOR)
        .coerceAtMost(MAX_INFERENCE_BYTES)
        .coerceAtLeast(estimatedBytesPerInputPixel)
    return inferenceBudget / estimatedBytesPerInputPixel
}

internal fun calculateBitmapCacheKilobytes(maxMemoryBytes: Long): Int =
    (maxMemoryBytes / CACHE_HEAP_DIVISOR)
        .coerceAtMost(MAX_CACHE_BYTES)
        .coerceAtLeast(1024L)
        .div(1024)
        .toInt()

sealed interface EnhancementInferenceOutcome {
    data class Success(
        val bitmap: Bitmap,
        val backend: EnhancementInferenceBackend,
        val memoryCached: Boolean,
    ) : EnhancementInferenceOutcome

    data class Failure(val message: String) : EnhancementInferenceOutcome
}

@Keep
internal object NativeEnhancementBridge {
    private val loadResult: Result<Unit> by lazy {
        runCatching { System.loadLibrary("inkleaf_enhancement") }
    }

    fun isLoaded(): Boolean = loadResult.isSuccess

    fun gpuCount(): Int {
        loadResult.getOrThrow()
        return nativeGpuCount()
    }

    fun createSession(
        modelId: String,
        paramPath: String,
        modelPath: String,
        preferVulkan: Boolean,
    ): Long {
        loadResult.getOrThrow()
        return nativeCreateSession(modelId, paramPath, modelPath, preferVulkan)
    }

    fun sessionUsesVulkan(handle: Long): Boolean = nativeSessionUsesVulkan(handle)

    fun enhance(handle: Long, input: Bitmap, output: Bitmap): Int =
        nativeEnhance(handle, input, output)

    fun destroySession(handle: Long) = nativeDestroySession(handle)

    private external fun nativeGpuCount(): Int
    private external fun nativeCreateSession(
        modelId: String,
        paramPath: String,
        modelPath: String,
        preferVulkan: Boolean,
    ): Long

    private external fun nativeSessionUsesVulkan(handle: Long): Boolean
    private external fun nativeEnhance(handle: Long, input: Bitmap, output: Bitmap): Int
    private external fun nativeDestroySession(handle: Long)
}

object NcnnEnhancementEngine {
    private data class Session(
        val handle: Long,
        val backend: EnhancementInferenceBackend,
        val scale: Int,
        val inferenceMutex: Mutex = Mutex(),
        var closed: Boolean = false,
    )

    private data class CachedBitmap(
        val modelId: String,
        val bitmap: Bitmap,
        val backend: EnhancementInferenceBackend,
    )

    private data class TransientDiskWrite(
        val diskCache: EnhancedImageDiskCache,
        val token: EnhancedImageDiskCacheWriteToken,
        val key: EnhancementPageKey,
        val bitmap: Bitmap,
    )

    private val sessionMutex = Mutex()
    private val inferenceScheduler = EnhancementInferenceScheduler()
    private val sessions = mutableMapOf<String, Session>()
    private val disabledModelsLock = Any()
    private val disabledModels = mutableSetOf<String>()
    private val modelGenerations = mutableMapOf<String, Long>()

    private val bitmapCache = object : LruCache<String, CachedBitmap>(
        calculateBitmapCacheKilobytes(Runtime.getRuntime().maxMemory())
    ) {
        override fun sizeOf(key: String, value: CachedBitmap): Int =
            (value.bitmap.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val inFlightRequests =
        InFlightRequestRegistry<String, EnhancementInferenceOutcome>()
    private val diskWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diskWriteQueue = Channel<TransientDiskWrite>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        diskWriteScope.launch {
            var lastBudgetEnforcementAt = 0L
            for (request in diskWriteQueue) {
                if (
                    request.diskCache.writeTransient(
                        request.key,
                        request.bitmap,
                        request.token,
                    )
                ) {
                    val now = System.currentTimeMillis()
                    if (now - lastBudgetEnforcementAt >= TRANSIENT_BUDGET_INTERVAL_MS) {
                        request.diskCache.enforceTransientBudget(
                            request.diskCache.transientBudgetBytes()
                        )
                        lastBudgetEnforcementAt = now
                    }
                }
            }
        }
    }

    fun runtimeDescription(): String = when {
        !NativeEnhancementBridge.isLoaded() -> "ncnn 未加载"
        runCatching { NativeEnhancementBridge.gpuCount() }.getOrDefault(0) > 0 ->
            "ncnn · Vulkan / CPU"
        else -> "ncnn · CPU"
    }

    suspend fun enhance(
        context: Context,
        key: EnhancementPageKey,
        source: Bitmap,
        preferVulkan: Boolean = true,
        persistTransient: Boolean = true,
        priority: EnhancementRequestPriority = EnhancementRequestPriority.CURRENT_PAGE,
        cacheInMemory: Boolean = true,
    ): EnhancementInferenceOutcome {
        cached(key.modelId, key.value)?.let { return it }
        val requestKey = "${priority.name}\u0000${key.modelId}\u0000${key.value}"
        return inFlightRequests.run(requestKey) {
            cached(key.modelId, key.value) ?: enhanceUncached(
                context = context,
                modelId = key.modelId,
                source = source,
                cacheKey = key.value,
                preferVulkan = preferVulkan,
                priority = priority,
                cacheInMemory = cacheInMemory,
            ).also { outcome ->
                if (persistTransient && outcome is EnhancementInferenceOutcome.Success) {
                    val diskCache = EnhancedImageDiskCache.getInstance(context)
                    diskWriteQueue.trySend(
                        TransientDiskWrite(
                            diskCache = diskCache,
                            token = diskCache.writeToken(key),
                            key = key,
                            bitmap = outcome.bitmap,
                        )
                    )
                }
            }
        }
    }

    suspend fun cached(
        context: Context,
        key: EnhancementPageKey,
    ): EnhancementInferenceOutcome.Success? {
        cached(key.modelId, key.value)?.let { return it }
        val bitmap = EnhancedImageDiskCache.getInstance(context).read(key) ?: return null
        val generation = activeModelGeneration(key.modelId)
        if (generation == null || !isModelGenerationCurrent(key.modelId, generation)) {
            bitmap.recycle()
            return null
        }
        val cached = CachedBitmap(
            modelId = key.modelId,
            bitmap = bitmap,
            backend = EnhancementInferenceBackend.DISK_CACHE,
        )
        synchronized(bitmapCache) { bitmapCache.put(key.value, cached) }
        return EnhancementInferenceOutcome.Success(bitmap, cached.backend, memoryCached = true)
    }

    fun cached(
        modelId: String,
        cacheKey: String,
    ): EnhancementInferenceOutcome.Success? {
        val requestGeneration = activeModelGeneration(modelId)
            ?: return null
        synchronized(bitmapCache) {
            bitmapCache.get(cacheKey)?.takeUnless { it.bitmap.isRecycled }?.let { cached ->
                if (
                    cached.modelId == modelId &&
                    isModelGenerationCurrent(modelId, requestGeneration)
                ) {
                    return EnhancementInferenceOutcome.Success(
                        bitmap = cached.bitmap,
                        backend = cached.backend,
                        memoryCached = true,
                    )
                }
            }
        }
        return null
    }

    private suspend fun enhanceUncached(
        context: Context,
        modelId: String,
        source: Bitmap,
        cacheKey: String,
        preferVulkan: Boolean,
        priority: EnhancementRequestPriority,
        cacheInMemory: Boolean,
    ): EnhancementInferenceOutcome {
        val requestGeneration = activeModelGeneration(modelId)
            ?: return EnhancementInferenceOutcome.Failure("模型当前不可用，已显示原图。")
        return withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            if (!NativeEnhancementBridge.isLoaded()) {
                return@withContext EnhancementInferenceOutcome.Failure(
                    "ncnn 推理库未能加载。"
                )
            }
            var prepared: Bitmap? = null
            var unownedOutput: Bitmap? = null
            try {
                inferenceScheduler.withPermit(priority) {
                    currentCoroutineContext().ensureActive()
                    cached(modelId, cacheKey)?.let { return@withPermit it }
                    val session = sessionFor(context, modelId, preferVulkan)
                        ?: return@withPermit EnhancementInferenceOutcome.Failure(
                            "模型加载失败，请重新下载模型包。"
                        )
                    prepared = prepareInput(source, session.scale)
                        ?: return@withPermit EnhancementInferenceOutcome.Failure(
                            "页面尺寸过大，无法安全创建推理位图。"
                        )
                    val inferenceInput = requireNotNull(prepared)
                    unownedOutput = Bitmap.createBitmap(
                        inferenceInput.width * session.scale,
                        inferenceInput.height * session.scale,
                        Bitmap.Config.ARGB_8888,
                    ).apply { setHasAlpha(inferenceInput.hasAlpha()) }
                    val inferenceOutput = requireNotNull(unownedOutput)

                    val resultCode = session.inferenceMutex.withLock {
                        when {
                            session.closed -> NATIVE_ERROR_SESSION_CLOSED
                            !isModelGenerationCurrent(modelId, requestGeneration) ->
                                NATIVE_ERROR_MODEL_DISABLED
                            else -> NativeEnhancementBridge.enhance(
                                session.handle,
                                inferenceInput,
                                inferenceOutput,
                            )
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (resultCode != NATIVE_RESULT_OK) {
                        return@withPermit EnhancementInferenceOutcome.Failure(
                            "AI 推理失败（错误码 $resultCode），已显示原图。"
                        )
                    }
                    if (!isModelGenerationCurrent(modelId, requestGeneration)) {
                        return@withPermit EnhancementInferenceOutcome.Failure(
                            "模型已被移除，已显示原图。"
                        )
                    }
                    if (cacheInMemory) {
                        synchronized(bitmapCache) {
                            if (!isModelGenerationCurrent(modelId, requestGeneration)) {
                                return@withPermit EnhancementInferenceOutcome.Failure(
                                    "模型已被移除，已显示原图。"
                                )
                            }
                            bitmapCache.put(
                                cacheKey,
                                CachedBitmap(modelId, inferenceOutput, session.backend),
                            )
                        }
                    }
                    unownedOutput = null
                    EnhancementInferenceOutcome.Success(
                        bitmap = inferenceOutput,
                        backend = session.backend,
                        memoryCached = cacheInMemory,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                EnhancementInferenceOutcome.Failure("设备内存不足，已显示原图。")
            } catch (_: Exception) {
                EnhancementInferenceOutcome.Failure("AI 推理失败，已显示原图。")
            } finally {
                unownedOutput?.recycle()
                prepared?.takeIf { it !== source }?.recycle()
            }
        }
    }

    suspend fun evictModel(modelId: String) = withContext(NonCancellable) {
        synchronized(disabledModelsLock) {
            disabledModels += modelId
            modelGenerations[modelId] = modelGenerations.getOrDefault(modelId, 0L) + 1L
        }
        val removedSessions = sessionMutex.withLock {
            sessions.keys
                .filter { key -> key.startsWith("$modelId:") }
                .mapNotNull(sessions::remove)
        }
        removedSessions.forEach { session ->
            session.inferenceMutex.withLock {
                if (!session.closed) {
                    session.closed = true
                    NativeEnhancementBridge.destroySession(session.handle)
                }
            }
        }
        synchronized(bitmapCache) {
            bitmapCache.snapshot()
                .filterValues { cached -> cached.modelId == modelId }
                .keys
                .forEach(bitmapCache::remove)
        }
    }

    fun enableModel(modelId: String) {
        synchronized(disabledModelsLock) { disabledModels -= modelId }
    }

    private suspend fun sessionFor(
        context: Context,
        modelId: String,
        preferVulkan: Boolean,
    ): Session? =
        sessionMutex.withLock {
            if (isModelDisabled(modelId)) return@withLock null
            val sessionKey = "$modelId:${if (preferVulkan) "vulkan" else "cpu"}"
            sessions.keys.filter { it != sessionKey }.mapNotNull(sessions::remove).forEach {
                staleSession ->
                staleSession.inferenceMutex.withLock {
                    if (!staleSession.closed) {
                        staleSession.closed = true
                        NativeEnhancementBridge.destroySession(staleSession.handle)
                    }
                }
            }
            sessions[sessionKey]?.let { return@withLock it }
            val model = EnhancementModelCatalog.find(modelId) ?: return@withLock null
            val directory = EnhancementModelRepository.getInstance(context)
                .installedDirectory(modelId) ?: return@withLock null
            val param = model.artifacts.singleOrNull { it.filename.endsWith(".param") }
                ?: return@withLock null
            val weights = model.artifacts.singleOrNull { it.filename.endsWith(".bin") }
                ?: return@withLock null
            val handle = NativeEnhancementBridge.createSession(
                modelId = modelId,
                paramPath = directory.resolve(param.filename).absolutePath,
                modelPath = directory.resolve(weights.filename).absolutePath,
                preferVulkan = preferVulkan,
            )
            if (handle == 0L) return@withLock null
            Session(
                handle = handle,
                scale = model.scale,
                backend = if (NativeEnhancementBridge.sessionUsesVulkan(handle)) {
                    EnhancementInferenceBackend.VULKAN
                } else {
                    EnhancementInferenceBackend.CPU
                },
            ).also { sessions[sessionKey] = it }
        }

    private fun isModelDisabled(modelId: String): Boolean =
        synchronized(disabledModelsLock) { modelId in disabledModels }

    private fun activeModelGeneration(modelId: String): Long? =
        synchronized(disabledModelsLock) {
            if (modelId in disabledModels) null else modelGenerations.getOrDefault(modelId, 0L)
        }

    private fun isModelGenerationCurrent(modelId: String, generation: Long): Boolean =
        synchronized(disabledModelsLock) {
            modelId !in disabledModels && modelGenerations.getOrDefault(modelId, 0L) == generation
        }

    private fun prepareInput(source: Bitmap, scale: Int): Bitmap? {
        val argb = try {
            if (source.config == Bitmap.Config.ARGB_8888 && !source.isRecycled) {
                source
            } else {
                source.copy(Bitmap.Config.ARGB_8888, false)
            }
        } catch (_: OutOfMemoryError) {
            return null
        } ?: return null

        val maxInputPixels = maxInputPixels(scale)
        val sourcePixels = argb.width.toLong() * argb.height.toLong()
        if (sourcePixels <= maxInputPixels) return argb

        val ratio = sqrt(maxInputPixels.toDouble() / sourcePixels.toDouble())
        val targetWidth = (argb.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (argb.height * ratio).toInt().coerceAtLeast(1)
        val scaled = try {
            Bitmap.createScaledBitmap(argb, targetWidth, targetHeight, true)
        } catch (_: OutOfMemoryError) {
            null
        }
        if (argb !== source && argb !== scaled) argb.recycle()
        return scaled
    }

    fun maxInputPixels(scale: Int = 2): Long =
        calculateMaxInputPixels(Runtime.getRuntime().maxMemory(), scale)

    // Includes source/prepared overlap, native input/output, Java output, and headroom.
    private const val NATIVE_RESULT_OK = 0
    private const val NATIVE_ERROR_SESSION_CLOSED = -1
    private const val NATIVE_ERROR_MODEL_DISABLED = -2
}

internal class EnhancementInferenceScheduler {
    private data class Waiter(
        val priority: EnhancementRequestPriority,
        val signal: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val mutex = Mutex()
    private val waiters = mutableListOf<Waiter>()
    private var active = false

    suspend fun <T> withPermit(
        priority: EnhancementRequestPriority,
        block: suspend () -> T,
    ): T {
        val waiter = mutex.withLock {
            if (!active) {
                active = true
                null
            } else {
                Waiter(priority).also(waiters::add)
            }
        }
        if (waiter != null) {
            try {
                waiter.signal.await()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    val hadPermit = mutex.withLock { !waiters.remove(waiter) }
                    if (hadPermit) release()
                }
                throw cancelled
            }
        }

        try {
            return block()
        } finally {
            withContext(NonCancellable) { release() }
        }
    }

    private suspend fun release() {
        val next = mutex.withLock {
            if (waiters.isEmpty()) {
                active = false
                null
            } else {
                val nextIndex = waiters.indices.minBy { waiters[it].priority.ordinal }
                waiters.removeAt(nextIndex)
            }
        }
        next?.signal?.complete(Unit)
    }
}

private const val INFERENCE_HEAP_DIVISOR = 4L

// Keep the cache below the active inference budget so cached output cannot
// crowd out the input/output bitmaps and native buffers used by ncnn.
private const val CACHE_HEAP_DIVISOR = 10L
private const val MAX_INFERENCE_BYTES = 64L * 1024 * 1024
private const val MAX_CACHE_BYTES = 48L * 1024 * 1024
private const val TRANSIENT_BUDGET_INTERVAL_MS = 30_000L
