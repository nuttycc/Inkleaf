// Streams source-resolution page regions so OCR never needs a complete page bitmap in memory.
package com.exio.inkleaf.data.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.ComicVolume
import java.io.ByteArrayInputStream

internal interface OcrPageSource : AutoCloseable {
    val width: Int
    val height: Int

    suspend fun load(bounds: OcrTileBounds): Bitmap
}

internal suspend fun openOcrPageSource(volume: ComicVolume, page: Int): OcrPageSource {
    volume.ocrPageSize(page)?.let { size ->
        return object : OcrPageSource {
            override val width = size.width
            override val height = size.height

            override suspend fun load(bounds: OcrTileBounds): Bitmap =
                volume.loadOcrPageRegion(
                    globalPage = page,
                    left = bounds.left,
                    top = bounds.top,
                    width = bounds.width,
                    height = bounds.height,
                ) ?: throw ComicOpenException("本页图像无法解码")

            override fun close() = Unit
        }
    }

    val bytes = volume.loadPageBytes(page)
    return RegionDecodedOcrPageSource(bytes)
}

@Suppress("DEPRECATION")
private class RegionDecodedOcrPageSource(bytes: ByteArray) : OcrPageSource {
    private val decoder =
        BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
    private val sourceWidth = decoder.width
    private val sourceHeight = decoder.height
    private val orientation =
        runCatching {
                ExifInterface(ByteArrayInputStream(bytes))
                    .getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
            }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    override val width: Int = if (orientation.swapsDimensions()) sourceHeight else sourceWidth
    override val height: Int = if (orientation.swapsDimensions()) sourceWidth else sourceHeight

    override suspend fun load(bounds: OcrTileBounds): Bitmap {
        val mapped = mapOrientedRectToSource(bounds, orientation, sourceWidth, sourceHeight)
        val sourceRect = Rect(mapped.left, mapped.top, mapped.right, mapped.bottom)
        val decoded =
            decoder.decodeRegion(
                sourceRect,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            ) ?: throw ComicOpenException("本页图像无法解码")
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return decoded

        return try {
            Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                rawOrientationMatrix(orientation),
                true,
            )
        } finally {
            decoded.recycle()
        }
    }

    override fun close() {
        decoder.recycle()
    }
}

internal data class OcrPixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun mapOrientedRectToSource(
    bounds: OcrTileBounds,
    orientation: Int,
    sourceWidth: Int,
    sourceHeight: Int,
): OcrPixelRect {
    val left = bounds.left
    val top = bounds.top
    val right = bounds.left + bounds.width
    val bottom = bounds.top + bounds.height
    return when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
            OcrPixelRect(
                sourceWidth - right,
                top,
                sourceWidth - left,
                bottom,
            )

        ExifInterface.ORIENTATION_ROTATE_180 ->
            OcrPixelRect(
                sourceWidth - right,
                sourceHeight - bottom,
                sourceWidth - left,
                sourceHeight - top,
            )

        ExifInterface.ORIENTATION_FLIP_VERTICAL ->
            OcrPixelRect(
                left,
                sourceHeight - bottom,
                right,
                sourceHeight - top,
            )

        ExifInterface.ORIENTATION_TRANSPOSE -> OcrPixelRect(top, left, bottom, right)
        ExifInterface.ORIENTATION_ROTATE_90 ->
            OcrPixelRect(
                top,
                sourceHeight - right,
                bottom,
                sourceHeight - left,
            )

        ExifInterface.ORIENTATION_TRANSVERSE ->
            OcrPixelRect(
                sourceWidth - bottom,
                sourceHeight - right,
                sourceWidth - top,
                sourceHeight - left,
            )

        ExifInterface.ORIENTATION_ROTATE_270 ->
            OcrPixelRect(
                sourceWidth - bottom,
                left,
                sourceWidth - top,
                right,
            )

        else -> OcrPixelRect(left, top, right, bottom)
    }
}

private fun Int.swapsDimensions(): Boolean =
    this == ExifInterface.ORIENTATION_TRANSPOSE ||
        this == ExifInterface.ORIENTATION_ROTATE_90 ||
        this == ExifInterface.ORIENTATION_TRANSVERSE ||
        this == ExifInterface.ORIENTATION_ROTATE_270

private fun rawOrientationMatrix(orientation: Int): Matrix =
    Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
