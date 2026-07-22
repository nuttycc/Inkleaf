// Strip geometry for full-resolution tiled enhancement (#23).
//
// Whole-page pre-downscale is avoided by decoding/rendering only one input strip
// at a time. Overlap is model-dependent; 32px is the initial tested default and
// lives in the pipeline revision when changed.
package com.exio.inkleaf.data.enhancement

import kotlin.math.ceil
import kotlin.math.max

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

internal data class EnhancementStripGeometry(
    val overlap: Int,
    val coreHeight: Int,
)

internal fun enhancementStripGeometry(
    sourceWidth: Int,
    sourceHeight: Int,
    maxInputPixels: Long,
    overlapPx: Int = DEFAULT_ENHANCEMENT_STRIP_OVERLAP_PX,
): EnhancementStripGeometry? {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(maxInputPixels > 0L)
    require(overlapPx >= 0)

    val maxSourceHeight = minOf(
        sourceHeight.toLong(),
        maxInputPixels / sourceWidth.toLong(),
    ).toInt()
    if (maxSourceHeight < 1) return null

    val overlap = if (maxSourceHeight == sourceHeight) {
        0
    } else {
        minOf(overlapPx, (maxSourceHeight - 1) / 2)
    }
    return EnhancementStripGeometry(
        overlap = overlap,
        coreHeight = maxSourceHeight - 2 * overlap,
    )
}

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

    val geometry = enhancementStripGeometry(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        maxInputPixels = maxInputPixels,
        overlapPx = overlapPx,
    ) ?: return emptyList()
    val overlap = geometry.overlap
    val coreHeight = geometry.coreHeight
    if (saturatingMultiply(sourceHeight.toLong(), scale.toLong()) > Int.MAX_VALUE) {
        return emptyList()
    }
    val strips = ArrayList<EnhancementStrip>()
    var coreTop = 0
    while (coreTop < sourceHeight) {
        val coreBottom = minOf(
            sourceHeight.toLong(),
            coreTop.toLong() + coreHeight.toLong(),
        ).toInt()
        val thisCoreHeight = coreBottom - coreTop
        val srcTop = max(0, coreTop - if (coreTop == 0) 0 else overlap)
        val srcBottom = minOf(
            sourceHeight.toLong(),
            coreBottom.toLong() + if (coreBottom >= sourceHeight) 0L else overlap.toLong(),
        ).toInt()
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
    require(maxOutputBytes >= 0L)
    if (maxOutputBytes == 0L) return null
    val outputWidth = saturatingMultiply(sourceWidth.toLong(), scale.toLong())
    val outputHeight = saturatingMultiply(sourceHeight.toLong(), scale.toLong())
    if (
        outputWidth <= 0L ||
        outputHeight <= 0L ||
        outputWidth > Int.MAX_VALUE ||
        outputHeight > Int.MAX_VALUE
    ) {
        return null
    }
    val argb = bitmapAllocationBytes(outputWidth, outputHeight, bytesPerPixel = 4)
    if (argb != null && argb <= maxOutputBytes && argb <= Int.MAX_VALUE) {
        return StripOutputAllocation(StripOutputColorConfig.ARGB_8888, argb)
    }
    val rgb565 = bitmapAllocationBytes(outputWidth, outputHeight, bytesPerPixel = 2)
    if (rgb565 != null && rgb565 <= maxOutputBytes && rgb565 <= Int.MAX_VALUE) {
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
    val geometry = enhancementStripGeometry(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        maxInputPixels = maxInputPixels,
        overlapPx = overlapPx,
    ) ?: return 0
    return ceil(sourceHeight.toDouble() / geometry.coreHeight.toDouble()).toInt()
}

private fun bitmapAllocationBytes(
    width: Long,
    height: Long,
    bytesPerPixel: Int,
): Long? {
    // Bitmap.createBitmap() uses SkImageInfo's minimum row bytes for a fresh software bitmap.
    val pixels = saturatingMultiply(width, height)
    if (pixels == Long.MAX_VALUE) return null
    val allocation = saturatingMultiply(pixels, bytesPerPixel.toLong())
    return allocation.takeUnless { it == Long.MAX_VALUE }
}
