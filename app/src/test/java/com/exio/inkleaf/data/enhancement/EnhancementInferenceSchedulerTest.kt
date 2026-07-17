package com.exio.inkleaf.data.enhancement

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class EnhancementInferenceSchedulerTest {
    @Test
    fun cancellationDuringActivePermitDoesNotBlockNextRequest() = runBlocking {
        val scheduler = EnhancementInferenceScheduler()
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val cancelled = launch {
            scheduler.withPermit(EnhancementRequestPriority.CURRENT_PAGE) {
                entered.complete(Unit)
                hold.await()
            }
        }
        entered.await()

        cancelled.cancelAndJoin()

        val result = withTimeout(1_000) {
            scheduler.withPermit(EnhancementRequestPriority.CURRENT_PAGE) { "next" }
        }
        assertEquals("next", result)
    }

    @Test
    fun cancellationWhileWaitingDoesNotConsumePermit() = runBlocking {
        val scheduler = EnhancementInferenceScheduler()
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val first = launch {
            scheduler.withPermit(EnhancementRequestPriority.CURRENT_PAGE) {
                entered.complete(Unit)
                hold.await()
            }
        }
        entered.await()
        val waiting = launch {
            scheduler.withPermit(EnhancementRequestPriority.BULK_CACHE) {}
        }

        waiting.cancelAndJoin()
        hold.complete(Unit)
        first.join()

        withTimeout(1_000) {
            scheduler.withPermit(EnhancementRequestPriority.CURRENT_PAGE) { }
        }
    }

    @Test
    fun waitingRequestsRunInPriorityOrder() = runBlocking {
        val scheduler = EnhancementInferenceScheduler()
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val order = mutableListOf<EnhancementRequestPriority>()
        val first = launch {
            scheduler.withPermit(EnhancementRequestPriority.CURRENT_PAGE) {
                entered.complete(Unit)
                hold.await()
            }
        }
        entered.await()

        val bulk = launch {
            scheduler.withPermit(EnhancementRequestPriority.BULK_CACHE) {
                order += EnhancementRequestPriority.BULK_CACHE
            }
        }
        yield()
        val prefetch = launch {
            scheduler.withPermit(EnhancementRequestPriority.PREFETCH) {
                order += EnhancementRequestPriority.PREFETCH
            }
        }
        yield()
        val current = launch {
            scheduler.withPermit(EnhancementRequestPriority.CURRENT_PAGE) {
                order += EnhancementRequestPriority.CURRENT_PAGE
            }
        }
        yield()

        hold.complete(Unit)
        joinAll(first, bulk, prefetch, current)

        assertEquals(
            listOf(
                EnhancementRequestPriority.CURRENT_PAGE,
                EnhancementRequestPriority.PREFETCH,
                EnhancementRequestPriority.BULK_CACHE,
            ),
            order,
        )
    }
}
