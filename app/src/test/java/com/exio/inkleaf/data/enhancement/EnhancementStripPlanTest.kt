package com.exio.inkleaf.data.enhancement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementStripPlanTest {
    @Test
    fun stripsCoverFullHeightWithoutGaps() {
        val strips = planEnhancementStrips(
            sourceWidth = 2000,
            sourceHeight = 3000,
            scale = 2,
            maxInputPixels = 1_000_000L,
            overlapPx = 32,
        )
        assertTrue(strips.isNotEmpty())
        assertEquals(0, strips.first().outputCoreTop)
        val last = strips.last()
        assertEquals(3000 * 2, last.outputCoreTop + last.outputCoreHeight)
        strips.forEach { strip ->
            assertTrue(2000L * strip.sourceHeight <= 1_000_000L)
        }
    }

    @Test
    fun titleCaseOutputFitsRgb565Under96MiB() {
        val allocation = planStripOutputAllocation(2000, 3000, scale = 2)
        assertNotNull(allocation)
        assertEquals(StripOutputColorConfig.RGB_565, allocation!!.colorConfig)
    }

    @Test
    fun enormousPageCannotAllocateStripOutput() {
        assertNull(planStripOutputAllocation(20_000, 30_000, scale = 4))
    }

    @Test
    fun smallPageUsesArgb8888() {
        val allocation = planStripOutputAllocation(800, 1200, scale = 2)
        assertNotNull(allocation)
        assertEquals(StripOutputColorConfig.ARGB_8888, allocation!!.colorConfig)
    }
}
