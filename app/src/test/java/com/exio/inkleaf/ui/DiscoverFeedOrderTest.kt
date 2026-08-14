package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.PluginFeedDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DiscoverFeedOrderTest {
    @Test
    fun `no user order keeps discovery order`() {
        val keys = listOf("a:latest", "a:hot", "a:new")

        assertEquals(keys, applyUserOrder(keys, emptyList()))
    }

    @Test
    fun `partial user order puts known keys first and appends new keys in discovery order`() {
        val keys = listOf("a:latest", "a:hot", "a:new", "a:top")

        val ordered = applyUserOrder(keys, listOf("a:new", "a:latest"))

        // "a:new" 和 "a:latest" 按用户顺序置前，未记录的 "a:hot"/"a:top" 保持发现相对顺序在后
        assertEquals(listOf("a:new", "a:latest", "a:hot", "a:top"), ordered)
    }

    @Test
    fun `full user order wins over discovery order`() {
        val keys = listOf("a:latest", "a:hot", "a:new")

        val ordered = applyUserOrder(keys, listOf("a:hot", "a:new", "a:latest"))

        assertEquals(listOf("a:hot", "a:new", "a:latest"), ordered)
    }

    @Test
    fun `user order entries for removed feeds are ignored`() {
        val keys = listOf("a:latest", "a:new")

        val ordered = applyUserOrder(keys, listOf("a:gone", "a:latest", "a:new"))

        assertEquals(listOf("a:latest", "a:new"), ordered)
    }

    @Test
    fun `move to earlier slot puts from key before target`() {
        val order = listOf("a", "b", "c", "d")

        val moved = moveFeedKey(order, "d", "b")

        assertEquals(listOf("a", "d", "b", "c"), moved)
    }

    @Test
    fun `move to later slot puts from key after target`() {
        val order = listOf("a", "b", "c", "d")

        val moved = moveFeedKey(order, "a", "c")

        assertEquals(listOf("b", "c", "a", "d"), moved)
    }

    @Test
    fun `move into adjacent target`() {
        val order = listOf("a", "b", "c")

        assertEquals(listOf("a", "c", "b"), moveFeedKey(order, "b", "c"))
        assertEquals(listOf("b", "a", "c"), moveFeedKey(order, "a", "b"))
    }

    @Test
    fun `move with unknown or identical keys is a no-op returning same instance`() {
        val order = listOf("a", "b", "c")

        assertSame(order, moveFeedKey(order, "a", "a"))
        assertSame(order, moveFeedKey(order, "a", "missing"))
        assertSame(order, moveFeedKey(order, "missing", "a"))
    }

    @Test
    fun `default selection without user order is the first plugin feed`() {
        val feeds = feedsOf("p1" to listOf("latest", "hot"), "p2" to listOf("top"))

        val ordered = applyUserOrderToFeeds(feeds, emptyMap())

        assertEquals(listOf("p1:latest", "p1:hot", "p2:top"), ordered.map { it.key })
    }

    @Test
    fun `default selection follows per-plugin user order while keeping plugin order`() {
        val feeds = feedsOf("p1" to listOf("latest", "hot", "new"), "p2" to listOf("top", "rank"))
        // userOrder 存的是完整 feed key（"pluginId:feedId"），与 moveFeed 持久化的格式一致
        val userOrder = mapOf("p1" to listOf("p1:new", "p1:latest"), "p2" to listOf("p2:rank"))

        val ordered = applyUserOrderToFeeds(feeds, userOrder)

        assertEquals(listOf("p1:new", "p1:latest", "p1:hot", "p2:rank", "p2:top"), ordered.map { it.key })
    }

    @Test
    fun `user order entries for removed or unknown plugins are ignored`() {
        val feeds = feedsOf("p1" to listOf("latest"))
        val userOrder = mapOf("p1" to listOf("p1:latest"), "gone" to listOf("gone:x"))

        val ordered = applyUserOrderToFeeds(feeds, userOrder)

        assertEquals(listOf("p1:latest"), ordered.map { it.key })
    }

    private fun feedsOf(vararg pluginFeeds: Pair<String, List<String>>): List<DiscoverViewModel.Feed> =
        pluginFeeds.flatMap { (pluginId, feedIds) ->
            feedIds.map { feedId ->
                DiscoverViewModel.Feed(
                    pluginId = pluginId,
                    pluginName = pluginId,
                    pluginVersion = "1.0.0",
                    descriptor = PluginFeedDescriptor(id = feedId, title = feedId),
                )
            }
        }
}
