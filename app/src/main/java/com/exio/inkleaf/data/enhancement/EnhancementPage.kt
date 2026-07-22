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
const val ENHANCEMENT_PIPELINE_REVISION = "3"

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
    /** Whole-page path; may floor-sample then continuous-clamp to the input budget. */
    data class EnhanceFast(val sourceSize: PagePixelSize) : EnhancementPagePlan

    /** Full-resolution horizontal strips — no whole-page pre-downscale (#23). */
    data class EnhanceStrips(val sourceSize: PagePixelSize) : EnhancementPagePlan

    data class Skip(val reason: EnhancementSkipReason) : EnhancementPagePlan
}

/**
 * Decides whether enhancement may run. Never allocates the inference bitmap.
 * Non-raster volumes use [ComicVolume.fastRasterEnhancementSkipReason]
 * (PDF → [EnhancementSkipReason.PDF_UNSUPPORTED]).
 *
 * Order: fast budget win → full-res strips if output fits → skip.
 */
suspend fun planEnhancementPage(
    volume: ComicVolume,
    page: Int,
    scale: Int,
    maxInputPixels: Long = NcnnEnhancementEngine.maxInputPixels(scale),
): EnhancementPagePlan {
    val size = volume.loadPageRasterSize(page)
    if (size == null) {
        return if (!volume.supportsFastRasterEnhancement) {
            EnhancementPagePlan.Skip(volume.fastRasterEnhancementSkipReason)
        } else {
            throw ComicOpenException("本页图像无法解码")
        }
    }
    if (volume.supportsFastRasterEnhancement) {
        when (
            evaluateResolutionBudgetEligibility(
                sourceWidth = size.width,
                sourceHeight = size.height,
                scale = scale,
                maxInputPixels = maxInputPixels,
            )
        ) {
            EnhancementEligibility.Eligible -> return EnhancementPagePlan.EnhanceFast(size)
            is EnhancementEligibility.Skipped -> Unit
        }
    }
    // Full-res / baseline strips when region load exists and composed output fits heap.
    if (
        volume.supportsPageRegionLoad &&
        planStripOutputAllocation(size.width, size.height, scale) != null
    ) {
        return EnhancementPagePlan.EnhanceStrips(size)
    }
    return EnhancementPagePlan.Skip(
        if (volume.supportsFastRasterEnhancement) {
            EnhancementSkipReason.RESOLUTION_BUDGET
        } else {
            volume.fastRasterEnhancementSkipReason
        },
    )
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
