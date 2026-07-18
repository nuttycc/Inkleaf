// Loads a bounded, orientation-correct page bitmap without creating a lossy intermediate file.
package com.exio.inkleaf.data.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.ui.graphics.asAndroidBitmap
import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.ComicVolume
import com.exio.inkleaf.data.calculateInferenceSampleSize
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal const val OCR_MAX_LONG_EDGE = 1600
private const val OCR_MAX_PIXELS = OCR_MAX_LONG_EDGE.toLong() * OCR_MAX_LONG_EDGE
private const val OCR_DECODE_MAX_PIXELS = OCR_MAX_PIXELS * 4

suspend fun loadOcrPageBitmap(
    volume: ComicVolume,
    page: Int,
): Bitmap {
    try {
        volume.loadPageBitmapForInference(page, OCR_MAX_PIXELS)?.let { image ->
            return image.asAndroidBitmap().scaledForOcr()
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
            maxPixels = OCR_DECODE_MAX_PIXELS,
        )
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: throw ComicOpenException("本页图像无法解码")

        return decoded.orientedByExif(bytes).scaledForOcr()
    } catch (_: OutOfMemoryError) {
        throw ComicOpenException("设备内存不足，无法识别本页文字")
    }
}

private fun Bitmap.scaledForOcr(): Bitmap {
    val longEdge = max(width, height)
    if (longEdge <= OCR_MAX_LONG_EDGE) return this
    val scale = OCR_MAX_LONG_EDGE.toFloat() / longEdge
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    var scaled: Bitmap? = null
    try {
        val created = Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
        scaled = created
        return created
    } finally {
        if (scaled == null || scaled !== this) recycle()
    }
}

private fun Bitmap.orientedByExif(bytes: ByteArray): Bitmap {
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return this
    }

    var oriented: Bitmap? = null
    try {
        val created = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        oriented = created
        return created
    } finally {
        if (oriented == null || oriented !== this) recycle()
    }
}
