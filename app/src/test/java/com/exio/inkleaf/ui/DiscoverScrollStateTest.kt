package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverScrollStateTest {
    private val first = DiscoverComicKey("plugin", "first")
    private val second = DiscoverComicKey("plugin", "second")
    private val third = DiscoverComicKey("plugin", "third")

    @Test
    fun `stale search generation is rejected after availability invalidation`() {
        val gate = DiscoverSearchRequestGate()
        val staleGeneration = gate.next()

        gate.invalidate()

        assertFalse(gate.accepts(staleGeneration))
        val currentGeneration = gate.next()
        assertTrue(gate.accepts(currentGeneration))
    }

    @Test
    fun `browse context equality ignores filter map order`() {
        val firstContext =
            DiscoverScrollContextKey.Browse(
                pluginId = "plugin",
                pluginVersion = "1.0.0",
                feedId = "recommended",
                filters = linkedMapOf("region" to "all", "period" to "week"),
            )
        val secondContext =
            DiscoverScrollContextKey.Browse(
                pluginId = "plugin",
                pluginVersion = "1.0.0",
                feedId = "recommended",
                filters = mapOf("period" to "week", "region" to "all"),
            )

        assertEquals(firstContext, secondContext)
    }

    @Test
    fun `search context equality ignores selected plugin order and layout`() {
        val firstContext =
            DiscoverScrollContextKey.Search(
                query = "comic",
                selectedPlugins =
                    linkedSetOf(
                        DiscoverSearchPluginKey("plugin-a", "1.0.0"),
                        DiscoverSearchPluginKey("plugin-b", "2.0.0"),
                    ),
            )
        val secondContext =
            DiscoverScrollContextKey.Search(
                query = "comic",
                selectedPlugins =
                    linkedSetOf(
                        DiscoverSearchPluginKey("plugin-b", "2.0.0"),
                        DiscoverSearchPluginKey("plugin-a", "1.0.0"),
                    ),
            )

        assertEquals(firstContext, secondContext)
    }

    @Test
    fun `browse structural rows map comics to lazy grid indexes`() {
        val result =
            mapDiscoverGridComicItems(
                leadingStructuralItemCount = 2,
                sections =
                    listOf(
                        DiscoverGridComicSection(
                            structuralItemCount = 0,
                            comicKeys = listOf(first, second),
                        )
                    ),
                trailingStructuralItemCount = 1,
            )

        assertEquals(
            listOf(
                DiscoverGridComicItem(first, 2),
                DiscoverGridComicItem(second, 3),
            ),
            result,
        )
    }

    @Test
    fun `search structural rows map each section independently`() {
        val result =
            mapDiscoverGridComicItems(
                leadingStructuralItemCount = 1,
                sections =
                    listOf(
                        DiscoverGridComicSection(1, listOf(first, second)),
                        DiscoverGridComicSection(2, listOf(third)),
                    ),
            )

        assertEquals(
            listOf(
                DiscoverGridComicItem(first, 2),
                DiscoverGridComicItem(second, 3),
                DiscoverGridComicItem(third, 6),
            ),
            result,
        )
    }

    @Test
    fun `exact stable key wins and preserves pixel offset`() {
        val target =
            resolveDiscoverScrollTarget(
                anchor = DiscoverScrollAnchor(first, -36, listOf(first, second, third)),
                currentItems =
                    listOf(
                        DiscoverGridComicItem(first, 8),
                        DiscoverGridComicItem(second, 9),
                    ),
            )

        assertEquals(DiscoverScrollTarget(8, -36), target)
    }

    @Test
    fun `nearest surviving successor wins an equal distance tie`() {
        val target =
            resolveDiscoverScrollTarget(
                anchor = DiscoverScrollAnchor(second, 12, listOf(first, second, third)),
                currentItems =
                    listOf(
                        DiscoverGridComicItem(first, 4),
                        DiscoverGridComicItem(third, 10),
                    ),
            )

        assertEquals(DiscoverScrollTarget(10, 12), target)
    }

    @Test
    fun `missing anchor uses nearest surviving predecessor`() {
        val target =
            resolveDiscoverScrollTarget(
                anchor = DiscoverScrollAnchor(third, 0, listOf(first, second, third)),
                currentItems = listOf(DiscoverGridComicItem(first, 2)),
            )

        assertEquals(DiscoverScrollTarget(2, 0), target)
    }

    @Test
    fun `no old item survives so restoration falls back to top`() {
        val target =
            resolveDiscoverScrollTarget(
                anchor = DiscoverScrollAnchor(second, 0, listOf(first, second)),
                currentItems = listOf(DiscoverGridComicItem(DiscoverComicKey("plugin", "new"), 4)),
            )

        assertNull(target)
    }

    @Test
    fun `scroll to top is hidden at the very top`() {
        assertFalse(shouldShowScrollToTop(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0))
    }

    @Test
    fun `scroll to top stays hidden before the visible threshold`() {
        assertFalse(shouldShowScrollToTop(firstVisibleItemIndex = 1, firstVisibleItemScrollOffset = 0))
    }

    @Test
    fun `scroll to top is hidden on the threshold item before it scrolls out`() {
        assertFalse(shouldShowScrollToTop(firstVisibleItemIndex = 2, firstVisibleItemScrollOffset = 0))
    }

    @Test
    fun `scroll to top shows once the threshold item starts scrolling out`() {
        assertTrue(shouldShowScrollToTop(firstVisibleItemIndex = 2, firstVisibleItemScrollOffset = 1))
    }

    @Test
    fun `scroll to top shows beyond the threshold`() {
        assertTrue(shouldShowScrollToTop(firstVisibleItemIndex = 3, firstVisibleItemScrollOffset = 0))
    }

    @Test
    fun `scroll to top shows deep in the list`() {
        assertTrue(shouldShowScrollToTop(firstVisibleItemIndex = 100, firstVisibleItemScrollOffset = 240))
    }

    @Test
    fun `scroll anchor store access refreshes LRU order`() {
        val store = DiscoverScrollAnchorStore()
        val contexts =
            (0..32).map { index ->
                DiscoverScrollContextKey.Browse(
                    pluginId = "plugin",
                    pluginVersion = "1.0.0",
                    feedId = "feed-$index",
                    filters = emptyMap(),
                )
            }

        contexts.take(32).forEachIndexed { index, context ->
            store.put(
                context,
                DiscoverScrollAnchor(
                    itemKey = DiscoverComicKey("plugin", "comic-$index"),
                    scrollOffset = 0,
                    orderedKeys = emptyList(),
                ),
            )
        }
        assertNotNull(store.get(contexts.first()))
        store.put(
            contexts.last(),
            DiscoverScrollAnchor(
                itemKey = DiscoverComicKey("plugin", "comic-32"),
                scrollOffset = 0,
                orderedKeys = emptyList(),
            ),
        )

        assertNotNull(store.get(contexts.first()))
        assertNull(store.get(contexts[1]))
    }
}
