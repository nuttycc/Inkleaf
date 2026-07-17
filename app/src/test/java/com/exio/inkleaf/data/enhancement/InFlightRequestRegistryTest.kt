package com.exio.inkleaf.data.enhancement

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class InFlightRequestRegistryTest {
    @Test
    fun `same key shares one producer`() = runBlocking {
        val registry = InFlightRequestRegistry<String, String>()
        val gate = CompletableDeferred<Unit>()
        var producerRuns = 0

        val first = async {
            registry.run("page") {
                producerRuns++
                gate.await()
                "result"
            }
        }
        yield()
        val second = async { registry.run("page") { error("duplicate producer") } }
        gate.complete(Unit)

        assertEquals("result", first.await())
        assertEquals("result", second.await())
        assertEquals(1, producerRuns)
    }

    @Test
    fun `failed request is removed so the key can retry`() = runBlocking {
        val registry = InFlightRequestRegistry<String, String>()

        try {
            registry.run("page") { error("failed") }
            fail("Expected producer failure")
        } catch (_: IllegalStateException) {
            // Expected: the failed request must not remain in the registry.
        }

        assertEquals("retry", registry.run("page") { "retry" })
    }

    @Test
    fun `cancelled request is removed so the key can retry`() = runBlocking {
        val registry = InFlightRequestRegistry<String, String>()
        val gate = CompletableDeferred<Unit>()
        val request = async {
            registry.run("page") {
                gate.await()
                "never"
            }
        }
        yield()
        request.cancel()
        try {
            request.await()
            fail("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected: cancellation must still remove the in-flight entry.
        }

        assertEquals("retry", registry.run("page") { "retry" })
        gate.cancel()
    }
}
