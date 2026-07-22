package com.exio.inkleaf.data.enhancement

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementResourceLeaseTest {
    @Test
    fun closeWaitsForAllProducerLeases() = runBlocking {
        val registry = EnhancementResourceLeaseRegistry<Any>()
        val resource = Any()
        val lease = registry.acquire(resource)
        val closed = AtomicInteger()

        val closeJob = async(start = CoroutineStart.UNDISPATCHED) {
            registry.closeAfterIdle(resource) { closed.incrementAndGet() }
        }
        assertEquals(0, closed.get())

        lease.close()
        closeJob.await()
        assertEquals(1, closed.get())
    }

    @Test
    fun closingResourceRejectsNewLeases() = runBlocking {
        val registry = EnhancementResourceLeaseRegistry<Any>()
        val resource = Any()
        val lease = registry.acquire(resource)
        val closing = CompletableDeferred<Unit>()

        val closeJob = async(start = CoroutineStart.UNDISPATCHED) {
            registry.closeAfterIdle(resource) { closing.complete(Unit) }
        }
        assertTrue(registry.isClosing(resource))

        var rejected = false
        try {
            registry.acquire(resource)
        } catch (_: kotlinx.coroutines.CancellationException) {
            rejected = true
        }
        assertTrue(rejected)

        assertFalse(closing.isCompleted)
        lease.close()
        closeJob.await()
        assertTrue(closing.isCompleted)
    }

    @Test
    fun closedResourceCannotBeLeasedAgain() = runBlocking {
        val registry = EnhancementResourceLeaseRegistry<Any>()
        val resource = Any()

        registry.closeAfterIdle(resource) {
            // No active lease: close completes immediately and leaves a weak closed marker.
        }

        var rejected = false
        try {
            registry.acquire(resource)
        } catch (_: kotlinx.coroutines.CancellationException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun equalResourcesKeepIndependentIdentityLifetimes() = runBlocking {
        data class Resource(val id: Int)

        val registry = EnhancementResourceLeaseRegistry<Resource>()
        val first = Resource(1)
        val second = Resource(1)

        registry.closeAfterIdle(first) {}
        assertTrue(registry.isClosing(first))
        assertFalse(registry.isClosing(second))
        val secondLease = registry.acquire(second)
        secondLease.close()
    }

    @Test
    fun concurrentCloseRequestsRunTheCloseActionOnce() = runBlocking {
        val registry = EnhancementResourceLeaseRegistry<Any>()
        val resource = Any()
        val lease = registry.acquire(resource)
        val closes = AtomicInteger()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            registry.closeAfterIdle(resource) { closes.incrementAndGet() }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            registry.closeAfterIdle(resource) { closes.incrementAndGet() }
        }

        lease.close()
        first.await()
        second.await()
        assertEquals(1, closes.get())
    }

    @Test
    fun coordinatorProducerKeepsTheResourceOpenAfterItsRequesterIsCancelled() = runBlocking {
        val registry = EnhancementResourceLeaseRegistry<Any>()
        val coordinator = EnhancementPageJobCoordinator<Unit>(this)
        val resource = Any()
        val lease = registry.acquire(resource)
        val producerEntered = CompletableDeferred<Unit>()
        val releaseProducer = CompletableDeferred<Unit>()
        val closes = AtomicInteger()

        val requester = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = pageKey(),
                priority = EnhancementRequestPriority.CURRENT_PAGE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
                discardProducer = { lease.close() },
            ) {
                try {
                    producerEntered.complete(Unit)
                    releaseProducer.await()
                } finally {
                    lease.close()
                }
            }
        }
        producerEntered.await()
        requester.cancelAndJoin()

        val closeJob = async(start = CoroutineStart.UNDISPATCHED) {
            registry.closeAfterIdle(resource) { closes.incrementAndGet() }
        }
        assertEquals(0, closes.get())

        releaseProducer.complete(Unit)
        closeJob.await()
        assertEquals(1, closes.get())
    }

    private fun pageKey() = EnhancementPageKey(
        comicId = 1L,
        modelId = "model",
        modelRevision = "model-revision",
        sourceRevision = "source-revision",
        pipelineRevision = ENHANCEMENT_PIPELINE_REVISION,
        value = "page",
    )
}
