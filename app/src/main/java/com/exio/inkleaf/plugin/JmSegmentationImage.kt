package com.exio.inkleaf.plugin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream

/** Applies [JmSegmentation] to downloaded page bytes and returns an image the reader can decode. */
internal object JmSegmentationImage {
    private const val RECODE_QUALITY = 92

    /** Returns the restored image, or [bytes] unchanged when the page needs no work. */
    fun apply(bytes: ByteArray, chapterId: Long, scrambleId: Long, url: String): ByteArray {
        val strips =
            JmSegmentation.stripCount(
                scrambleId = scrambleId,
                chapterId = chapterId,
                fileNameWithoutSuffix = JmSegmentation.fileNameFromUrl(url),
            )
        // Animated pages cannot survive a re-encode, and a one-strip layout is already correct.
        if (strips <= 1 || isGif(bytes)) return bytes

        val source = decode(bytes) ?: return bytes
        try {
            if (source.height < strips) return bytes
            val restored = restack(source, strips) ?: return bytes
            try {
                return encode(restored)
            } finally {
                restored.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val options =
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun restack(source: Bitmap, strips: Int): Bitmap? {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val restored = IntArray(width * height)
        JmSegmentation.restoreMoves(height, strips).forEach { move ->
            System.arraycopy(
                pixels,
                move.sourceY * width,
                restored,
                move.targetY * width,
                move.height * width,
            )
        }
        return Bitmap.createBitmap(restored, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val format =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            }
        val output = ByteArrayOutputStream(bitmap.byteCount / 4)
        if (!bitmap.compress(format, RECODE_QUALITY, output)) {
            error("Image re-encode failed")
        }
        return output.toByteArray()
    }

    private fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte()
}
