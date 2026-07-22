package com.exio.inkleaf.data.enhancement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementEligibilityTest {
    private val capped64MiBInput2x = calculateMaxInputPixels(2L * 1024 * 1024 * 1024, scale = 2)
    private val capped64MiBInput4x = calculateMaxInputPixels(2L * 1024 * 1024 * 1024, scale = 4)

    @Test
    fun titleCase2000x3000At2xFailsFastBudgetButStripOutputFits() {
        val result = evaluateResolutionBudgetEligibility(
            sourceWidth = 2000,
            sourceHeight = 3000,
            scale = 2,
            maxInputPixels = capped64MiBInput2x,
        )
        assertEquals(
            EnhancementEligibility.Skipped(EnhancementSkipReason.RESOLUTION_BUDGET),
            result,
        )
        // #23 strip path still applies when composed output fits (RGB_565 under 96 MiB).
        assertNotNull(planStripOutputAllocation(2000, 3000, scale = 2))
    }

    @Test
    fun smallRasterPageIsEligible() {
        val result = evaluateResolutionBudgetEligibility(
            sourceWidth = 800,
            sourceHeight = 1200,
            scale = 2,
            maxInputPixels = capped64MiBInput2x,
        )
        assertEquals(EnhancementEligibility.Eligible, result)
    }

    @Test
    fun exactRatioBoundaryIsEligible() {
        // planned = min(S,M)*4; need planned/S = 1.25 ⇒ S = 4M/1.25 when S > M
        val maxInput = 1_000_000L
        val sourcePixels = (maxInput * 4L * 100L) / 125L
        val width = 1000
        val height = (sourcePixels / width).toInt()
        val result = evaluateResolutionBudgetEligibility(
            sourceWidth = width,
            sourceHeight = height,
            scale = 2,
            maxInputPixels = maxInput,
            minOutputToSourceRatio = 1.25,
        )
        assertEquals(EnhancementEligibility.Eligible, result)
    }

    @Test
    fun justBelowRatioBoundaryIsSkipped() {
        val maxInput = 1_000_000L
        val sourcePixels = (maxInput * 4L * 100L) / 125L + 1L
        val width = 1000
        val height = ((sourcePixels + width - 1) / width).toInt()
        val result = evaluateResolutionBudgetEligibility(
            sourceWidth = width,
            sourceHeight = height,
            scale = 2,
            maxInputPixels = maxInput,
            minOutputToSourceRatio = 1.25,
        )
        assertTrue(result is EnhancementEligibility.Skipped)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveDimensions() {
        evaluateResolutionBudgetEligibility(0, 100, scale = 2, maxInputPixels = 1_000_000)
    }

    @Test
    fun extremeDimensionsDoNotOverflowEligibilityMath() {
        val result = evaluateResolutionBudgetEligibility(
            sourceWidth = Int.MAX_VALUE,
            sourceHeight = Int.MAX_VALUE,
            scale = Int.MAX_VALUE,
            maxInputPixels = Long.MAX_VALUE,
        )
        // planned/source = scale² which is huge → eligible under double math
        assertEquals(EnhancementEligibility.Eligible, result)
    }

    @Test
    fun largePageAt4xIsSkippedUnderCappedBudget() {
        // 2480×3508 ≈ 8.7M — classic “4× worse” observation under ~0.47M input cap.
        val result = evaluateResolutionBudgetEligibility(
            sourceWidth = 2480,
            sourceHeight = 3508,
            scale = 4,
            maxInputPixels = capped64MiBInput4x,
        )
        assertEquals(
            EnhancementEligibility.Skipped(EnhancementSkipReason.RESOLUTION_BUDGET),
            result,
        )
    }

    @Test
    fun fourXBoundaryJustEligibleWhenOutputMeetsMargin() {
        val maxInput = capped64MiBInput4x
        // S = floor(M * 16 / 1.25) so planned/S ≈ 1.25 when S > M
        val sourcePixels = (maxInput * 16L * 100L) / 125L
        val width = 2000
        val height = (sourcePixels / width).toInt().coerceAtLeast(1)
        val result = evaluateResolutionBudgetEligibility(
            sourceWidth = width,
            sourceHeight = height,
            scale = 4,
            maxInputPixels = maxInput,
        )
        assertEquals(EnhancementEligibility.Eligible, result)
    }

    @Test
    fun fourXBoundaryJustSkippedWhenOutputBelowMargin() {
        val maxInput = capped64MiBInput4x
        val sourcePixels = (maxInput * 16L * 100L) / 125L + maxInput
        val width = 2000
        val height = ((sourcePixels + width - 1) / width).toInt()
        val result = evaluateResolutionBudgetEligibility(
            sourceWidth = width,
            sourceHeight = height,
            scale = 4,
            maxInputPixels = maxInput,
        )
        assertTrue(result is EnhancementEligibility.Skipped)
    }
}
