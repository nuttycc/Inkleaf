package com.exio.inkleaf.plugin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import com.exio.inkleaf.data.ComicOpenException
import java.io.ByteArrayOutputStream

/** Applies [JmSegmentation] to downloaded page bytes and returns an image the reader can decode. */
internal object JmSegmentationImage {
    private const val RECODE_QUALITY = 92

    /**
     * Returns the restored image, or [bytes] unchanged when the page needs no work.
     *
     * Fails with [ComicOpenException] when a page that does need work is too large to restore: its
     * scrambled bytes would still decode, so returning them would show a broken page as if it were
     * valid.
     */
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
            // Unlike the other early returns, this one cannot fall back to [bytes]: scrambled
            // bytes of an oversized page still decode, so the reader would show them as valid.
            if (!JmSegmentation.isRestackable(source.width, source.height)) {
                throw ComicOpenException("页面图像过大，无法还原")
            }
            if (source.height < strips) return bytes
            val restored = restack(source, strips)
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

    private fun restack(source: Bitmap, strips: Int): Bitmap {
        val width = source.width
        val height = source.height
        val restored = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Every strip is a full-width row band copied at 1:1 scale, so a blit relocates it without
        // the two full-image pixel buffers a per-pixel copy would put on the Java heap.
        val canvas = Canvas(restored)
        JmSegmentation.restoreMoves(height, strips).forEach { move ->
            canvas.drawBitmap(
                source,
                Rect(0, move.sourceY, width, move.sourceY + move.height),
                Rect(0, move.targetY, width, move.targetY + move.height),
                null,
            )
        }
        return restored
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
