package com.exio.inkleaf.data.enhancement

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.util.LruCache
import androidx.annotation.Keep
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.exio.inkleaf.data.ComicVolume
import com.exio.inkleaf.data.PagePixelSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

enum class EnhancementInferenceBackend { VULKAN, CPU, DISK_CACHE }

enum class EnhancementRequestPriority { CURRENT_PAGE, PREFETCH, BULK_CACHE }

enum class EnhancementSourceOwnership { TRANSFERRED, BORROWED }

enum class EnhancementPersistenceStatus {
    NONE,
    PINNED,
    LOW_STORAGE,
    INVALIDATED,
    WRITE_FAILED,
}

sealed interface EnhancementInferenceOutcome {
    /** The bitmap is a shared borrowed result and must not be recycled by callers. */
    data class Success(
        val bitmap: Bitmap,
        val backend: EnhancementInferenceBackend,
        val memoryCached: Boolean,
        val persistenceStatus: EnhancementPersistenceStatus =
            EnhancementPersistenceStatus.NONE,
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
    private val pageJobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pageJobs = EnhancementPageJobCoordinator<EnhancementInferenceOutcome>(pageJobScope)
    private val volumeLeases = EnhancementResourceLeaseRegistry<ComicVolume>()
    private val foregroundQuietWindow = EnhancementForegroundQuietWindow()
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
                    request.diskCache.writeTransientUnlessPinned(
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

    fun recordForegroundRequest(modelId: String) {
        foregroundQuietWindow.record(modelId)
    }

    /**
     * By default transfers [source] ownership to the shared page job. Borrowed sources remain
     * caller-owned and are only read by the selected producer. Attached duplicate requests discard
     * only transferred sources; the selected producer keeps its source reachable even if the
     * original requesting coroutine is cancelled.
     */
    suspend fun enhance(
        context: Context,
        key: EnhancementPageKey,
        source: Bitmap,
        preferVulkan: Boolean = true,
        persistTransient: Boolean = true,
        priority: EnhancementRequestPriority = EnhancementRequestPriority.CURRENT_PAGE,
        cacheInMemory: Boolean = true,
        sourceOwnership: EnhancementSourceOwnership = EnhancementSourceOwnership.TRANSFERRED,
    ): EnhancementInferenceOutcome {
        val recycleSource = sourceOwnership == EnhancementSourceOwnership.TRANSFERRED
        var sourceTransferred = false
        try {
            val persistenceRequirement = if (persistTransient) {
                EnhancementPersistenceRequirement.TRANSIENT
            } else {
                EnhancementPersistenceRequirement.PINNED
            }
            if (priority == EnhancementRequestPriority.BULK_CACHE) {
                foregroundQuietWindow.awaitBulkTurn(key.modelId)
            } else {
                foregroundQuietWindow.record(key.modelId)
            }
            val diskCache = EnhancedImageDiskCache.getInstance(context)
            val writeToken = diskCache.writeToken(key)
            sourceTransferred = true
            // discardProducer runs only if this job is dropped before the producer starts.
            // Once the producer runs, enhanceUncached / cache-hit path owns releaseSource.
            return pageJobs.request(
                key = key,
                priority = priority,
                persistenceRequirement = persistenceRequirement,
                discardProducer = { if (recycleSource) recycleSafely(source) },
                finalizer = { outcome, required ->
                    finalizePersistence(diskCache, writeToken, key, outcome, required)
                },
            ) {
                val memoryHit = cached(context, key)
                if (memoryHit != null) {
                    if (recycleSource) recycleSafely(source)
                    memoryHit
                } else {
                    enhanceUncached(
                        context = context,
                        modelId = key.modelId,
                        source = source,
                        cacheKey = key.value,
                        preferVulkan = preferVulkan,
                        cacheInMemory = cacheInMemory,
                        releaseSource = recycleSource,
                    )
                }
            }
        } finally {
            if (!sourceTransferred && recycleSource) recycleSafely(source)
        }
    }

    suspend fun pinCached(
        context: Context,
        key: EnhancementPageKey,
        cached: EnhancementInferenceOutcome.Success,
        priority: EnhancementRequestPriority,
    ): EnhancementInferenceOutcome {
        val diskCache = EnhancedImageDiskCache.getInstance(context)
        val writeToken = diskCache.writeToken(key)
        return pageJobs.request(
            key = key,
            priority = priority,
            persistenceRequirement = EnhancementPersistenceRequirement.PINNED,
            retainWithoutWaiters = priority == EnhancementRequestPriority.BULK_CACHE,
            finalizer = { outcome, required ->
                finalizePersistence(diskCache, writeToken, key, outcome, required)
            },
        ) {
            cached
        }
    }

    /**
     * Full-resolution strip enhancement (#23): one region at a time, compose into one output.
     * Caller must pass the exact input/output budgets that produced its [EnhancementPagePlan].
     */
    suspend fun enhanceStrips(
        context: Context,
        key: EnhancementPageKey,
        volume: ComicVolume,
        page: Int,
        sourceSize: PagePixelSize,
        inputPixelBudget: Long,
        outputByteBudget: Long,
        preferVulkan: Boolean = true,
        persistTransient: Boolean = true,
        priority: EnhancementRequestPriority = EnhancementRequestPriority.CURRENT_PAGE,
        cacheInMemory: Boolean = true,
    ): EnhancementInferenceOutcome {
        require(inputPixelBudget > 0L) { "input pixel budget must be positive" }
        require(outputByteBudget >= 0L) { "output byte budget must be non-negative" }
        val persistenceRequirement = if (persistTransient) {
            EnhancementPersistenceRequirement.TRANSIENT
        } else {
            EnhancementPersistenceRequirement.PINNED
        }
        if (priority == EnhancementRequestPriority.BULK_CACHE) {
            foregroundQuietWindow.awaitBulkTurn(key.modelId)
        } else {
            foregroundQuietWindow.record(key.modelId)
        }
        val diskCache = EnhancedImageDiskCache.getInstance(context)
        val writeToken = diskCache.writeToken(key)
        val volumeLease = volumeLeases.acquire(volume)
        var leaseTransferred = false
        try {
            leaseTransferred = true
            // A queued producer owns the lease until either discardProducer or producer finally.
            return pageJobs.request(
                key = key,
                priority = priority,
                persistenceRequirement = persistenceRequirement,
                discardProducer = { volumeLease.close() },
                finalizer = { outcome, required ->
                    finalizePersistence(diskCache, writeToken, key, outcome, required)
                },
            ) {
                try {
                    cached(context, key) ?: enhanceStripsUncached(
                        context = context,
                        modelId = key.modelId,
                        volume = volume,
                        page = page,
                        sourceSize = sourceSize,
                        maxInputPixels = inputPixelBudget,
                        maxOutputBytes = outputByteBudget,
                        cacheKey = key.value,
                        preferVulkan = preferVulkan,
                        cacheInMemory = cacheInMemory,
                    )
                } finally {
                    volumeLease.close()
                }
            }
        } finally {
            if (!leaseTransferred) volumeLease.close()
        }
    }

    /**
     * Defers bulk source decoding until this page owns the single producer slot. The non-cancellable
     * await keeps caller-owned book resources valid until that page boundary finishes; pause and
     * cancel are observed before the next page is submitted.
     */
    suspend fun enhanceBulk(
        context: Context,
        key: EnhancementPageKey,
        preferVulkan: Boolean = true,
        sourceLoader: suspend () -> Bitmap,
    ): EnhancementInferenceOutcome {
        foregroundQuietWindow.awaitBulkTurn(key.modelId)
        val diskCache = EnhancedImageDiskCache.getInstance(context)
        val writeToken = diskCache.writeToken(key)
        return withContext(NonCancellable) {
            pageJobs.request(
                key = key,
                priority = EnhancementRequestPriority.BULK_CACHE,
                persistenceRequirement = EnhancementPersistenceRequirement.PINNED,
                retainWithoutWaiters = true,
                finalizer = { outcome, required ->
                    finalizePersistence(diskCache, writeToken, key, outcome, required)
                },
            ) {
                val memoryHit = cached(context, key)
                if (memoryHit != null) {
                    memoryHit
                } else {
                    sourceLoader().let { source ->
                        enhanceUncached(
                            context = context,
                            modelId = key.modelId,
                            source = source,
                            cacheKey = key.value,
                            preferVulkan = preferVulkan,
                            cacheInMemory = false,
                            releaseSource = true,
                        )
                    }
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

    private suspend fun enhanceStripsUncached(
        context: Context,
        modelId: String,
        volume: ComicVolume,
        page: Int,
        sourceSize: PagePixelSize,
        maxInputPixels: Long,
        maxOutputBytes: Long,
        cacheKey: String,
        preferVulkan: Boolean,
        cacheInMemory: Boolean,
    ): EnhancementInferenceOutcome {
        val requestGeneration = activeModelGeneration(modelId)
            ?: return EnhancementInferenceOutcome.Failure("模型当前不可用，已显示原图。")
        return withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            if (!NativeEnhancementBridge.isLoaded()) {
                return@withContext EnhancementInferenceOutcome.Failure("ncnn 推理库未能加载。")
            }
            cached(modelId, cacheKey)?.let { return@withContext it }
            val session = sessionFor(context, modelId, preferVulkan)
                ?: return@withContext EnhancementInferenceOutcome.Failure(
                    "模型加载失败，请重新下载模型包。"
                )
            val scale = session.scale
            val allocation = planStripOutputAllocation(
                sourceWidth = sourceSize.width,
                sourceHeight = sourceSize.height,
                scale = scale,
                maxOutputBytes = maxOutputBytes,
            ) ?: return@withContext EnhancementInferenceOutcome.Failure(
                "页面尺寸过大，无法安全创建分带输出。"
            )
            val strips = planEnhancementStrips(
                sourceWidth = sourceSize.width,
                sourceHeight = sourceSize.height,
                scale = scale,
                maxInputPixels = maxInputPixels,
            )
            if (strips.isEmpty()) {
                return@withContext EnhancementInferenceOutcome.Failure("无法划分增强条带。")
            }
            var composed: Bitmap? = null
            try {
                val bitmapConfig = when (allocation.colorConfig) {
                    StripOutputColorConfig.ARGB_8888 -> Bitmap.Config.ARGB_8888
                    StripOutputColorConfig.RGB_565 -> Bitmap.Config.RGB_565
                }
                composed = Bitmap.createBitmap(
                    sourceSize.width * scale,
                    sourceSize.height * scale,
                    bitmapConfig,
                )
                val canvas = Canvas(composed)
                for (strip in strips) {
                    currentCoroutineContext().ensureActive()
                    if (!isModelGenerationCurrent(modelId, requestGeneration)) {
                        return@withContext EnhancementInferenceOutcome.Failure(
                            "模型已被移除，已显示原图。"
                        )
                    }
                    val region = volume.loadPageRegion(
                        globalPage = page,
                        left = 0,
                        top = strip.sourceTop,
                        width = sourceSize.width,
                        height = strip.sourceHeight,
                    ) ?: return@withContext EnhancementInferenceOutcome.Failure(
                        "无法读取页面条带，已显示原图。"
                    )
                    val stripOutcome = try {
                        enhanceUncached(
                            context = context,
                            modelId = modelId,
                            source = region,
                            cacheKey = "$cacheKey#strip-${strip.sourceTop}",
                            preferVulkan = preferVulkan,
                            cacheInMemory = false,
                            releaseSource = false,
                        )
                    } finally {
                        recycleSafely(region)
                    }
                    when (stripOutcome) {
                        is EnhancementInferenceOutcome.Failure -> return@withContext stripOutcome
                        is EnhancementInferenceOutcome.Success -> {
                            val enhanced = stripOutcome.bitmap
                            try {
                                val src = Rect(
                                    0,
                                    strip.outputCropTop,
                                    enhanced.width,
                                    strip.outputCropTop + strip.outputCoreHeight,
                                )
                                val dst = Rect(
                                    0,
                                    strip.outputCoreTop,
                                    enhanced.width,
                                    strip.outputCoreTop + strip.outputCoreHeight,
                                )
                                canvas.drawBitmap(enhanced, src, dst, null)
                            } finally {
                                if (!stripOutcome.memoryCached) recycleSafely(enhanced)
                            }
                        }
                    }
                }
                if (!isModelGenerationCurrent(modelId, requestGeneration)) {
                    return@withContext EnhancementInferenceOutcome.Failure(
                        "模型已被移除，已显示原图。"
                    )
                }
                val output = requireNotNull(composed)
                composed = null
                if (cacheInMemory) {
                    synchronized(bitmapCache) {
                        if (!isModelGenerationCurrent(modelId, requestGeneration)) {
                            recycleSafely(output)
                            return@withContext EnhancementInferenceOutcome.Failure(
                                "模型已被移除，已显示原图。"
                            )
                        }
                        bitmapCache.put(
                            cacheKey,
                            CachedBitmap(modelId, output, session.backend),
                        )
                    }
                }
                EnhancementInferenceOutcome.Success(
                    bitmap = output,
                    backend = session.backend,
                    memoryCached = cacheInMemory,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                EnhancementInferenceOutcome.Failure("设备内存不足，已显示原图。")
            } catch (_: Exception) {
                EnhancementInferenceOutcome.Failure("AI 分带推理失败，已显示原图。")
            } finally {
                composed?.let { recycleSafely(it) }
            }
        }
    }

    private suspend fun enhanceUncached(
        context: Context,
        modelId: String,
        source: Bitmap,
        cacheKey: String,
        preferVulkan: Boolean,
        cacheInMemory: Boolean,
        releaseSource: Boolean,
    ): EnhancementInferenceOutcome {
        val requestGeneration = activeModelGeneration(modelId)
            ?: run {
                if (releaseSource) recycleSafely(source)
                return EnhancementInferenceOutcome.Failure("模型当前不可用，已显示原图。")
            }
        return withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            if (!NativeEnhancementBridge.isLoaded()) {
                if (releaseSource) recycleSafely(source)
                return@withContext EnhancementInferenceOutcome.Failure(
                    "ncnn 推理库未能加载。"
                )
            }
            var prepared: Bitmap? = null
            var unownedOutput: Bitmap? = null
            // When true, [source] still needs recycle in finally (prepared === source path).
            var sourceHeldForInference = releaseSource
            try {
                currentCoroutineContext().ensureActive()
                cached(modelId, cacheKey)?.let {
                    if (releaseSource) recycleSafely(source)
                    sourceHeldForInference = false
                    return@withContext it
                }
                val session = sessionFor(context, modelId, preferVulkan)
                    ?: return@withContext EnhancementInferenceOutcome.Failure(
                        "模型加载失败，请重新下载模型包。"
                    )
                prepared = prepareInput(source, session.scale)
                    ?: return@withContext EnhancementInferenceOutcome.Failure(
                        "页面尺寸过大，无法安全创建推理位图。"
                    )
                // Floor decode may exceed M; drop it once continuous clamp exists so peak
                // matches calculateMaxInputPixels (prepared + output + native mats only).
                if (releaseSource && prepared !== source) {
                    recycleSafely(source)
                    sourceHeldForInference = false
                }
                val inferenceInput = requireNotNull(prepared)
                unownedOutput = createBitmap(
                    inferenceInput.width * session.scale,
                    inferenceInput.height * session.scale,
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
                    return@withContext EnhancementInferenceOutcome.Failure(
                        "AI 推理失败（错误码 $resultCode），已显示原图。"
                    )
                }
                if (!isModelGenerationCurrent(modelId, requestGeneration)) {
                    return@withContext EnhancementInferenceOutcome.Failure(
                        "模型已被移除，已显示原图。"
                    )
                }
                if (cacheInMemory) {
                    synchronized(bitmapCache) {
                        if (!isModelGenerationCurrent(modelId, requestGeneration)) {
                            return@withContext EnhancementInferenceOutcome.Failure(
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                EnhancementInferenceOutcome.Failure("设备内存不足，已显示原图。")
            } catch (_: Exception) {
                EnhancementInferenceOutcome.Failure("AI 推理失败，已显示原图。")
            } finally {
                unownedOutput?.recycle()
                val preparedBitmap = prepared
                if (preparedBitmap != null && preparedBitmap !== source) {
                    recycleSafely(preparedBitmap)
                }
                if (sourceHeldForInference) recycleSafely(source)
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
            argb.scale(targetWidth, targetHeight)
        } catch (_: OutOfMemoryError) {
            null
        }
        if (argb !== source && argb !== scaled) argb.recycle()
        return scaled
    }

    fun maxInputPixels(scale: Int = 2): Long = memoryBudget(scale).maxInputPixels

    internal fun memoryBudget(scale: Int = 2): EnhancementMemoryBudget =
        calculateEnhancementMemoryBudget(Runtime.getRuntime().maxMemory(), scale)

    internal fun maxStripOutputBytes(scale: Int = 2): Long = memoryBudget(scale).composedOutputBytes

    internal suspend fun closeVolumeAfterEnhancement(volume: ComicVolume) {
        volumeLeases.closeAfterIdle(volume) { it.close() }
    }

    private suspend fun finalizePersistence(
        diskCache: EnhancedImageDiskCache,
        writeToken: EnhancedImageDiskCacheWriteToken,
        key: EnhancementPageKey,
        outcome: EnhancementInferenceOutcome,
        requirement: EnhancementPersistenceRequirement,
    ): EnhancementInferenceOutcome {
        val success = outcome as? EnhancementInferenceOutcome.Success ?: return outcome
        if (requirement == EnhancementPersistenceRequirement.TRANSIENT) {
            if (success.backend != EnhancementInferenceBackend.DISK_CACHE) {
                diskWriteQueue.trySend(
                    TransientDiskWrite(
                        diskCache = diskCache,
                        token = writeToken,
                        key = key,
                        bitmap = success.bitmap,
                    )
                )
            }
            return success
        }
        if (diskCache.containsPinned(key)) {
            return success.copy(persistenceStatus = EnhancementPersistenceStatus.PINNED)
        }
        val persistenceStatus = when (
            diskCache.writePinnedResult(key, success.bitmap, writeToken)
        ) {
            EnhancedImagePinnedWriteResult.WRITTEN -> EnhancementPersistenceStatus.PINNED
            EnhancedImagePinnedWriteResult.INVALIDATED -> EnhancementPersistenceStatus.INVALIDATED
            EnhancedImagePinnedWriteResult.LOW_STORAGE -> EnhancementPersistenceStatus.LOW_STORAGE
            EnhancedImagePinnedWriteResult.FAILED -> EnhancementPersistenceStatus.WRITE_FAILED
        }
        return success.copy(persistenceStatus = persistenceStatus)
    }

    private fun recycleSafely(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private const val NATIVE_RESULT_OK = 0
    private const val NATIVE_ERROR_SESSION_CLOSED = -1
    private const val NATIVE_ERROR_MODEL_DISABLED = -2
}

internal class EnhancementForegroundQuietWindow(
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Any()
    private var lastForegroundModelId: String? = null
    private var lastForegroundRequestAt = Long.MIN_VALUE

    fun record(modelId: String) {
        synchronized(lock) {
            lastForegroundModelId = modelId
            lastForegroundRequestAt = now()
        }
    }

    suspend fun awaitBulkTurn(modelId: String) {
        while (true) {
            val remaining = synchronized(lock) {
                if (lastForegroundModelId == null || lastForegroundModelId == modelId) {
                    0L
                } else {
                    DIFFERENT_MODEL_QUIET_WINDOW_MS - (now() - lastForegroundRequestAt)
                }
            }
            if (remaining <= 0L) return
            pause(remaining.coerceAtMost(QUIET_WINDOW_POLL_MS))
        }
    }
}

private const val TRANSIENT_BUDGET_INTERVAL_MS = 30_000L
private const val DIFFERENT_MODEL_QUIET_WINDOW_MS = 2_000L
private const val QUIET_WINDOW_POLL_MS = 100L
