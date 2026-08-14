package com.exio.inkleaf.ui

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
}
