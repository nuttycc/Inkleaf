package com.exio.inkleaf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfRenderSizeTest {
    @Test
    fun `A4 page fits a 1080 by 1920 viewport at physical pixel resolution`() {
        val size =
            calculatePdfRenderSize(
                pageWidthPoints = 595,
                pageHeightPoints = 842,
                request = request(1080, 1920),
            )

        assertEquals(1080, size.width)
        assertEquals(1528, size.height)
    }

    @Test
    fun `zoomed render preserves aspect ratio while respecting both caps`() {
        val size =
            calculatePdfRenderSize(
                pageWidthPoints = 595,
                pageHeightPoints = 842,
                request = request(3240, 5760),
            )

        assertTrue(size.width.toLong() * size.height <= 8_000_000L)
        assertTrue(maxOf(size.width, size.height) <= 4096)
        assertEquals(595.0 / 842.0, size.width.toDouble() / size.height, 0.001)
    }

    @Test
    fun `wide and unusually long pages fit without exceeding allocation budget`() {
        listOf(842 to 595, 300 to 3000).forEach { (width, height) ->
            val size =
                calculatePdfRenderSize(
                    pageWidthPoints = width,
                    pageHeightPoints = height,
                    request = request(6000, 6000),
                )

            assertTrue(size.width.toLong() * size.height <= 8_000_000L)
            assertTrue(maxOf(size.width, size.height) <= 4096)
        }
    }

    @Test
    fun `invalid PDF dimensions still produce an allocatable bitmap size`() {
        val size =
            calculatePdfRenderSize(
                pageWidthPoints = 0,
                pageHeightPoints = -1,
                request = request(1080, 1920),
            )

        assertTrue(size.width >= 1)
        assertTrue(size.height >= 1)
    }

    private fun request(width: Int, height: Int) =
        PageRenderRequest(
            maxWidthPx = width,
            maxHeightPx = height,
            maxPixels = 8_000_000,
            maxDimensionPx = 4096,
        )
}
