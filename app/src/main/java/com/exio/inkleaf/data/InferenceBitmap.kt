// Shared pixel-budget sampling used by inference features before exact resizing.
//
// Decode uses a power-of-two inSampleSize for bounded peak memory, then the
// engine's continuous prepareInput clamps to the true pixel cap. Floor sampling
// keeps the decoded bitmap at least as large as that continuous target so the
// second step is not starved by an overly coarse first step.
//
// Peak memory (fast path) after resolution-budget eligibility + early source release:
// enhanceUncached recycles an oversized floor-decoded source once prepareInput has
// produced the continuous clamp (<=M). During nativeEnhance the simultaneous set is:
//   Java prepared input <= M px (4 B/px)
//   Java output <= M·scale² (4 B/px)
//   ncnn full-frame input Mat ≈ M·4 B (see enhancement_jni.cpp RGBA copy)
//   ncnn full-frame output Mat ≈ M·scale²·4 B
//   plus tiled workspace inside Real-ESRGAN / Real-CUGAN / Waifu2x
// Budget model calculateMaxInputPixels uses 16+8·scale² bytes per input pixel as a
// conservative estimate: full-frame Java prepared + Java output + ncnn input Mat +
// ncnn output Mat account for 8+8·scale²; the remaining ~8 bytes/px is assumed
// tile-workspace headroom and still needs on-device validation — not a proof.
// That model does NOT include a live floor-overshoot decode — hence early release.
// Floor inSampleSize may briefly allocate <4M px before release; do not add an
// arbitrary 2M decode hard-cap (it collapses floor sampling between 2-power rungs).
package com.exio.inkleaf.data

import kotlin.math.sqrt

/**
 * Smallest power-of-two sample size whose decoded pixel count is <= [maxPixels].
 * Used when an upper bound is the only requirement.
 */
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

/**
 * Largest power-of-two sample size whose decoded size still covers the continuous
 * budget target on both edges (floor sampling). Exact clamp stays in prepareInput.
 */
internal fun calculateFloorInferenceSampleSize(
    width: Int,
    height: Int,
    maxPixels: Long,
): Int {
    require(width > 0 && height > 0)
    require(maxPixels > 0)
    val sourcePixels = width.toLong() * height.toLong()
    if (sourcePixels <= maxPixels) return 1

    val ratio = sqrt(maxPixels.toDouble() / sourcePixels.toDouble())
    val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
    val targetHeight = (height * ratio).toInt().coerceAtLeast(1)

    var sampleSize = 1L
    while (true) {
        val next = sampleSize * 2L
        // Match calculateInferenceSampleSize ceil semantics for consistent rung choice.
        val nextWidth = (width.toLong() + next - 1L) / next
        val nextHeight = (height.toLong() + next - 1L) / next
        if (nextWidth < targetWidth || nextHeight < targetHeight || nextWidth < 1L || nextHeight < 1L) {
            return sampleSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        sampleSize = next
    }
}
