// Shared pixel-budget sampling used by inference features before exact resizing.
package com.exio.inkleaf.data

internal fun calculateInferenceSampleSize(
    width: Int,
    height: Int,
    maxPixels: Long,
): Int {
    require(width > 0 && height > 0)
    require(maxPixels > 0)
    var sampleSize = 1L
    while (true) {
        val sampledWidth = (width.toLong() + sampleSize - 1L) / sampleSize
        val sampledHeight = (height.toLong() + sampleSize - 1L) / sampleSize
        if (sampledWidth * sampledHeight <= maxPixels) {
            return sampleSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        sampleSize *= 2L
    }
}
