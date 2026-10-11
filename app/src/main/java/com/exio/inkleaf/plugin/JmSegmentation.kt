package com.exio.inkleaf.plugin

import java.security.MessageDigest

/**
 * De-segmentation of 18comic (禁漫天堂) page images, expressed as pure arithmetic so the block
 * layout can be unit-tested without an Android framework.
 *
 * The site stores a page as horizontal strips stacked bottom-up. Recovering the original needs
 * only the strip count, which the site derives from the chapter id and the image file name.
 */
internal object JmSegmentation {
    /** Chapter ids below the source-provided scramble id are served unscrambled. */
    const val FIXED_STRIP_CUTOFF = 268_850L

    /** 2023-02-08: the site dropped the ten-way variant in favour of an eight-way one. */
    const val WIDE_HASH_CUTOFF = 421_926L

    /**
     * Restack cost scales with decoded pixels, while the download cap only measures compressed
     * bytes, so a tall page can pass the byte limit and still exhaust the heap here. The reader
     * already refuses to rasterize past this budget for PDF pages.
     */
    const val MAX_RESTACK_PIXELS = 8_000_000L

    /** Guards the degenerate wide-or-tall shapes the pixel budget above would still admit. */
    const val MAX_RESTACK_DIMENSION = 8_192

    // MD5 is not a security primitive here: the site's own JavaScript derives the layout from this
    // exact digest, so any other algorithm produces a different strip count and mis-restacks.
    private val md5 = ThreadLocal.withInitial { MessageDigest.getInstance("MD5") }

    /**
     * Strip count for one image, or 0/1 when the bytes are already in the right order.
     *
     * The digest's last character is compared by ASCII code, not by hex value -- a quirk of the
     * site's own JavaScript that every client has to reproduce to get the same layout.
     */
    fun stripCount(scrambleId: Long, chapterId: Long, fileNameWithoutSuffix: String): Int {
        if (chapterId < scrambleId) return 0
        if (chapterId < FIXED_STRIP_CUTOFF) return 10
        val spread = if (chapterId < WIDE_HASH_CUTOFF) 10 else 8
        return (lastDigestCharCode("$chapterId$fileNameWithoutSuffix") % spread) * 2 + 2
    }

    /** File name without suffix, taken from the last path segment so query strings are ignored. */
    fun fileNameFromUrl(url: String): String {
        val lastSegment = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
        return lastSegment.substringBeforeLast('.', lastSegment)
    }

    /** Whether a decoded page this size can be restored without risking the whole process heap. */
    fun isRestackable(width: Int, height: Int): Boolean =
        width in 1..MAX_RESTACK_DIMENSION &&
            height in 1..MAX_RESTACK_DIMENSION &&
            width.toLong() * height.toLong() <= MAX_RESTACK_PIXELS

    /** Strips to copy, in destination order: the bottom source strip becomes the top row band. */
    fun restoreMoves(imageHeight: Int, stripCount: Int): List<JmSegmentMove> {
        if (imageHeight <= 0 || stripCount <= 1) return emptyList()
        val baseHeight = imageHeight / stripCount
        val remainder = imageHeight % stripCount
        return (0 until stripCount).map { index ->
            var height = baseHeight
            var targetY = baseHeight * index
            val sourceY = imageHeight - baseHeight * (index + 1) - remainder
            if (index == 0) {
                height += remainder
            } else {
                targetY += remainder
            }
            JmSegmentMove(sourceY = sourceY, targetY = targetY, height = height)
        }
    }

    private fun lastDigestCharCode(value: String): Int {
        val digest = md5.get()
        digest.reset()
        val lastNibble = digest.digest(value.toByteArray(Charsets.UTF_8)).last().toInt() and 0x0f
        val lastHexChar = if (lastNibble < 10) '0' + lastNibble else 'a' + (lastNibble - 10)
        return lastHexChar.code
    }
}

/** One horizontal strip: where it sits in the scrambled image and where it belongs. */
internal data class JmSegmentMove(val sourceY: Int, val targetY: Int, val height: Int)
