// Resolution-budget eligibility for the fast whole-page enhancement path.
//
// This is not an image-quality score. It only answers: under the current input
// pixel cap and model scale, can planned output pixels clearly exceed the
// source? If not, running SR is expected to waste work and often look softer
// than 原图 — so the page should keep the original render path.
package com.exio.inkleaf.data.enhancement

/** Why the fast enhancement path chose not to run inference. */
enum class EnhancementSkipReason {
    /** Planned output pixels cannot beat the source under the inference budget. */
    RESOLUTION_BUDGET,

    /** PDF keeps viewport-targeted original rendering in the first shipping cut. */
    PDF_UNSUPPORTED,

    /** Volume format is outside the fast whole-page raster path (not specifically PDF). */
    FAST_PATH_UNSUPPORTED,
}

/** Pure planning result before any enhancement decode or cache lookup. */
sealed interface EnhancementEligibility {
    data object Eligible : EnhancementEligibility

    data class Skipped(val reason: EnhancementSkipReason) : EnhancementEligibility
}

/** Default margin so equal pixel counts from a downsampled input still skip. */
const val DEFAULT_MIN_OUTPUT_TO_SOURCE_RATIO = 1.25

/**
 * Returns whether a raster page is eligible for fast whole-page SR.
 *
 * [sourceWidth]/[sourceHeight] must be the true source pixel size (file bounds),
 * not a memory-capped decode. PDF must not call this — use [EnhancementSkipReason.PDF_UNSUPPORTED].
 *
 * plannedOutput = min(sourcePixels, maxInputPixels) * scale²
 * eligible when plannedOutput / sourcePixels >= minOutputToSourceRatio
 */
fun evaluateResolutionBudgetEligibility(
    sourceWidth: Int,
    sourceHeight: Int,
    scale: Int,
    maxInputPixels: Long,
    minOutputToSourceRatio: Double = DEFAULT_MIN_OUTPUT_TO_SOURCE_RATIO,
): EnhancementEligibility {
    require(sourceWidth > 0 && sourceHeight > 0) {
        "source dimensions must be positive"
    }
    require(scale > 0) { "scale must be positive" }
    require(maxInputPixels > 0L) { "maxInputPixels must be positive" }
    require(minOutputToSourceRatio > 0.0) { "ratio must be positive" }

    val sourcePixels = sourceWidth.toLong() * sourceHeight.toLong()
    // Ratio form avoids overflowing min(S,M)*scale² for large pages / scales.
    val cappedInputPixels = minOf(sourcePixels, maxInputPixels).toDouble()
    val plannedToSource =
        (cappedInputPixels / sourcePixels.toDouble()) * scale.toDouble() * scale.toDouble()
    return if (plannedToSource >= minOutputToSourceRatio) {
        EnhancementEligibility.Eligible
    } else {
        EnhancementEligibility.Skipped(EnhancementSkipReason.RESOLUTION_BUDGET)
    }
}

fun EnhancementSkipReason.readerShortLabel(): String = "保留原图"

fun EnhancementSkipReason.readerDescription(): String = when (this) {
    EnhancementSkipReason.RESOLUTION_BUDGET ->
        "本页分辨率较高，当前增强模式无法增加足够的输出分辨率，已保留原图"

    EnhancementSkipReason.PDF_UNSUPPORTED ->
        "当前增强模式暂不处理 PDF，已保留原图"

    EnhancementSkipReason.FAST_PATH_UNSUPPORTED ->
        "当前增强模式暂不支持此页面格式，已保留原图"
}
