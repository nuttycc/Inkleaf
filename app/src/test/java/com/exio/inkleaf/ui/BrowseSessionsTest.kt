package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.ComicSummary
import com.exio.inkleaf.plugin.PluginBrowseCacheKey
import com.exio.inkleaf.plugin.PluginBrowseCacheSnapshot
import com.exio.inkleaf.plugin.PluginBrowseRequest
import com.exio.inkleaf.plugin.PluginErrorCode
import com.exio.inkleaf.plugin.PluginFilterDescriptor
import com.exio.inkleaf.plugin.PluginFilterOption
import com.exio.inkleaf.plugin.PluginRpcError
import com.exio.inkleaf.plugin.PluginRpcException
import com.exio.inkleaf.plugin.PluginSearchPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseSessionsTest {

    private class FakeSource : BrowseSource {
        var generation = 1L
        var fresh = true
        var cached: PluginBrowseCacheSnapshot? = null
        var refreshed: PluginBrowseCacheSnapshot? = null
        var nextPage: PluginSearchPage? = null
        var failFirstPage: Throwable? = null
        var failNextPage: Throwable? = null
        var firstPageGate: CompletableDeferred<Unit>? = null
        var nextPageGate: CompletableDeferred<Unit>? = null
        val refreshCalls = mutableListOf<RefreshCall>()
        val pageCalls = mutableListOf<PluginBrowseRequest>()

        override fun cacheGeneration(pluginId: String): Long = generation

        override fun isFresh(fetchedAtMs: Long): Boolean = fresh

        override suspend fun readFirstPage(key: PluginBrowseCacheKey) = cached

        override suspend fun refreshFirstPage(
            key: PluginBrowseCacheKey,
            request: PluginBrowseRequest,
            expectedRevision: String?,
            force: Boolean,
        ): PluginBrowseCacheSnapshot {
            refreshCalls += RefreshCall(request, expectedRevision, force)
            firstPageGate?.await()
            failFirstPage?.let { throw it }
            return checkNotNull(refreshed) { "no refreshed snapshot configured" }
        }

        override suspend fun loadPage(
            pluginId: String,
            request: PluginBrowseRequest,
        ): PluginSearchPage {
            pageCalls += request
            nextPageGate?.await()
            failNextPage?.let { throw it }
            return checkNotNull(nextPage) { "no next page configured" }
        }
    }

    private data class RefreshCall(
        val request: PluginBrowseRequest,
        val expectedRevision: String?,
        val force: Boolean,
    )

    private fun key(filters: Map<String, String> = emptyMap()) =
        PluginBrowseCacheKey(
            pluginId = "source",
            pluginVersion = "1.0",
            feedId = "feed",
            filters = filters,
        )

    private fun target(
        filters: Map<String, String> = emptyMap(),
        loadNow: Boolean = true,
    ) = BrowseTarget(key(filters), "feed", filters, loadNow)

    private fun comic(id: String) = ComicSummary(sourceId = id, title = id)

    private fun page(
        vararg ids: String,
        nextCursor: String? = null,
        filters: List<PluginFilterDescriptor> = emptyList(),
    ) = PluginSearchPage(items = ids.map(::comic), nextCursor = nextCursor, filters = filters)

    private val folderFilter =
        PluginFilterDescriptor(
            "folder",
            "收藏夹",
            type = "select",
            options = listOf(PluginFilterOption("0", "全部"), PluginFilterOption("19", "韓漫")),
        )

    private fun snapshot(
        page: PluginSearchPage,
        fetchedAtMs: Long = 1_000L,
        revision: String = "r1",
        generation: Long = 1L,
    ) = PluginBrowseCacheSnapshot(
        page = page,
        fetchedAtMs = fetchedAtMs,
        revision = revision,
        cacheGeneration = generation,
    )

    private fun idsOf(items: List<ComicSummary>) = items.map { it.sourceId }

    @Test
    fun `select publishes a fresh cached first page without a refresh`() = runBlocking {
        val source = FakeSource().apply { cached = snapshot(page("a", "b")) }
        val sessions = BrowseSessions(this, source)

        val job = sessions.select(target())
        job?.join()

        assertEquals(listOf("a", "b"), idsOf(sessions.state.value.items))
        assertTrue(sessions.state.value.ready)
        assertFalse(sessions.state.value.isBrowsing)
        assertTrue(source.refreshCalls.isEmpty())
    }

    @Test
    fun `select restores a session when returning to a target`() = runBlocking {
        val source = FakeSource().apply { cached = snapshot(page("a", "b")) }
        val sessions = BrowseSessions(this, source)
        sessions.select(target()).also { it?.join() }

        val returned = sessions.select(target())

        assertNull(returned)
        assertEquals(listOf("a", "b"), idsOf(sessions.state.value.items))
        assertTrue(source.refreshCalls.isEmpty())
    }

    @Test
    fun `stale cached content is published first and then refreshed`() = runBlocking {
        val source =
            FakeSource().apply {
                fresh = false
                cached = snapshot(page("a"), revision = "r1")
                refreshed = snapshot(page("a", "b"), revision = "r2")
            }
        val sessions = BrowseSessions(this, source)

        sessions.select(target())?.join()

        assertEquals(listOf("a", "b"), idsOf(sessions.state.value.items))
        assertEquals("r1", source.refreshCalls.single().expectedRevision)
        assertEquals(2L, sessions.state.value.firstPageCommitRevision)
    }

    @Test
    fun `a missing cache loads the first page`() = runBlocking {
        val source = FakeSource().apply { refreshed = snapshot(page("a")) }
        val sessions = BrowseSessions(this, source)

        sessions.select(target())?.join()

        assertNull(source.refreshCalls.single().expectedRevision)
        assertEquals(listOf("a"), idsOf(sessions.state.value.items))
    }

    @Test
    fun `cache from an older plugin generation is ignored`() = runBlocking {
        val source =
            FakeSource().apply {
                generation = 2L
                cached = snapshot(page("stale"), generation = 1L)
                refreshed = snapshot(page("fresh"), generation = 2L)
            }
        val sessions = BrowseSessions(this, source)

        sessions.select(target())?.join()

        assertNull(source.refreshCalls.single().expectedRevision)
        assertEquals(listOf("fresh"), idsOf(sessions.state.value.items))
    }

    @Test
    fun `selecting with no key clears the grid`() = runBlocking {
        val source =
            FakeSource().apply { cached = snapshot(page("a", nextCursor = "c1")) }
        val sessions = BrowseSessions(this, source)
        sessions.select(target()).also { it?.join() }

        val job = sessions.select(BrowseTarget(null, null, emptyMap(), loadNow = true))

        assertNull(job)
        assertTrue(sessions.state.value.items.isEmpty())
        assertNull(sessions.state.value.nextCursor)
        assertTrue(sessions.state.value.ready)
    }

    @Test
    fun `search mode selects without loading and ensureFresh loads later`() = runBlocking {
        val source = FakeSource().apply { cached = snapshot(page("a")) }
        val sessions = BrowseSessions(this, source)

        val idle = sessions.select(target(loadNow = false))

        assertNull(idle)
        assertFalse(sessions.state.value.ready)
        assertTrue(sessions.state.value.items.isEmpty())

        sessions.ensureFresh()?.join()
        assertEquals(listOf("a"), idsOf(sessions.state.value.items))
    }

    @Test
    fun `refresh without a target does nothing`() = runBlocking {
        val sessions = BrowseSessions(this, FakeSource())

        assertNull(sessions.refresh(force = true))
    }

    @Test
    fun `refresh cancels a superseded first page request`() = runBlocking {
        val source =
            FakeSource().apply {
                refreshed = snapshot(page("a"))
                firstPageGate = CompletableDeferred()
            }
        val sessions = BrowseSessions(this, source)
        val first = requireNotNull(sessions.select(target()))
        yield()
        assertEquals(1, source.refreshCalls.size)

        val replacement = requireNotNull(sessions.refresh(force = true))

        assertTrue(first.isCancelled)
        source.firstPageGate?.complete(Unit)
        replacement.join()
        assertEquals(listOf("a"), idsOf(sessions.state.value.items))
        assertFalse(sessions.state.value.isBrowsing)
    }

    @Test
    fun `changing targets cancels a running next page request`() = runBlocking {
        val source =
            FakeSource().apply {
                cached = snapshot(page("a", nextCursor = "c1"))
                nextPage = page("b")
                nextPageGate = CompletableDeferred()
            }
        val sessions = BrowseSessions(this, source)
        sessions.select(target())?.join()
        val nextPage = requireNotNull(sessions.loadMore())
        yield()
        assertEquals(1, source.pageCalls.size)

        sessions.select(BrowseTarget(null, null, emptyMap(), loadNow = false))

        assertTrue(nextPage.isCancelled)
        nextPage.join()
        assertTrue(sessions.state.value.items.isEmpty())
        assertFalse(sessions.state.value.isBrowsing)
    }

    @Test
    fun `loadMore appends without duplicates and advances the cursor`() = runBlocking {
        val source =
            FakeSource().apply {
                cached = snapshot(page("a", "b", nextCursor = "c1"))
                nextPage = page("b", "c", nextCursor = "c2")
            }
        val sessions = BrowseSessions(this, source)
        sessions.select(target()).also { it?.join() }

        sessions.loadMore()?.join()

        assertEquals(listOf("a", "b", "c"), idsOf(sessions.state.value.items))
        assertEquals("c2", sessions.state.value.nextCursor)
        assertEquals("c1", source.pageCalls.single().cursor)
    }

    @Test
    fun `a failed next page retries the same page`() = runBlocking {
        val source =
            FakeSource().apply {
                cached = snapshot(page("a", nextCursor = "c1"))
                failNextPage = IllegalStateException("boom")
            }
        val sessions = BrowseSessions(this, source)
        sessions.select(target()).also { it?.join() }

        sessions.loadMore()?.join()
        assertEquals("boom", sessions.state.value.error)

        source.failNextPage = null
        source.nextPage = page("b", nextCursor = "c2")
        sessions.retry()?.join()

        assertEquals(listOf("a", "b"), idsOf(sessions.state.value.items))
        assertEquals(2, source.pageCalls.size)
        assertNull(sessions.state.value.error)
    }

    @Test
    fun `a failed first page is retried with a forced refresh`() = runBlocking {
        val source = FakeSource().apply { failFirstPage = IllegalStateException("boom") }
        val sessions = BrowseSessions(this, source)

        sessions.select(target())?.join()
        assertEquals("boom", sessions.state.value.error)
        assertTrue(sessions.state.value.ready)

        source.failFirstPage = null
        source.refreshed = snapshot(page("a"))
        sessions.retry()?.join()

        assertEquals(listOf("a"), idsOf(sessions.state.value.items))
        assertTrue(source.refreshCalls.last().force)
        assertEquals(2, source.refreshCalls.size)
    }

    @Test
    fun `selectedFilters remembers a valid choice and falls back to the first option`() {
        val filters =
            listOf(
                PluginFilterDescriptor(
                    id = "sort",
                    title = "Sort",
                    options = listOf(PluginFilterOption("new", "New"), PluginFilterOption("hot", "Hot")),
                )
            )
        val sessions = BrowseSessions(runBlockingScope(), FakeSource())

        assertEquals(mapOf("sort" to "new"), sessions.selectedFilters("feed", filters))

        sessions.rememberFilters("feed", mapOf("sort" to "hot"))
        assertEquals(mapOf("sort" to "hot"), sessions.selectedFilters("feed", filters))

        sessions.rememberFilters("feed", mapOf("sort" to "gone"))
        assertEquals(mapOf("sort" to "new"), sessions.selectedFilters("feed", filters))
    }

    @Test
    fun `first page publishes dynamic filters and a new target clears them`() = runBlocking {
        val source =
            FakeSource().apply { cached = snapshot(page("a", filters = listOf(folderFilter))) }
        val sessions = BrowseSessions(this, source)
        sessions.select(target()).also { it?.join() }

        assertEquals(listOf("folder"), sessions.state.value.dynamicFilters.map { it.id })

        sessions.select(BrowseTarget(null, null, emptyMap(), loadNow = true))

        assertTrue(sessions.state.value.dynamicFilters.isEmpty())
    }

    @Test
    fun `next page fills in dynamic filters and keeps them when a page omits them`() = runBlocking {
        val source =
            FakeSource().apply {
                cached = snapshot(page("a", nextCursor = "c1"))
                nextPage = page("b", nextCursor = "c2", filters = listOf(folderFilter))
            }
        val sessions = BrowseSessions(this, source)
        sessions.select(target()).also { it?.join() }

        sessions.loadMore()?.join()
        assertEquals(listOf("folder"), sessions.state.value.dynamicFilters.map { it.id })

        source.nextPage = page("c", nextCursor = "c3")
        sessions.loadMore()?.join()
        assertEquals(listOf("folder"), sessions.state.value.dynamicFilters.map { it.id })
        assertEquals(listOf("a", "b", "c"), idsOf(sessions.state.value.items))
    }

    @Test
    fun `browse failures expose the plugin error code`() = runBlocking {
        val source =
            FakeSource().apply {
                failFirstPage =
                    PluginRpcException(
                        PluginRpcError(PluginErrorCode.AUTH_REQUIRED, "sign in first")
                    )
            }
        val sessions = BrowseSessions(this, source)

        sessions.select(target())?.join()

        assertEquals(PluginErrorCode.AUTH_REQUIRED, sessions.state.value.errorCode)
    }

    @Test
    fun `remembered dynamic filter selections survive declared filter resolution`() {
        val declared =
            listOf(
                PluginFilterDescriptor(
                    "sort",
                    "Sort",
                    options =
                        listOf(PluginFilterOption("new", "New"), PluginFilterOption("hot", "Hot")),
                )
            )
        val sessions = BrowseSessions(runBlockingScope(), FakeSource())

        sessions.rememberFilters("feed", mapOf("sort" to "hot", "folder" to "19"))

        assertEquals(
            mapOf("sort" to "hot", "folder" to "19"),
            sessions.selectedFilters("feed", declared),
        )
    }

    @Test
    fun `scroll anchors are kept per context`() {
        val sessions = BrowseSessions(runBlockingScope(), FakeSource())
        val context = DiscoverScrollContextKey.Search("query", emptySet())
        val anchor =
            DiscoverScrollAnchor(
                itemKey = DiscoverComicKey("source", "a"),
                scrollOffset = 12,
                orderedKeys = listOf(DiscoverComicKey("source", "a")),
            )

        sessions.updateScrollAnchor(context, anchor)

        assertEquals(anchor, sessions.scrollAnchor(context))
        assertNull(sessions.scrollAnchor(DiscoverScrollContextKey.Search("other", emptySet())))
    }

    private fun runBlockingScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
}
