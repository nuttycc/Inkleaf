package com.exio.inkleaf.plugin

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBrowseRepositoryTest {
    @Test
    fun `fresh cache avoids another remote request and survives repository recreation`() =
        runBlocking {
            withCacheDirectory { directory ->
                val calls = AtomicInteger()
                val remote: suspend (String, PluginBrowseRequest) -> PluginSearchPage = { _, _ ->
                    calls.incrementAndGet()
                    page("comic-${calls.get()}")
                }
                val first =
                    PluginBrowseRepository(directory, remote, clockMs = { 100L })
                        .refreshFirstPage(KEY, REQUEST, expectedRevision = null)

                val restoredRepository =
                    PluginBrowseRepository(directory, remote, clockMs = { 200L })
                val restored = requireNotNull(restoredRepository.readFirstPage(KEY))
                val reused =
                    restoredRepository.refreshFirstPage(
                        KEY,
                        REQUEST,
                        expectedRevision = restored.revision,
                    )

                assertEquals(first, restored)
                assertEquals(restored, reused)
                assertEquals(1, calls.get())
            }
        }

    @Test
    fun `plugin version and filters select different cache entries`() = runBlocking {
        withCacheDirectory { directory ->
            val repository = PluginBrowseRepository(directory, { _, _ -> page("comic-1") })
            repository.refreshFirstPage(KEY, REQUEST, expectedRevision = null)

            assertNull(repository.readFirstPage(KEY.copy(pluginVersion = "2.0.0")))
            assertNull(repository.readFirstPage(KEY.copy(filters = mapOf("region" to "jp"))))
        }
    }

    @Test
    fun `manual refresh replaces a fresh entry`() = runBlocking {
        withCacheDirectory { directory ->
            val calls = AtomicInteger()
            val repository =
                PluginBrowseRepository(
                    directory,
                    { _, _ ->
                        page("comic-${calls.incrementAndGet()}")
                    },
                )
            val cached = repository.refreshFirstPage(KEY, REQUEST, expectedRevision = null)
            val refreshed =
                repository.refreshFirstPage(
                    KEY,
                    REQUEST,
                    expectedRevision = cached.revision,
                    force = true,
                )

            assertEquals("comic-2", refreshed.page.items.single().sourceId)
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun `disk failure does not discard a successful remote page`() = runBlocking {
        val cachePath = Files.createTempFile("inkleaf-plugin-browse", ".tmp").toFile()
        try {
            val repository = PluginBrowseRepository(cachePath, { _, _ -> page("comic-1") })
            val result = repository.refreshFirstPage(KEY, REQUEST, expectedRevision = null)

            assertEquals("comic-1", result.page.items.single().sourceId)
            assertEquals(result, repository.readFirstPage(KEY))
        } finally {
            cachePath.delete()
        }
    }

    @Test
    fun `stale content remains readable when refresh fails`() = runBlocking {
        withCacheDirectory { directory ->
            var now = 0L
            var fail = false
            val repository =
                PluginBrowseRepository(
                    cacheDirectory = directory,
                    remoteBrowse = { _, _ ->
                        if (fail) error("network unavailable")
                        page("cached")
                    },
                    ttlMs = 10L,
                    clockMs = { now },
                )
            val cached = repository.refreshFirstPage(KEY, REQUEST, expectedRevision = null)
            now = 11L
            fail = true

            assertFalse(repository.isFresh(cached))
            runCatching {
                repository.refreshFirstPage(KEY, REQUEST, expectedRevision = cached.revision)
            }
            assertEquals(cached, repository.readFirstPage(KEY))
        }
    }

    @Test
    fun `corrupt disk entry is discarded as a cache miss`() = runBlocking {
        withCacheDirectory { directory ->
            val repository = PluginBrowseRepository(directory, { _, _ -> page("comic-1") })
            repository.refreshFirstPage(KEY, REQUEST, expectedRevision = null)
            directory.listFiles().orEmpty().single { it.extension == "json" }.writeText("not-json")

            val restoredRepository = PluginBrowseRepository(directory, { _, _ -> page("comic-2") })
            assertNull(restoredRepository.readFirstPage(KEY))
            assertTrue(directory.listFiles().orEmpty().none { it.extension == "json" })
        }
    }

    @Test
    fun `concurrent cache misses share one remote request`() = runBlocking {
        withCacheDirectory { directory ->
            val calls = AtomicInteger()
            val repository =
                PluginBrowseRepository(
                    directory,
                    { _, _ ->
                        calls.incrementAndGet()
                        delay(25)
                        page("comic-1")
                    },
                )

            val results =
                listOf(
                        async {
                            repository.refreshFirstPage(KEY, REQUEST, expectedRevision = null)
                        },
                        async {
                            repository.refreshFirstPage(KEY, REQUEST, expectedRevision = null)
                        },
                    )
                    .awaitAll()

            assertEquals(results[0], results[1])
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `concurrent forced refreshes share one new revision`() = runBlocking {
        withCacheDirectory { directory ->
            val calls = AtomicInteger()
            val repository =
                PluginBrowseRepository(
                    directory,
                    { _, _ ->
                        calls.incrementAndGet()
                        delay(25)
                        page("comic-${calls.get()}")
                    },
                )
            val cached = repository.refreshFirstPage(KEY, REQUEST, expectedRevision = null)

            val results =
                listOf(
                        async {
                            repository.refreshFirstPage(KEY, REQUEST, cached.revision, force = true)
                        },
                        async {
                            repository.refreshFirstPage(KEY, REQUEST, cached.revision, force = true)
                        },
                    )
                    .awaitAll()

            assertEquals(results[0].revision, results[1].revision)
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun `clear waits for an in-flight refresh before deleting its result`() = runBlocking {
        withCacheDirectory { directory ->
            val remoteStarted = CompletableDeferred<Unit>()
            val finishRemote = CompletableDeferred<Unit>()
            val clearStarted = CompletableDeferred<Unit>()
            val repository =
                PluginBrowseRepository(
                    directory,
                    { _, _ ->
                        remoteStarted.complete(Unit)
                        finishRemote.await()
                        page("comic-1")
                    },
                )
            val refresh = async {
                repository.refreshFirstPage(KEY, REQUEST, expectedRevision = null)
            }
            remoteStarted.await()
            val clear = async {
                clearStarted.complete(Unit)
                repository.clear(KEY.pluginId)
            }
            clearStarted.await()

            finishRemote.complete(Unit)
            val refreshed = refresh.await()
            clear.await()

            assertTrue(repository.cacheGeneration(KEY.pluginId) > refreshed.cacheGeneration)
            assertNull(repository.readFirstPage(KEY))
        }
    }

    private suspend fun withCacheDirectory(block: suspend (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("inkleaf-plugin-browse").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        val KEY =
            PluginBrowseCacheKey(
                pluginId = "io.example.source",
                pluginVersion = "1.0.0",
                feedId = "recommended",
                filters = mapOf("region" to "all"),
            )
        val REQUEST =
            PluginBrowseRequest(
                feedId = "recommended",
                filters = mapOf("region" to "all"),
            )

        fun page(sourceId: String) =
            PluginSearchPage(
                items = listOf(ComicSummary(sourceId = sourceId, title = sourceId)),
                nextCursor = "next",
            )
    }
}
