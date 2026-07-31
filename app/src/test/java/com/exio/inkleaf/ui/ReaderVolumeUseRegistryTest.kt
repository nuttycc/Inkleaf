package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderVolumeUseRegistryTest {
    @Test
    fun `close waits for the last task lease`() {
        val volume = Any()
        val closed = mutableListOf<Any>()
        val registry = ReaderVolumeUseRegistry<Any>(closed::add)

        registry.acquire(volume)
        registry.acquire(volume)
        registry.closeWhenUnused(volume)
        registry.release(volume)

        assertEquals(emptyList<Any>(), closed)

        registry.release(volume)

        assertEquals(listOf(volume), closed)

        registry.closeWhenUnused(volume)
        assertEquals(listOf(volume), closed)
    }

    @Test
    fun `unused volume closes immediately`() {
        val volume = Any()
        val closed = mutableListOf<Any>()
        val registry = ReaderVolumeUseRegistry<Any>(closed::add)

        registry.closeWhenUnused(volume)

        assertEquals(listOf(volume), closed)
    }

    @Test
    fun `acquire rejects pending and closed volumes`() {
        val volume = Any()
        val closed = mutableListOf<Any>()
        val registry = ReaderVolumeUseRegistry<Any>(closed::add)

        assertTrue(registry.acquire(volume))
        registry.closeWhenUnused(volume)
        assertFalse(registry.acquire(volume))

        registry.release(volume)
        assertEquals(listOf(volume), closed)
        assertFalse(registry.acquire(volume))
    }

    @Test
    fun `closed tracking uses object identity`() {
        val first = EqualVolume(1)
        val second = EqualVolume(1)
        val closed = mutableListOf<EqualVolume>()
        val registry = ReaderVolumeUseRegistry<EqualVolume>(closed::add)

        registry.closeWhenUnused(first)

        assertTrue(registry.acquire(second))
        registry.release(second)
        assertEquals(listOf(first), closed)
    }

    private data class EqualVolume(val id: Int)
}
