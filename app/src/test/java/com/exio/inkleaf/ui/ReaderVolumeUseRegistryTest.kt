package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
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
}
