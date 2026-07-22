// Strip geometry for full-resolution tiled enhancement (#23).
//
// Whole-page pre-downscale is avoided by decoding/rendering only one input strip
// at a time. Overlap is model-dependent; 32px is the initial tested default and
// lives in the pipeline revision when changed.
package com.exio.inkleaf.data.enhancement

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Default input-side overlap between consecutive horizontal strips (pixels). */
const val DEFAULT_ENHANCEMENT_STRIP_OVERLAP_PX = 32

/** Soft cap for a single composed strip-enhancement output bitmap. */
const val DEFAULT_MAX_STRIP_OUTPUT_BYTES = 96L * 1024 * 1024

data class EnhancementStrip(
    /** Inclusive top of the source strip including leading overlap. */
    val sourceTop: Int,
    /** Height of the source strip including overlaps. */
    val sourceHeight: Int,
    /** Y offset in the full output bitmap where the non-overlap core starts. */
    val outputCoreTop: Int,
    /** Height of the non-overlap core in output pixels. */
    val outputCoreHeight: Int,
    /** Rows to skip from the top of the enhanced strip bitmap (overlap half). */
    val outputCropTop: Int,
)

enum class StripOutputColorConfig {
    ARGB_8888,
    RGB_565,
}

data class StripOutputAllocation(
    val colorConfig: StripOutputColorConfig,
    val byteCount: Long,
)

/**
 * Builds horizontal strips so each source strip has at most [maxInputPixels] pixels.
 * [overlapPx] is applied on the source; output crops the leading scaled overlap.
 */
fun planEnhancementStrips(
    sourceWidth: Int,
    sourceHeight: Int,
    scale: Int,
    maxInputPixels: Long,
    overlapPx: Int = DEFAULT_ENHANCEMENT_STRIP_OVERLAP_PX,
): List<EnhancementStrip> {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(scale > 0)
    require(maxInputPixels > 0L)
    require(overlapPx >= 0)

    val maxStripHeight = max(
        1,
        min(
            sourceHeight.toLong(),
            maxInputPixels / sourceWidth.toLong(),
        ).toInt(),
    )
    val overlap = min(overlapPx, (maxStripHeight - 1).coerceAtLeast(0))
    val coreHeight = max(1, maxStripHeight - overlap)
    val strips = ArrayList<EnhancementStrip>()
    var coreTop = 0
    while (coreTop < sourceHeight) {
        val coreBottom = min(sourceHeight, coreTop + coreHeight)
        val thisCoreHeight = coreBottom - coreTop
        val srcTop = max(0, coreTop - if (coreTop == 0) 0 else overlap)
        val srcBottom = min(
            sourceHeight,
            coreBottom + if (coreBottom >= sourceHeight) 0 else overlap,
        )
        val srcHeight = srcBottom - srcTop
        val cropTop = (coreTop - srcTop) * scale
        strips += EnhancementStrip(
            sourceTop = srcTop,
            sourceHeight = srcHeight,
            outputCoreTop = coreTop * scale,
            outputCoreHeight = thisCoreHeight * scale,
            outputCropTop = cropTop,
        )
        if (coreBottom >= sourceHeight) break
        coreTop = coreBottom
    }
    return strips
}

/**
 * Picks an output config that can hold scale²·W·H, or null when even RGB_565 exceeds the cap.
 */
fun planStripOutputAllocation(
    sourceWidth: Int,
    sourceHeight: Int,
    scale: Int,
    maxOutputBytes: Long = DEFAULT_MAX_STRIP_OUTPUT_BYTES,
): StripOutputAllocation? {
    require(sourceWidth > 0 && sourceHeight > 0 && scale > 0)
    val pixels = sourceWidth.toLong() * sourceHeight.toLong() * scale.toLong() * scale.toLong()
    if (pixels <= 0L) return null
    val argb = pixels * 4L
    if (argb <= maxOutputBytes && argb <= Int.MAX_VALUE) {
        return StripOutputAllocation(StripOutputColorConfig.ARGB_8888, argb)
    }
    val rgb565 = pixels * 2L
    if (rgb565 <= maxOutputBytes && rgb565 <= Int.MAX_VALUE) {
        return StripOutputAllocation(StripOutputColorConfig.RGB_565, rgb565)
    }
    return null
}

fun stripCountFor(
    sourceWidth: Int,
    sourceHeight: Int,
    maxInputPixels: Long,
    overlapPx: Int = DEFAULT_ENHANCEMENT_STRIP_OVERLAP_PX,
): Int {
    val maxStripHeight = max(
        1,
        (maxInputPixels / sourceWidth.toLong()).toInt().coerceAtMost(sourceHeight),
    )
    val overlap = min(overlapPx, (maxStripHeight - 1).coerceAtLeast(0))
    val core = max(1, maxStripHeight - overlap)
    return ceil(sourceHeight.toDouble() / core.toDouble()).toInt()
}
