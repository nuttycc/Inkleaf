package com.exio.inkleaf.data.enhancement

import org.junit.Assert.assertEquals
import org.junit.Test

class NcnnEnhancementBudgetTest {
    @Test
    fun sampleSizeStaysAtOneWhenImageFitsBudget() {
        assertEquals(1, calculateInferenceSampleSize(1_000, 800, 800_000))
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
        assertEquals(24 * 1024, calculateBitmapCacheKilobytes(heapBytes))
    }
}
