package com.exio.inkleaf.data.enhancement

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asAndroidBitmap
import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.ComicVolume
import com.exio.inkleaf.data.ReaderPageCacheKey
import com.exio.inkleaf.data.calculateInferenceSampleSize

data class EnhancementPageKey(
    val comicId: Long,
    val modelId: String,
    val modelRevision: String,
    val sourceRevision: String,
    val value: String,
)

fun buildEnhancementPageKey(
    comicId: Long,
    volume: ComicVolume,
    page: Int,
    model: EnhancementModelDescriptor,
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
        value = "$sourceKey@${model.id}@$modelRevision",
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

        val sampleSize = calculateInferenceSampleSize(
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
