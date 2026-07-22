package com.exio.inkleaf.data.enhancement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementStripPlanTest {
    @Test
    fun stripsCoverFullHeightWithoutGaps() {
        val sourceWidth = 2000
        val sourceHeight = 3000
        val scale = 2
        val maxInputPixels = 1_000_000L
        val strips = planEnhancementStrips(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            scale = scale,
            maxInputPixels = maxInputPixels,
            overlapPx = 32,
        )
        assertTrue(strips.isNotEmpty())
        assertEquals(0, strips.first().outputCoreTop)
        var expectedSourceCoreTop = 0
        strips.forEach { strip ->
            val sourceCoreTop = strip.outputCoreTop / scale
            val sourceCoreHeight = strip.outputCoreHeight / scale
            assertTrue(sourceWidth.toLong() * strip.sourceHeight <= maxInputPixels)
            assertEquals(expectedSourceCoreTop, sourceCoreTop)
            assertEquals((sourceCoreTop - strip.sourceTop) * scale, strip.outputCropTop)
            assertTrue(strip.sourceTop <= sourceCoreTop)
            assertTrue(
                strip.sourceTop + strip.sourceHeight >= sourceCoreTop + sourceCoreHeight,
            )
            expectedSourceCoreTop += sourceCoreHeight
        }
        assertEquals(sourceHeight, expectedSourceCoreTop)
        assertTrue(strips.any { it.sourceHeight == 500 })
        assertEquals(
            strips.size,
            stripCountFor(sourceWidth, sourceHeight, maxInputPixels, 32),
        )
    }

    @Test
    fun largeOutputUsesRgb565WhenResidualBudgetIsBelowArgbSize() {
        val allocation = planStripOutputAllocation(
            2000,
            3000,
            scale = 2,
            maxOutputBytes = 64L * 1024 * 1024,
        )
        assertNotNull(allocation)
        assertEquals(StripOutputColorConfig.RGB_565, allocation!!.colorConfig)
    }

    @Test
    fun largeOutputUsesArgbWhenResidualBudgetAllowsIt() {
        val allocation = planStripOutputAllocation(
            2000,
            3000,
            scale = 2,
            maxOutputBytes = 96L * 1024 * 1024,
        )
        assertNotNull(allocation)
        assertEquals(StripOutputColorConfig.ARGB_8888, allocation!!.colorConfig)
    }

    @Test
    fun pageWiderThanInputBudgetCannotBeSplit() {
        assertTrue(
            planEnhancementStrips(
                sourceWidth = 2000,
                sourceHeight = 3000,
                scale = 2,
                maxInputPixels = 1999L,
            ).isEmpty(),
        )
        assertEquals(0, stripCountFor(2000, 3000, 1999L))
    }

    @Test
    fun oneRowBudgetDropsOverlapAndKeepsEveryStripWithinBudget() {
        val strips = planEnhancementStrips(
            sourceWidth = 10,
            sourceHeight = 5,
            scale = 2,
            maxInputPixels = 10L,
            overlapPx = 32,
        )

        assertEquals(5, strips.size)
        assertTrue(strips.all { it.sourceHeight == 1 })
        assertEquals(listOf(0, 2, 4, 6, 8), strips.map { it.outputCoreTop })
    }

    @Test
    fun pageThatFitsTheInputBudgetUsesOneStripWithoutOverlap() {
        val strips = planEnhancementStrips(
            sourceWidth = 100,
            sourceHeight = 100,
            scale = 2,
            maxInputPixels = 10_000L,
            overlapPx = 32,
        )

        assertEquals(
            listOf(
                EnhancementStrip(
                    sourceTop = 0,
                    sourceHeight = 100,
                    outputCoreTop = 0,
                    outputCoreHeight = 200,
                    outputCropTop = 0,
                ),
            ),
            strips,
        )
        assertEquals(1, stripCountFor(100, 100, 10_000L, 32))
    }

    @Test
    fun enormousPageCannotAllocateStripOutput() {
        assertNull(planStripOutputAllocation(20_000, 30_000, scale = 4))
    }

    @Test
    fun stripsReturnEmptyWhenScaledOutputOffsetsCannotFitInt() {
        assertTrue(
            planEnhancementStrips(
                sourceWidth = 1,
                sourceHeight = 2,
                scale = Int.MAX_VALUE,
                maxInputPixels = 2L,
            ).isEmpty(),
        )
    }

    @Test
    fun zeroResidualBudgetCannotAllocateStripOutput() {
        assertNull(planStripOutputAllocation(1, 1, scale = 2, maxOutputBytes = 0L))
    }

    @Test
    fun rgb565UsesTwoBytesPerPixelForFreshSoftwareBitmap() {
        val allocation = planStripOutputAllocation(
            sourceWidth = 1,
            sourceHeight = 1,
            scale = 1,
            maxOutputBytes = 2L,
        )

        assertEquals(StripOutputColorConfig.RGB_565, allocation?.colorConfig)
        assertEquals(2L, allocation?.byteCount)
    }

    @Test
    fun smallPageUsesArgb8888() {
        val allocation = planStripOutputAllocation(800, 1200, scale = 2)
        assertNotNull(allocation)
        assertEquals(StripOutputColorConfig.ARGB_8888, allocation!!.colorConfig)
    }
}
