package com.exio.inkleaf.data.enhancement

import java.io.InputStream
import java.io.OutputStream

internal enum class EnhancedBitmapCacheColorConfig(val code: Int) {
    ARGB_8888(1),
    RGB_565(2),
}

internal sealed interface EnhancedBitmapCacheHeader {
    data object Missing : EnhancedBitmapCacheHeader

    data object Invalid : EnhancedBitmapCacheHeader

    data class Present(val colorConfig: EnhancedBitmapCacheColorConfig) :
        EnhancedBitmapCacheHeader
}

internal const val ENHANCED_BITMAP_CACHE_HEADER_SIZE = 6

/** Prefixes the PNG payload with the allocation config required when decoding it again. */
internal fun writeEnhancedBitmapCacheHeader(
    output: OutputStream,
    colorConfig: EnhancedBitmapCacheColorConfig,
) {
    output.write(ENHANCED_BITMAP_CACHE_MAGIC)
    output.write(ENHANCED_BITMAP_CACHE_FORMAT_VERSION)
    output.write(colorConfig.code)
}

/** Reads a header from the current position. Callers must rewind when [Missing] is returned. */
internal fun readEnhancedBitmapCacheHeader(input: InputStream): EnhancedBitmapCacheHeader {
    val header = ByteArray(ENHANCED_BITMAP_CACHE_HEADER_SIZE)
    var offset = 0
    while (offset < header.size) {
        val read = input.read(header, offset, header.size - offset)
        if (read <= 0) break
        offset += read
    }
    if (offset < ENHANCED_BITMAP_CACHE_MAGIC.size) return EnhancedBitmapCacheHeader.Missing
    if (ENHANCED_BITMAP_CACHE_MAGIC.indices.any { header[it] != ENHANCED_BITMAP_CACHE_MAGIC[it] }) {
        return EnhancedBitmapCacheHeader.Missing
    }
    if (offset != header.size || header[4].toInt() != ENHANCED_BITMAP_CACHE_FORMAT_VERSION) {
        return EnhancedBitmapCacheHeader.Invalid
    }
    val config = EnhancedBitmapCacheColorConfig.entries
        .firstOrNull { it.code == header[5].toInt() }
        ?: return EnhancedBitmapCacheHeader.Invalid
    return EnhancedBitmapCacheHeader.Present(config)
}

private val ENHANCED_BITMAP_CACHE_MAGIC = byteArrayOf(
    'I'.code.toByte(),
    'N'.code.toByte(),
    'K'.code.toByte(),
    'L'.code.toByte(),
)
private const val ENHANCED_BITMAP_CACHE_FORMAT_VERSION = 1
