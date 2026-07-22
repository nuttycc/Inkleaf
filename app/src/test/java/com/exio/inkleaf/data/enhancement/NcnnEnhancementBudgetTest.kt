package com.exio.inkleaf.data.enhancement

import com.exio.inkleaf.data.calculateFloorInferenceSampleSize
import com.exio.inkleaf.data.calculateInferenceSampleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class NcnnEnhancementBudgetTest {
    @Test
    fun sampleSizeStaysAtOneWhenImageFitsBudget() {
        assertEquals(1, calculateInferenceSampleSize(1_000, 800, 800_000))
        assertEquals(1, calculateFloorInferenceSampleSize(1_000, 800, 800_000))
    }

    @Test
    fun sampleSizeIsPowerOfTwoAndKeepsDecodedImageWithinBudget() {
        assertEquals(4, calculateInferenceSampleSize(8_000, 6_000, 3_000_000))
    }

    @Test
    fun sampleSizeUsesCeilingDimensionsForOddImages() {
        assertEquals(8, calculateInferenceSampleSize(9, 9, 8))
    }

    @Test
    fun floorSampleKeepsFullDecodeWhenCeilingWouldHalveEligibleOverBudgetPage() {
        // Eligible under G1 at 2× with M=1e6: S <= 4M/1.25 = 3.2e6.
        // 1600×2000 = 3.2e6 → continuous target fills M; ceiling sample is 2, floor is 1.
        val maxPixels = 1_000_000L
        assertEquals(
            EnhancementEligibility.Eligible,
            evaluateResolutionBudgetEligibility(1600, 2000, scale = 2, maxInputPixels = maxPixels),
        )
        assertEquals(2, calculateInferenceSampleSize(1600, 2000, maxPixels))
        assertEquals(1, calculateFloorInferenceSampleSize(1600, 2000, maxPixels))
    }

    @Test
    fun floorSampleCoversContinuousTargetEdgesForOverBudgetEligiblePage() {
        val width = 1600
        val height = 2000
        val maxPixels = 1_000_000L
        val sample = calculateFloorInferenceSampleSize(width, height, maxPixels)
        val ratio = sqrt(maxPixels.toDouble() / (width.toLong() * height.toLong()))
        val targetW = (width * ratio).toInt().coerceAtLeast(1)
        val targetH = (height * ratio).toInt().coerceAtLeast(1)
        val decodedW = (width + sample - 1) / sample
        val decodedH = (height + sample - 1) / sample
        assertTrue(decodedW >= targetW)
        assertTrue(decodedH >= targetH)
    }

    @Test
    fun floorSampleHandlesOddNearBudgetDimensions() {
        assertEquals(1, calculateFloorInferenceSampleSize(1001, 1001, 1001L * 1001L))
        val sample = calculateFloorInferenceSampleSize(1001, 1001, 200_000)
        assertTrue(sample >= 1)
        assertEquals(0, sample and (sample - 1)) // power of two
    }

    @Test
    fun lowHeapBudgetNeverExceedsOneQuarterOfHeap() {
        val heapBytes = 64L * 1024 * 1024

        assertEquals(heapBytes / 4L / 48L, calculateMaxInputPixels(heapBytes))
        assertEquals(heapBytes / 4L / 144L, calculateMaxInputPixels(heapBytes, scale = 4))
    }

    @Test
    fun largeHeapBudgetAndCacheAreCapped() {
        val heapBytes = 2L * 1024 * 1024 * 1024

        assertEquals(64L * 1024 * 1024 / 48L, calculateMaxInputPixels(heapBytes))
        assertEquals(64L * 1024 * 1024 / 144L, calculateMaxInputPixels(heapBytes, scale = 4))
        assertEquals(48 * 1024, calculateBitmapCacheKilobytes(heapBytes))
    }

    @Test
    fun cacheScalesWithHeapBeforeApplyingTheAbsoluteCap() {
        val heapBytes = 128L * 1024 * 1024

        assertEquals(
            (heapBytes / 10L / 1024L).toInt(),
            calculateBitmapCacheKilobytes(heapBytes),
        )
    }
}
