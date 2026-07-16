package com.exio.inkleaf.data.enhancement

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.annotation.Keep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

enum class EnhancementInferenceBackend { VULKAN, CPU }

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

internal fun calculateMaxInputPixels(maxMemoryBytes: Long): Long {
    val inferenceBudget = (maxMemoryBytes / INFERENCE_HEAP_DIVISOR)
        .coerceAtMost(MAX_INFERENCE_BYTES)
        .coerceAtLeast(ESTIMATED_BYTES_PER_INPUT_PIXEL)
    return inferenceBudget / ESTIMATED_BYTES_PER_INPUT_PIXEL
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
        val inferenceMutex: Mutex = Mutex(),
        var closed: Boolean = false,
    )

    private data class CachedBitmap(
        val modelId: String,
        val bitmap: Bitmap,
        val backend: EnhancementInferenceBackend,
    )

    private val sessionMutex = Mutex()
    private val globalInferenceMutex = Mutex()
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

    fun runtimeDescription(): String = when {
        !NativeEnhancementBridge.isLoaded() -> "ncnn 未加载"
        runCatching { NativeEnhancementBridge.gpuCount() }.getOrDefault(0) > 0 ->
            "ncnn · Vulkan / CPU"
        else -> "ncnn · CPU"
    }

    suspend fun enhance(
        context: Context,
        modelId: String,
        source: Bitmap,
        cacheKey: String,
        preferVulkan: Boolean = true,
    ): EnhancementInferenceOutcome {
        val requestGeneration = activeModelGeneration(modelId)
            ?: return EnhancementInferenceOutcome.Failure("模型当前不可用，已显示原图。")
        synchronized(bitmapCache) {
            bitmapCache.get(cacheKey)?.takeUnless { it.bitmap.isRecycled }?.let { cached ->
                if (isModelGenerationCurrent(modelId, requestGeneration)) {
                    return EnhancementInferenceOutcome.Success(
                        bitmap = cached.bitmap,
                        backend = cached.backend,
                    )
                }
            }
        }

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
                globalInferenceMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    val session = sessionFor(context, modelId, preferVulkan)
                        ?: return@withLock EnhancementInferenceOutcome.Failure(
                            "模型加载失败，请重新下载模型包。"
                        )
                    prepared = prepareInput(source)
                        ?: return@withLock EnhancementInferenceOutcome.Failure(
                            "页面尺寸过大，无法安全创建推理位图。"
                        )
                    val inferenceInput = requireNotNull(prepared)
                    unownedOutput = Bitmap.createBitmap(
                        inferenceInput.width * MODEL_SCALE,
                        inferenceInput.height * MODEL_SCALE,
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
                        return@withLock EnhancementInferenceOutcome.Failure(
                            "AI 推理失败（错误码 $resultCode），已显示原图。"
                        )
                    }
                    if (!isModelGenerationCurrent(modelId, requestGeneration)) {
                        return@withLock EnhancementInferenceOutcome.Failure(
                            "模型已被移除，已显示原图。"
                        )
                    }
                    synchronized(bitmapCache) {
                        if (!isModelGenerationCurrent(modelId, requestGeneration)) {
                            return@withLock EnhancementInferenceOutcome.Failure(
                                "模型已被移除，已显示原图。"
                            )
                        }
                        bitmapCache.put(
                            cacheKey,
                            CachedBitmap(modelId, inferenceOutput, session.backend),
                        )
                    }
                    unownedOutput = null
                    EnhancementInferenceOutcome.Success(inferenceOutput, session.backend)
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

    private fun prepareInput(source: Bitmap): Bitmap? {
        val argb = try {
            if (source.config == Bitmap.Config.ARGB_8888 && !source.isRecycled) {
                source
            } else {
                source.copy(Bitmap.Config.ARGB_8888, false)
            }
        } catch (_: OutOfMemoryError) {
            return null
        } ?: return null

        val maxInputPixels = maxInputPixels()
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

    fun maxInputPixels(): Long = calculateMaxInputPixels(Runtime.getRuntime().maxMemory())

    private const val MODEL_SCALE = 2
    // Includes source/prepared overlap, native input/output, Java output, and headroom.
    private const val NATIVE_RESULT_OK = 0
    private const val NATIVE_ERROR_SESSION_CLOSED = -1
    private const val NATIVE_ERROR_MODEL_DISABLED = -2
}

private const val ESTIMATED_BYTES_PER_INPUT_PIXEL = 48L
private const val INFERENCE_HEAP_DIVISOR = 4L
private const val CACHE_HEAP_DIVISOR = 12L
private const val MAX_INFERENCE_BYTES = 64L * 1024 * 1024
private const val MAX_CACHE_BYTES = 24L * 1024 * 1024
