package com.exio.inkleaf.data.enhancement

/** Combined heap budget used by both strip planning and strip execution. */
internal data class EnhancementMemoryBudget(
    val maxInputPixels: Long,
    val inferenceReserveBytes: Long,
    val cacheReserveBytes: Long,
    /** Heap kept available for reader/UI bitmaps and other transient runtime allocations. */
    val runtimeReserveBytes: Long,
    val composedOutputBytes: Long,
)

/** Minimum non-enhancement heap reserve; larger heaps reserve a proportional margin below. */
internal const val MIN_ENHANCEMENT_RUNTIME_RESERVE_BYTES = 8L * 1024 * 1024

/** Active PNG write, queued/current reader result, and the composed bitmap being produced next. */
internal const val ENHANCEMENT_COMPOSED_OUTPUT_SLOTS = 3L

internal fun calculateMaxInputPixels(maxMemoryBytes: Long, scale: Int = 2): Long {
    require(maxMemoryBytes > 0L) { "max memory must be positive" }
    require(scale > 0) { "scale must be positive" }
    val estimatedBytesPerInputPixel = estimatedInferenceBytesPerInputPixel(scale)
    val inferenceBudget = (maxMemoryBytes / INFERENCE_HEAP_DIVISOR)
        .coerceAtMost(MAX_INFERENCE_BYTES)
        .coerceAtLeast(estimatedBytesPerInputPixel)
    return inferenceBudget / estimatedBytesPerInputPixel
}

internal fun calculateBitmapCacheKilobytes(maxMemoryBytes: Long): Int {
    require(maxMemoryBytes > 0L) { "max memory must be positive" }
    return (maxMemoryBytes / CACHE_HEAP_DIVISOR)
        .coerceAtMost(MAX_CACHE_BYTES)
        .coerceAtLeast(MIN_CACHE_BYTES)
        .div(1024L)
        .toInt()
}

/**
 * Reserves the existing inference/cache estimates before allowing a composed strip bitmap.
 * A proportional margin is important here: a fixed few MiB leaves a 128 MiB process with no
 * room for the reader's current page and other Android allocations while a strip is composed.
 * The remaining output budget is split across the asynchronous writer, the queued/current reader
 * result, and the next result being composed or prefetched.
 * The result is a guardrail, not a proof of native-memory availability; OOM handling remains
 * required at allocation and inference boundaries.
 */
internal fun calculateEnhancementMemoryBudget(
    maxMemoryBytes: Long,
    scale: Int,
    maxComposedOutputBytes: Long = DEFAULT_MAX_STRIP_OUTPUT_BYTES,
): EnhancementMemoryBudget {
    require(maxMemoryBytes > 0L) { "max memory must be positive" }
    require(scale > 0) { "scale must be positive" }
    require(maxComposedOutputBytes > 0L) { "output budget must be positive" }

    val maxInputPixels = calculateMaxInputPixels(maxMemoryBytes, scale)
    val inferenceReserveBytes = saturatingMultiply(
        maxInputPixels,
        estimatedInferenceBytesPerInputPixel(scale),
    )
    val cacheReserveBytes = calculateBitmapCacheKilobytes(maxMemoryBytes).toLong() * 1024L
    val runtimeReserveBytes = MIN_ENHANCEMENT_RUNTIME_RESERVE_BYTES
        .coerceAtLeast(maxMemoryBytes / 4L)
        .coerceAtMost(maxMemoryBytes)
    val remainingForOutputs = subtractBudget(
        total = maxMemoryBytes,
        reserves = listOf(inferenceReserveBytes, cacheReserveBytes, runtimeReserveBytes),
    )
    val composedOutputBytes = remainingForOutputs / ENHANCEMENT_COMPOSED_OUTPUT_SLOTS

    return EnhancementMemoryBudget(
        maxInputPixels = maxInputPixels,
        inferenceReserveBytes = inferenceReserveBytes,
        cacheReserveBytes = cacheReserveBytes,
        runtimeReserveBytes = runtimeReserveBytes,
        composedOutputBytes = composedOutputBytes.coerceAtMost(maxComposedOutputBytes),
    )
}

internal fun estimatedInferenceBytesPerInputPixel(scale: Int): Long {
    require(scale > 0) { "scale must be positive" }
    val squared = saturatingMultiply(scale.toLong(), scale.toLong())
    val modelBytes = saturatingMultiply(8L, squared)
    return if (modelBytes == Long.MAX_VALUE || modelBytes > Long.MAX_VALUE - 16L) {
        Long.MAX_VALUE
    } else {
        16L + modelBytes
    }
}

internal fun saturatingMultiply(left: Long, right: Long): Long {
    if (left <= 0L || right <= 0L) return 0L
    if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE
    return left * right
}

private fun subtractBudget(total: Long, reserves: List<Long>): Long {
    var remaining = total
    for (reserve in reserves) {
        if (reserve >= remaining) return 0L
        remaining -= reserve
    }
    return remaining
}

private const val INFERENCE_HEAP_DIVISOR = 4L

// Keep the cache below the active inference budget so cached output cannot crowd out the
// input/output bitmaps and native buffers used by ncnn.
private const val CACHE_HEAP_DIVISOR = 10L
private const val MAX_INFERENCE_BYTES = 64L * 1024 * 1024
private const val MAX_CACHE_BYTES = 48L * 1024 * 1024
private const val MIN_CACHE_BYTES = 1L * 1024 * 1024
