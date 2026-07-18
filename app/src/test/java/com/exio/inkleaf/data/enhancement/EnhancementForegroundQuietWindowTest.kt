package com.exio.inkleaf.data.enhancement

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EnhancementForegroundQuietWindowTest {
    @Test
    fun `same model bulk starts without a quiet delay`() = runBlocking {
        val quietWindow = EnhancementForegroundQuietWindow(
            now = { 0L },
            pause = { error("Same-model work must not wait") },
        )
        quietWindow.record("shared-model")

        quietWindow.awaitBulkTurn("shared-model")
    }

    @Test
    fun `new foreground activity extends the different model quiet window`() = runBlocking {
        var now = 0L
        var pauses = 0
        lateinit var quietWindow: EnhancementForegroundQuietWindow
        quietWindow = EnhancementForegroundQuietWindow(
            now = { now },
            pause = { duration ->
                now += duration
                pauses += 1
                if (pauses == 1) quietWindow.record("reader-model")
            },
        )
        quietWindow.record("reader-model")

        quietWindow.awaitBulkTurn("bulk-model")

        assertEquals(2_100L, now)
    }

    @Test
    fun `bulk quiet wait does not occupy the page job dispatcher`() = runBlocking {
        var now = 0L
        val bulkWaiting = CompletableDeferred<Unit>()
        val releaseBulk = CompletableDeferred<Unit>()
        val quietWindow = EnhancementForegroundQuietWindow(
            now = { now },
            pause = { duration ->
                bulkWaiting.complete(Unit)
                releaseBulk.await()
                now += duration
            },
        )
        val coordinator = EnhancementPageJobCoordinator<String>(this)
        val order = mutableListOf<String>()
        quietWindow.record("reader-model")

        val bulk = async(start = CoroutineStart.UNDISPATCHED) {
            quietWindow.awaitBulkTurn("bulk-model")
            coordinator.request(
                key = pageKey("bulk"),
                priority = EnhancementRequestPriority.BULK_CACHE,
                persistenceRequirement = EnhancementPersistenceRequirement.PINNED,
            ) {
                order += "bulk"
                "bulk"
            }
        }
        bulkWaiting.await()

        val current = coordinator.request(
            key = pageKey("current"),
            priority = EnhancementRequestPriority.CURRENT_PAGE,
            persistenceRequirement = EnhancementPersistenceRequirement.TRANSIENT,
        ) {
            order += "current"
            "current"
        }
        assertEquals("current", current)

        releaseBulk.complete(Unit)
        assertEquals("bulk", bulk.await())
        assertEquals(listOf("current", "bulk"), order)
    }

    private fun pageKey(value: String) = EnhancementPageKey(
        comicId = 1L,
        modelId = "model",
        modelRevision = "revision",
        sourceRevision = "source",
        value = value,
    )
}
