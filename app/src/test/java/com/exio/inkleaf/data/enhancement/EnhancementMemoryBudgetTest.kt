package com.exio.inkleaf.data.enhancement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementMemoryBudgetTest {
    @Test
    fun oneHundredTwentyEightMiBHeapCannotHoldConcurrentTitlePageOutputs() {
        val heapBytes = 128L * 1024 * 1024
        val budget = calculateEnhancementMemoryBudget(
            maxMemoryBytes = heapBytes,
            scale = 2,
        )

        assertEquals(heapBytes / 4L, budget.runtimeReserveBytes)
        assertTrue(
            budget.inferenceReserveBytes +
                budget.cacheReserveBytes +
                budget.runtimeReserveBytes +
                budget.composedOutputBytes * ENHANCEMENT_COMPOSED_OUTPUT_SLOTS <= heapBytes,
        )
        assertTrue(budget.composedOutputBytes < 96L * 1024 * 1024)
        assertNull(
            planStripOutputAllocation(
                sourceWidth = 2000,
                sourceHeight = 3000,
                scale = 2,
                maxOutputBytes = budget.composedOutputBytes,
            ),
        )
    }

    @Test
    fun threeHundredEightyFourMiBHeapCanHoldConcurrentRgb565TitlePageOutputs() {
        val budget = calculateEnhancementMemoryBudget(
            maxMemoryBytes = 384L * 1024 * 1024,
            scale = 2,
        )

        val allocation = planStripOutputAllocation(
            sourceWidth = 2000,
            sourceHeight = 3000,
            scale = 2,
            maxOutputBytes = budget.composedOutputBytes,
        )
        assertEquals(StripOutputColorConfig.RGB_565, allocation?.colorConfig)
    }

    @Test
    fun smallHeapStillKeepsTheMinimumRuntimeMargin() {
        val budget = calculateEnhancementMemoryBudget(
            maxMemoryBytes = 8L * 1024 * 1024,
            scale = 2,
        )

        assertEquals(MIN_ENHANCEMENT_RUNTIME_RESERVE_BYTES, budget.runtimeReserveBytes)
        assertEquals(0L, budget.composedOutputBytes)
    }

    @Test
    fun highHeapStillHonorsTheAbsoluteOutputCeiling() {
        val budget = calculateEnhancementMemoryBudget(
            maxMemoryBytes = 2L * 1024 * 1024 * 1024,
            scale = 2,
        )

        assertEquals(96L * 1024 * 1024, budget.composedOutputBytes)
    }
}
