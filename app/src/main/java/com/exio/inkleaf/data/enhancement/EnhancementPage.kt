package com.exio.inkleaf.data.enhancement

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asAndroidBitmap
import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.ComicVolume
import com.exio.inkleaf.data.PagePixelSize
import com.exio.inkleaf.data.ReaderPageCacheKey
import com.exio.inkleaf.data.calculateFloorInferenceSampleSize

/**
 * Bumped when fast-path preprocessing or eligibility rules change so disk/memory
 * keys miss old enhanced bitmaps without touching model artifact revisions.
 */
const val ENHANCEMENT_PIPELINE_REVISION = "2"

/** Pre-pipeline-key tasks/rows migrate to this so workers expire instead of skipping regen. */
const val ENHANCEMENT_PIPELINE_REVISION_LEGACY = "1"

data class EnhancementPageKey(
    val comicId: Long,
    val modelId: String,
    val modelRevision: String,
    val sourceRevision: String,
    val pipelineRevision: String,
    val value: String,
)

/** Pure cache-value builder — unit-tested without Android volume I/O. */
fun enhancementPageCacheValue(
    sourceKey: String,
    modelId: String,
    modelRevision: String,
    pipelineRevision: String = ENHANCEMENT_PIPELINE_REVISION,
): String = "$sourceKey@$modelId@$modelRevision@$pipelineRevision"

fun buildEnhancementPageKey(
    comicId: Long,
    volume: ComicVolume,
    page: Int,
    model: EnhancementModelDescriptor,
    pipelineRevision: String = ENHANCEMENT_PIPELINE_REVISION,
): EnhancementPageKey {
    val modelRevision = model.revision
    val sourceKey = ReaderPageCacheKey.forPage(
        cacheKeyPrefix = "comic-$comicId",
        page = page,
        pageIdentity = volume.pageIdentity(page),
        sourceRevision = volume.sourceRevision,
    )
    return EnhancementPageKey(
        comicId = comicId,
        modelId = model.id,
        modelRevision = modelRevision,
        sourceRevision = volume.sourceRevision,
        pipelineRevision = pipelineRevision,
        value = enhancementPageCacheValue(
            sourceKey = sourceKey,
            modelId = model.id,
            modelRevision = modelRevision,
            pipelineRevision = pipelineRevision,
        ),
    )
}

/** Volume-level plan before cache lookup or inference decode. */
sealed interface EnhancementPagePlan {
    data class Enhance(val sourceSize: PagePixelSize) : EnhancementPagePlan

    data class Skip(val reason: EnhancementSkipReason) : EnhancementPagePlan
}

/**
 * Decides whether the fast path may run. Never allocates the inference bitmap.
 * Non-raster volumes use [ComicVolume.fastRasterEnhancementSkipReason]
 * (PDF → [EnhancementSkipReason.PDF_UNSUPPORTED]).
 */
suspend fun planEnhancementPage(
    volume: ComicVolume,
    page: Int,
    scale: Int,
    maxInputPixels: Long = NcnnEnhancementEngine.maxInputPixels(scale),
): EnhancementPagePlan {
    if (!volume.supportsFastRasterEnhancement) {
        return EnhancementPagePlan.Skip(volume.fastRasterEnhancementSkipReason)
    }
    val size = volume.loadPageRasterSize(page)
        ?: throw ComicOpenException("本页图像无法解码")
    return when (
        val eligibility = evaluateResolutionBudgetEligibility(
            sourceWidth = size.width,
            sourceHeight = size.height,
            scale = scale,
            maxInputPixels = maxInputPixels,
        )
    ) {
        EnhancementEligibility.Eligible -> EnhancementPagePlan.Enhance(size)
        is EnhancementEligibility.Skipped -> EnhancementPagePlan.Skip(eligibility.reason)
    }
}

suspend fun loadEnhancementSourceBitmap(
    volume: ComicVolume,
    page: Int,
    scale: Int,
): Bitmap {
    val maxPixels = NcnnEnhancementEngine.maxInputPixels(scale)
    try {
        volume.loadPageBitmapForInference(page, maxPixels)?.let {
            return it.asAndroidBitmap()
        }
        val bytes = volume.loadPageBytes(page)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ComicOpenException("本页图像无法解码")
        }

        // Floor power-of-two decode stays >= continuous target; prepareInput clamps.
        val sampleSize = calculateFloorInferenceSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxPixels = maxPixels,
        )
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: throw ComicOpenException("本页图像无法解码")
    } catch (_: OutOfMemoryError) {
        throw ComicOpenException("设备内存不足，无法增强本页")
    }
}
