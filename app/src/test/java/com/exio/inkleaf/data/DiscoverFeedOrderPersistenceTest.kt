package com.exio.inkleaf.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverFeedOrderPersistenceTest {
    @Test
    fun `order round-trips through json encoding`() {
        val order = mapOf("plugin-a" to listOf("k1", "k2", "k3"), "plugin-b" to listOf("k9"))

        val decoded = decodeFeedOrder(encodeFeedOrder(order))

        assertEquals(order, decoded)
    }

    @Test
    fun `corrupted json falls back to empty order`() {
        assertEquals(emptyMap<String, List<String>>(), decodeFeedOrder("not-json"))
        assertEquals(emptyMap<String, List<String>>(), decodeFeedOrder("{\"a\": 1}"))
    }
}
