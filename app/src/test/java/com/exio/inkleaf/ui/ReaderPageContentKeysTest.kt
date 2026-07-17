package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReaderPageContentKeysTest {
    private val volume = Any()

    @Test
    fun `unrelated navigation does not restart an inactive page producer`() {
        val before = keys(isCurrentPage = false)
        val after = keys(isCurrentPage = false)

        assertEquals(before.stateReset, after.stateReset)
        assertEquals(before.producerRestart, after.producerRestart)
    }

    @Test
    fun `entering or leaving the current page restarts without resetting state`() {
        val inactive = keys(isCurrentPage = false)
        val active = keys(isCurrentPage = true)

        assertEquals(inactive.stateReset, active.stateReset)
        assertNotEquals(inactive.producerRestart, active.producerRestart)
    }

    @Test
    fun `model selection changes restart without resetting state`() {
        val before = keys(enhancementSelectionId = "model-a")
        val after = keys(enhancementSelectionId = "model-b")

        assertEquals(before.stateReset, after.stateReset)
        assertNotEquals(before.producerRestart, after.producerRestart)
    }

    @Test
    fun `model installation changes restart without resetting state`() {
        val before = keys(enhancementModelInstalled = true)
        val after = keys(enhancementModelInstalled = false)

        assertEquals(before.stateReset, after.stateReset)
        assertNotEquals(before.producerRestart, after.producerRestart)
    }

    @Test
    fun `page identity changes use a new state holder`() {
        val before = keys()

        assertNotEquals(before.stateReset, keys(page = 8).stateReset)
        assertNotEquals(before.stateReset, keys(volumeToken = Any()).stateReset)
        assertNotEquals(before.stateReset, keys(cacheKeyPrefix = "comic-2").stateReset)
    }

    private fun keys(
        volumeToken: Any = volume,
        page: Int = 7,
        cacheKeyPrefix: String = "comic-1",
        isCurrentPage: Boolean = true,
        enhancementSelectionId: String = "model-a",
        enhancementModelInstalled: Boolean = true,
    ): ReaderPageContentKeys = readerPageContentKeys(
        volumeToken = volumeToken,
        page = page,
        cacheKeyPrefix = cacheKeyPrefix,
        isCurrentPage = isCurrentPage,
        enhancementSelectionId = enhancementSelectionId,
        enhancementModelInstalled = enhancementModelInstalled,
    )
}
