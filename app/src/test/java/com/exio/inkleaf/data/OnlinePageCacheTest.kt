package com.exio.inkleaf.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlinePageCacheTest {
    @Test
    fun `concurrent misses share one load and disk entry survives recreation`() = runBlocking {
        withCacheRoot { root ->
            val calls = AtomicInteger()
            val cache = OnlinePageCache(root)
            val key = key(identity())
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val loads =
                List(3) {
                    async(start = CoroutineStart.UNDISPATCHED) {
                        cache.getOrLoad(key, OnlinePageLoadPriority.FOREGROUND) {
                            calls.incrementAndGet()
                            started.complete(Unit)
                            finish.await()
                            PAGE_BYTES
                        }
                    }
                }
            withTimeout(TEST_TIMEOUT_MS) { started.await() }
            finish.complete(Unit)
            val results = withTimeout(TEST_TIMEOUT_MS) { loads.awaitAll() }

            assertTrue(results.all { it.contentEquals(PAGE_BYTES) })
            assertEquals(1, calls.get())

            val restored =
                OnlinePageCache(root).getOrLoad(key, OnlinePageLoadPriority.FOREGROUND) {
                    error("A persisted cache hit must not call the loader")
                }
            assertTrue(restored.contentEquals(PAGE_BYTES))
        }
    }

    @Test
    fun `cache identity dimensions are isolated and secrets stay out of paths and manifest`() =
        runBlocking {
            withCacheRoot { root ->
                val cache = OnlinePageCache(root)
                val identities =
                    listOf(
                        identity(),
                        identity(pluginId = "plugin.other"),
                        identity(pluginVersion = "2.0.0"),
                        identity(accessScope = "private-scope-b"),
                        identity(sourceId = "source-other"),
                        identity(chapterId = "chapter-other"),
                        identity(revision = "revision-other"),
                    )
                val paths = identities.map { cache.pageFile(key(it)).absolutePath }.toMutableSet()
                paths += cache.pageFile(key(identity(), pageIdentity = "page-other")).absolutePath
                assertEquals(8, paths.size)
                paths.forEach { path ->
                    assertFalse(path.contains("private-scope"))
                    assertFalse(path.contains("plugin.test"))
                    assertFalse(path.contains("source-1"))
                }

                cache.writeManifest(
                    identity = identity(accessScope = "private-scope-a"),
                    pageIdentities = listOf("page-secret"),
                    fetchedAtMs = 123L,
                )
                val manifest = cache.manifestFiles().single().readText()
                assertFalse(manifest.contains("private-scope-a"))
                assertFalse(manifest.contains("page-secret"))
                assertFalse(manifest.contains("https://example.com/private.jpg"))
                assertFalse(manifest.contains("Authorization"))
                assertFalse(manifest.contains("Cookie"))
                assertFalse(manifest.contains("Referer"))
            }
        }

    @Test
    fun `clear prevents an in-flight foreground load from republishing`() = runBlocking {
        withCacheRoot { root ->
            val cache = OnlinePageCache(root)
            val key = key(identity())
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val load =
                async(start = CoroutineStart.UNDISPATCHED) {
                    cache.getOrLoad(key, OnlinePageLoadPriority.FOREGROUND) {
                        started.complete(Unit)
                        finish.await()
                        PAGE_BYTES
                    }
                }

            withTimeout(TEST_TIMEOUT_MS) { started.await() }
            cache.clear()
            finish.complete(Unit)

            assertTrue(withTimeout(TEST_TIMEOUT_MS) { load.await() }.contentEquals(PAGE_BYTES))
            assertFalse(cache.pageFile(key).exists())
        }
    }

    @Test
    fun `foreground waiter survives speculative caller cancellation`() = runBlocking {
        withCacheRoot { root ->
            val cache = OnlinePageCache(root)
            val key = key(identity())
            val calls = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val speculative =
                async(start = CoroutineStart.UNDISPATCHED) {
                    cache.getOrLoad(key, OnlinePageLoadPriority.SPECULATIVE) {
                        calls.incrementAndGet()
                        started.complete(Unit)
                        finish.await()
                        PAGE_BYTES
                    }
                }

            withTimeout(TEST_TIMEOUT_MS) { started.await() }
            val foreground =
                async(start = CoroutineStart.UNDISPATCHED) {
                    cache.getOrLoad(key, OnlinePageLoadPriority.FOREGROUND) {
                        error("The foreground waiter must join the existing flight")
                    }
                }
            speculative.cancelAndJoin()
            finish.complete(Unit)

            assertTrue(withTimeout(TEST_TIMEOUT_MS) { foreground.await() }.contentEquals(PAGE_BYTES))
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `clear pending marker disables persisted hits and new commits`() = runBlocking {
        withCacheRoot { root ->
            val cache = OnlinePageCache(root)
            val key = key(identity())
            cache.getOrLoad(key, OnlinePageLoadPriority.FOREGROUND) { PAGE_BYTES }
            val pageFile = cache.pageFile(key)
            val marker = File(root.parentFile, ".${root.name}.clear-pending")
            assertTrue(marker.createNewFile())
            val restored = OnlinePageCache(root)

            val calls = AtomicInteger()
            repeat(2) {
                val loaded =
                    restored.getOrLoad(key, OnlinePageLoadPriority.FOREGROUND) {
                        calls.incrementAndGet()
                        REPLACEMENT_BYTES
                    }
                assertTrue(loaded.contentEquals(REPLACEMENT_BYTES))
            }

            assertEquals(2, calls.get())
            assertTrue(pageFile.readBytes().contentEquals(PAGE_BYTES))
            restored.cleanupOnColdStart(Long.MAX_VALUE)
            assertFalse(marker.exists())
            assertFalse(root.exists())
        }
    }

    @Test
    fun `empty and oversized files become misses`() = runBlocking {
        withCacheRoot { root ->
            val cache = OnlinePageCache(root, maxPageBytes = 4L)
            val key = key(identity())
            val file = cache.pageFile(key)
            file.parentFile?.mkdirs()

            file.writeBytes(ByteArray(0))
            assertEquals(
                listOf(1, 2, 3),
                cache
                    .getOrLoad(key, OnlinePageLoadPriority.FOREGROUND) { byteArrayOf(1, 2, 3) }
                    .map { it.toInt() },
            )

            file.writeBytes(ByteArray(5))
            assertEquals(
                listOf(4, 3, 2, 1),
                cache
                    .getOrLoad(key, OnlinePageLoadPriority.FOREGROUND) {
                        byteArrayOf(4, 3, 2, 1)
                    }
                    .map { it.toInt() },
            )
        }
    }

    @Test
    fun `download concurrency is one foreground and two speculative`() = runBlocking {
        withCacheRoot { root ->
            val cache = OnlinePageCache(root)
            assertEquals(
                1,
                measuredConcurrency(cache, OnlinePageLoadPriority.FOREGROUND, expected = 1),
            )
            assertEquals(
                2,
                measuredConcurrency(cache, OnlinePageLoadPriority.SPECULATIVE, expected = 2),
            )
        }
    }

    @Test
    fun `disk failure does not discard loaded bytes`() = runBlocking {
        val rootFile = Files.createTempFile("online-page-cache", ".tmp").toFile()
        try {
            val cache = OnlinePageCache(rootFile)
            val calls = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val key = key(identity())
            val loads =
                List(3) {
                    async(start = CoroutineStart.UNDISPATCHED) {
                        cache.getOrLoad(key, OnlinePageLoadPriority.FOREGROUND) {
                            calls.incrementAndGet()
                            started.complete(Unit)
                            finish.await()
                            PAGE_BYTES
                        }
                    }
                }
            withTimeout(TEST_TIMEOUT_MS) { started.await() }
            finish.complete(Unit)
            val loaded = withTimeout(TEST_TIMEOUT_MS) { loads.awaitAll() }

            assertTrue(loaded.all { it.contentEquals(PAGE_BYTES) })
            assertEquals(1, calls.get())
            // The simulated disk failure (root is a plain file) must not create a page file.
            assertFalse(cache.pageFile(key).exists())
        } finally {
            rootFile.delete()
        }
    }

    private suspend fun measuredConcurrency(
        cache: OnlinePageCache,
        priority: OnlinePageLoadPriority,
        expected: Int,
    ): Int = coroutineScope {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val reachedExpected = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loads =
            List(3) { index ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    cache.getOrLoad(key(identity(), "${priority.name}-$index"), priority) {
                        val now = active.incrementAndGet()
                        maximum.updateAndGet { current -> maxOf(current, now) }
                        if (now >= expected) reachedExpected.complete(Unit)
                        try {
                            release.await()
                            PAGE_BYTES
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }
        withTimeout(TEST_TIMEOUT_MS) { reachedExpected.await() }
        release.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { loads.awaitAll() }
        maximum.get()
    }

    private fun identity(
        pluginId: String = "plugin.test",
        pluginVersion: String = "1.0.0",
        accessScope: String = "private-scope-a",
        sourceId: String = "source-1",
        chapterId: String = "chapter-1",
        revision: String = "revision-1",
    ): OnlinePageCacheIdentity =
        OnlinePageCacheIdentity.create(
            pluginId = pluginId,
            pluginVersion = pluginVersion,
            accessScope = accessScope,
            sourceId = sourceId,
            chapterId = chapterId,
            revision = revision,
        )

    private fun key(
        identity: OnlinePageCacheIdentity,
        pageIdentity: String = "page-1",
    ): OnlinePageCacheKey = OnlinePageCacheKey.create(identity, pageIdentity)

    private inline fun withCacheRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("online-page-cache").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
            File(root.parentFile, ".${root.name}.clear-pending").delete()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 5_000L
        val PAGE_BYTES = byteArrayOf(9, 8, 7, 6)
        val REPLACEMENT_BYTES = byteArrayOf(6, 7, 8, 9)
    }
}
