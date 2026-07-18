package com.exio.inkleaf.data.enhancement

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EnhancementPageJobCoordinatorTest {
    @Test
    fun `same page identity shares one producer across requester priorities`() = runBlocking {
        val coordinator = EnhancementPageJobCoordinator<String>(this)
        val producerEntered = CompletableDeferred<Unit>()
        val releaseProducer = CompletableDeferred<Unit>()
        val producerRuns = AtomicInteger()

        val bulk = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.BULK_CACHE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) {
                producerRuns.incrementAndGet()
                producerEntered.complete(Unit)
                releaseProducer.await()
                "shared"
            }
        }
        producerEntered.await()
        val current = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.CURRENT_PAGE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) {
                error("Duplicate producer must not execute")
            }
        }

        releaseProducer.complete(Unit)

        assertEquals("shared", bulk.await())
        assertEquals("shared", current.await())
        assertEquals(1, producerRuns.get())
    }

    @Test
    fun `queued page promotion changes the next execution order`() = runBlocking {
        val coordinator = EnhancementPageJobCoordinator<String>(this)
        val activeEntered = CompletableDeferred<Unit>()
        val releaseActive = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val active = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.BULK_CACHE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) {
                activeEntered.complete(Unit)
                releaseActive.await()
                "active"
            }
        }
        activeEntered.await()
        val promoted = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_TWO,
                priority = EnhancementRequestPriority.BULK_CACHE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) {
                order += "promoted"
                "promoted"
            }
        }
        val prefetch = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_THREE,
                priority = EnhancementRequestPriority.PREFETCH,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) {
                order += "prefetch"
                "prefetch"
            }
        }
        val promotion = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_TWO,
                priority = EnhancementRequestPriority.CURRENT_PAGE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) {
                error("Promoted request must attach to the queued producer")
            }
        }

        releaseActive.complete(Unit)
        active.await()
        promoted.await()
        prefetch.await()
        promotion.await()

        assertEquals(listOf("promoted", "prefetch"), order)
    }

    @Test
    fun `current page then prefetch then bulk priority is enforced`() = runBlocking {
        val coordinator = EnhancementPageJobCoordinator<String>(this)
        val activeEntered = CompletableDeferred<Unit>()
        val releaseActive = CompletableDeferred<Unit>()
        val order = mutableListOf<EnhancementRequestPriority>()

        val active = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.BULK_CACHE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) {
                activeEntered.complete(Unit)
                releaseActive.await()
                "active"
            }
        }
        activeEntered.await()
        val bulk =
            queuedRequest(coordinator, PAGE_TWO, EnhancementRequestPriority.BULK_CACHE, order)
        val current = queuedRequest(
            coordinator,
            PAGE_THREE,
            EnhancementRequestPriority.CURRENT_PAGE,
            order,
        )
        val prefetch = queuedRequest(
            coordinator,
            PAGE_FOUR,
            EnhancementRequestPriority.PREFETCH,
            order,
        )

        releaseActive.complete(Unit)
        active.await()
        current.await()
        prefetch.await()
        bulk.await()

        assertEquals(
            listOf(
                EnhancementRequestPriority.CURRENT_PAGE,
                EnhancementRequestPriority.PREFETCH,
                EnhancementRequestPriority.BULK_CACHE,
            ),
            order,
        )
    }

    @Test
    fun `pinned requirement can escalate while the producer is running`() = runBlocking {
        val coordinator = EnhancementPageJobCoordinator<EnhancementPersistenceRequirement>(this)
        val producerEntered = CompletableDeferred<Unit>()
        val inspectRequirement = CompletableDeferred<Unit>()

        val transient = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.PREFETCH,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) {
                producerEntered.complete(Unit)
                inspectRequirement.await()
                persistenceRequirement
            }
        }
        producerEntered.await()
        val pinned = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.BULK_CACHE,
                persistenceRequirement = EnhancementPersistenceRequirement.PINNED,
            ) {
                error("Pinned requester must attach to the running producer")
            }
        }

        inspectRequirement.complete(Unit)

        assertEquals(EnhancementPersistenceRequirement.PINNED, transient.await())
        assertEquals(EnhancementPersistenceRequirement.PINNED, pinned.await())
    }

    @Test
    fun `late persistence promotion is finalized before result publication`() = runBlocking {
        val coordinator = EnhancementPageJobCoordinator<String>(this)
        val transientFinalizerEntered = CompletableDeferred<Unit>()
        val releaseTransientFinalizer = CompletableDeferred<Unit>()
        val finalizedRequirements = mutableListOf<EnhancementPersistenceRequirement>()

        val transient = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.PREFETCH,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
                finalizer = { value, requirement ->
                    finalizedRequirements += requirement
                    if (requirement == EnhancementPersistenceRequirement.TRANSIENT) {
                        transientFinalizerEntered.complete(Unit)
                        releaseTransientFinalizer.await()
                    }
                    "$value:$requirement"
                },
            ) { "result" }
        }
        transientFinalizerEntered.await()
        val pinned = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.BULK_CACHE,
                persistenceRequirement = EnhancementPersistenceRequirement.PINNED,
            ) { error("Pinned requester must attach before publication") }
        }

        releaseTransientFinalizer.complete(Unit)

        assertEquals("result:PINNED", transient.await())
        assertEquals("result:PINNED", pinned.await())
        assertEquals(
            listOf(
                EnhancementPersistenceRequirement.TRANSIENT,
                EnhancementPersistenceRequirement.PINNED,
            ),
            finalizedRequirements,
        )
    }

    @Test
    fun `persistence finalization does not hold the producer slot`() = runBlocking {
        val coordinator = EnhancementPageJobCoordinator<String>(this)
        val finalizerEntered = CompletableDeferred<Unit>()
        val releaseFinalizer = CompletableDeferred<Unit>()
        val secondProducerEntered = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.BULK_CACHE,
                persistenceRequirement = EnhancementPersistenceRequirement.PINNED,
                retainWithoutWaiters = true,
                finalizer = { value, _ ->
                    finalizerEntered.complete(Unit)
                    releaseFinalizer.await()
                    value
                },
            ) { "first" }
        }
        finalizerEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_TWO,
                priority = EnhancementRequestPriority.CURRENT_PAGE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) {
                secondProducerEntered.complete(Unit)
                "second"
            }
        }

        withTimeout(1_000) { secondProducerEntered.await() }
        releaseFinalizer.complete(Unit)

        assertEquals("first", first.await())
        assertEquals("second", second.await())
    }

    @Test
    fun `attached request discards its unused producer resource`() = runBlocking {
        val coordinator = EnhancementPageJobCoordinator<String>(this)
        val producerEntered = CompletableDeferred<Unit>()
        val releaseProducer = CompletableDeferred<Unit>()
        val discarded = AtomicInteger()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.PREFETCH,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) {
                producerEntered.complete(Unit)
                releaseProducer.await()
                "shared"
            }
        }
        producerEntered.await()
        val attached = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.CURRENT_PAGE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
                discardProducer = { discarded.incrementAndGet() },
            ) { error("Attached producer must not run") }
        }

        releaseProducer.complete(Unit)

        assertEquals("shared", first.await())
        assertEquals("shared", attached.await())
        assertEquals(1, discarded.get())
    }

    @Test
    fun `cancelling the first caller does not cancel a producer needed by another caller`() =
        runBlocking {
            val coordinator = EnhancementPageJobCoordinator<String>(this)
            val producerEntered = CompletableDeferred<Unit>()
            val releaseProducer = CompletableDeferred<Unit>()

            val first = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(
                    key = PAGE_ONE,
                    priority = EnhancementRequestPriority.PREFETCH,
                    persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
                ) {
                    producerEntered.complete(Unit)
                    releaseProducer.await()
                    "kept-alive"
                }
            }
            producerEntered.await()
            val attached = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(
                    key = PAGE_ONE,
                    priority = EnhancementRequestPriority.CURRENT_PAGE,
                    persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
                ) {
                    error("Attached caller must not become another producer")
                }
            }

            first.cancelAndJoin()
            releaseProducer.complete(Unit)

            assertEquals("kept-alive", withTimeout(1_000) { attached.await() })
        }

    @Test
    fun `last cancelled waiter removes an unstarted UI page and discards its source`() =
        runBlocking {
            val coordinator = EnhancementPageJobCoordinator<String>(this)
            val activeEntered = CompletableDeferred<Unit>()
            val releaseActive = CompletableDeferred<Unit>()
            val discarded = AtomicInteger()
            val queuedProducerRuns = AtomicInteger()

            val active = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(
                    key = PAGE_ONE,
                    priority = EnhancementRequestPriority.BULK_CACHE,
                    persistenceRequirement = EnhancementPersistenceRequirement.PINNED,
                ) {
                    activeEntered.complete(Unit)
                    releaseActive.await()
                    "active"
                }
            }
            activeEntered.await()
            val obsolete = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(
                    key = PAGE_TWO,
                    priority = EnhancementRequestPriority.CURRENT_PAGE,
                    persistenceRequirement = EnhancementPersistenceRequirement.PINNED,
                    discardProducer = { discarded.incrementAndGet() },
                ) {
                    queuedProducerRuns.incrementAndGet()
                    "obsolete"
                }
            }

            obsolete.cancelAndJoin()
            releaseActive.complete(Unit)

            assertEquals("active", active.await())
            assertEquals(1, discarded.get())
            assertEquals(0, queuedProducerRuns.get())
            assertEquals(
                "replacement",
                coordinator.request(
                    key = PAGE_TWO,
                    priority = EnhancementRequestPriority.CURRENT_PAGE,
                    persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
                ) { "replacement" },
            )
        }

    @Test
    fun `completed and failed jobs are removed so the key can run again`() = runBlocking {
        val coordinator = EnhancementPageJobCoordinator<String>(this)

        assertEquals(
            "first",
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.CURRENT_PAGE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) { "first" },
        )
        assertEquals(
            "second",
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.CURRENT_PAGE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) { "second" },
        )

        try {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.CURRENT_PAGE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) { error("failed") }
            fail("Expected producer failure")
        } catch (_: IllegalStateException) {
            // Expected: a failed job must not remain registered.
        }

        assertEquals(
            "retry",
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.CURRENT_PAGE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) { "retry" },
        )
    }

    @Test
    fun `finalizer failure removes the job so the key can retry`() = runBlocking {
        val coordinator = EnhancementPageJobCoordinator<String>(this)

        try {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.CURRENT_PAGE,
                persistenceRequirement = EnhancementPersistenceRequirement.PINNED,
                finalizer = { _, _ -> error("persist failed") },
            ) { "first" }
            fail("Expected finalizer failure")
        } catch (_: IllegalStateException) {
            // Expected: persistence failure must not leave the page identity registered.
        }

        assertEquals(
            "retry",
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.CURRENT_PAGE,
                persistenceRequirement = EnhancementPersistenceRequirement.PINNED,
            ) { "retry" },
        )
    }

    @Test
    fun `cancelled scope rejects requests without registering a producer`() = runBlocking {
        val parentJob = SupervisorJob()
        val coordinator = EnhancementPageJobCoordinator<String>(
            CoroutineScope(parentJob + Dispatchers.Default)
        )
        val producerRuns = AtomicInteger()
        parentJob.cancelAndJoin()

        repeat(2) {
            expectCancellation {
                coordinator.request(
                    key = PAGE_ONE,
                    priority = EnhancementRequestPriority.CURRENT_PAGE,
                    persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
                ) {
                    producerRuns.incrementAndGet()
                    "unexpected"
                }
            }
        }

        assertEquals(0, producerRuns.get())
    }

    @Test
    fun `scope cancellation fails running queued and future requests`() = runBlocking {
        val parentJob = SupervisorJob()
        val coordinator = EnhancementPageJobCoordinator<String>(
            CoroutineScope(parentJob + Dispatchers.Default)
        )
        val producerEntered = CompletableDeferred<Unit>()
        val producerFinished = CompletableDeferred<Unit>()

        val running = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.BULK_CACHE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) {
                try {
                    producerEntered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    "unexpected"
                } finally {
                    producerFinished.complete(Unit)
                }
            }
        }
        producerEntered.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.request(
                key = PAGE_TWO,
                priority = EnhancementRequestPriority.CURRENT_PAGE,
                persistenceRequirement = EnhancementPersistenceRequirement.PINNED,
            ) { "unexpected" }
        }

        parentJob.cancelAndJoin()

        expectCancellation { running.await() }
        expectCancellation { queued.await() }
        withTimeout(1_000) { producerFinished.await() }
        expectCancellation {
            coordinator.request(
                key = PAGE_ONE,
                priority = EnhancementRequestPriority.CURRENT_PAGE,
                persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
            ) { "unexpected" }
        }
    }

    @Test
    fun `callers attached before result publication never start a second producer`() = runBlocking {
        val coordinator = EnhancementPageJobCoordinator<String>(this)
        val producerEntered = CompletableDeferred<Unit>()
        val releaseProducer = CompletableDeferred<Unit>()
        val producerRuns = AtomicInteger()

        val requests = List(64) { index ->
            async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(
                    key = PAGE_ONE,
                    priority = EnhancementRequestPriority.entries[
                        index % EnhancementRequestPriority.entries.size
                    ],
                    persistenceRequirement = if (index % 2 == 0) {
                        EnhancementPersistenceRequirement.TRANSIENT
                    } else {
                        EnhancementPersistenceRequirement.PINNED
                    },
                ) {
                    producerRuns.incrementAndGet()
                    producerEntered.complete(Unit)
                    releaseProducer.await()
                    "published"
                }
            }
        }
        producerEntered.await()
        releaseProducer.complete(Unit)

        requests.forEach { assertEquals("published", it.await()) }
        assertEquals(1, producerRuns.get())
    }

    private fun CoroutineScope.queuedRequest(
        coordinator: EnhancementPageJobCoordinator<String>,
        key: EnhancementPageKey,
        priority: EnhancementRequestPriority,
        order: MutableList<EnhancementRequestPriority>,
    ) = async(start = CoroutineStart.UNDISPATCHED) {
        coordinator.request(
            key = key,
            priority = priority,
            persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
        ) {
            order += priority
            priority.name
        }
    }

    private suspend fun expectCancellation(block: suspend () -> Unit) {
        try {
            withTimeout(1_000) { block() }
            fail("Expected cancellation")
        } catch (_: TimeoutCancellationException) {
            fail("Coordinator request did not finish after scope cancellation")
        } catch (_: CancellationException) {
            // Expected: a closed coordinator must fail instead of retaining unreachable work.
        }
    }

    private companion object {
        val PAGE_ONE = pageKey("one")
        val PAGE_TWO = pageKey("two")
        val PAGE_THREE = pageKey("three")
        val PAGE_FOUR = pageKey("four")

        fun pageKey(page: String) = EnhancementPageKey(
            comicId = 1L,
            modelId = "model",
            modelRevision = "model-revision",
            sourceRevision = "source-revision",
            value = page,
        )
    }
}
