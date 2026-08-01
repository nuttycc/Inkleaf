package com.exio.inkleaf.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryExportTest {
    @Test
    fun `image extension detected from magic bytes with jpeg fallback`() {
        assertEquals("jpg", imageExtension(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals(
            "png",
            imageExtension(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        )
        assertEquals(
            "webp",
            imageExtension("RIFFxxxxWEBP".toByteArray())
        )
        assertEquals("gif", imageExtension("GIF89a".toByteArray()))
        assertEquals("jpg", imageExtension(byteArrayOf(0, 1, 2, 3)))
        assertEquals("jpg", imageExtension(ByteArray(0)))
    }

    @Test
    fun `sanitized file name replaces unsafe characters keeps unicode and caps length`() {
        assertEquals("旅行_夏天_", sanitizeFileName("旅行:夏天?"))
        assertEquals("Inkleaf", sanitizeFileName(""))
        assertEquals("Inkleaf", sanitizeFileName("  \t "))
        assertEquals(48, sanitizeFileName("长".repeat(100)).length)
    }
}
