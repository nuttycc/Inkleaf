package com.exio.inkleaf.data.enhancement

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedBitmapCacheFormatTest {
    @Test
    fun headerRoundTripsBothBitmapConfigsAndLeavesPayloadReadable() {
        EnhancedBitmapCacheColorConfig.entries.forEach { expected ->
            val output = ByteArrayOutputStream()
            writeEnhancedBitmapCacheHeader(output, expected)
            output.write(byteArrayOf(7, 8, 9))

            val input = ByteArrayInputStream(output.toByteArray())
            assertEquals(
                EnhancedBitmapCacheHeader.Present(expected),
                readEnhancedBitmapCacheHeader(input),
            )
            assertTrue(input.readBytes().contentEquals(byteArrayOf(7, 8, 9)))
        }
    }

    @Test
    fun rawPngPayloadsRemainRecognizableAsLegacyEntries() {
        val input = ByteArrayInputStream(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))

        assertEquals(EnhancedBitmapCacheHeader.Missing, readEnhancedBitmapCacheHeader(input))
    }

    @Test
    fun recognizedMagicWithUnknownVersionIsInvalid() {
        val output = ByteArrayOutputStream()
        writeEnhancedBitmapCacheHeader(output, EnhancedBitmapCacheColorConfig.RGB_565)
        val bytes = output.toByteArray()
        bytes[4] = 99

        assertEquals(
            EnhancedBitmapCacheHeader.Invalid,
            readEnhancedBitmapCacheHeader(ByteArrayInputStream(bytes)),
        )
    }

    @Test
    fun recognizedMagicWithUnknownConfigIsInvalid() {
        val output = ByteArrayOutputStream()
        writeEnhancedBitmapCacheHeader(output, EnhancedBitmapCacheColorConfig.RGB_565)
        val bytes = output.toByteArray()
        bytes[5] = 99

        assertEquals(
            EnhancedBitmapCacheHeader.Invalid,
            readEnhancedBitmapCacheHeader(ByteArrayInputStream(bytes)),
        )
    }

    @Test
    fun recognizedMagicWithTruncatedHeaderIsInvalid() {
        val output = ByteArrayOutputStream()
        writeEnhancedBitmapCacheHeader(output, EnhancedBitmapCacheColorConfig.ARGB_8888)

        assertEquals(
            EnhancedBitmapCacheHeader.Invalid,
            readEnhancedBitmapCacheHeader(
                ByteArrayInputStream(output.toByteArray().copyOf(5)),
            ),
        )
    }
}
