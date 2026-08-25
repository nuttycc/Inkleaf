package com.exio.inkleaf.plugin

import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.OnlinePageCache
import com.exio.inkleaf.data.OnlinePageCacheIdentity
import com.exio.inkleaf.data.OnlinePageCacheKey
import com.exio.inkleaf.data.OnlinePageLoadPriority
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineChapterVolumeTest {
    private val temporaryRoots = mutableListOf<File>()

    @After
    fun deleteTemporaryRoots() {
        temporaryRoots.forEach { it.deleteRecursively() }
        temporaryRoots.clear()
    }

    @Test
    fun `ordered page ids provide a URL independent revision fallback`() {
        val first = response(pageIds = listOf("page-1", "page-2"))
        val sameIdsDifferentUrls =
            response(pageIds = listOf("page-1", "page-2"), urlSuffix = "-new")
        val reordered = response(pageIds = listOf("page-2", "page-1"))

        val revision = resolveOnlineChapterRevision(CHAPTER_ID, null, first)

        assertTrue(revision.startsWith("page-ids:"))
        assertEquals(revision, resolveOnlineChapterRevision(CHAPTER_ID, null, sameIdsDifferentUrls))
        assertNotEquals(revision, resolveOnlineChapterRevision(CHAPTER_ID, null, reordered))
    }

    @Test
    fun `missing page id requires an explicit revision`() {
        val response = response(pageIds = listOf(null))

        assertThrows(ComicOpenException::class.java) {
            resolveOnlineChapterRevision(CHAPTER_ID, null, response)
        }
        assertEquals(
            "chapter-r1",
            resolveOnlineChapterRevision(CHAPTER_ID, "chapter-r1", response),
        )
    }

    @Test
    fun `explicit and fallback page identities use separate namespaces`() {
        val revision = "chapter-r1"
        val oldFallbackCollision = "revision:$revision:index:1"
        val volume = volume(pageIds = listOf(oldFallbackCollision, null), revision = revision)
        try {
            assertNotEquals(volume.pageIdentity(0), volume.pageIdentity(1))
        } finally {
            volume.close()
        }
    }

    @Test
    fun `blank page ids are rejected by the volume invariant`() {
        assertThrows(IllegalArgumentException::class.java) {
            volume(pageIds = listOf(" ")).close()
        }
    }

    @Test
    fun `page body is rejected before an unknown-length response grows past its limit`() {
        val body = unknownLengthBody(ByteArray(6))

        assertThrows(ComicOpenException::class.java) { body.readPageBytes(maxBytes = 5L) }
    }

    @Test
    fun `page body at the limit is returned`() {
        val bytes = ByteArray(5) { it.toByte() }

        assertEquals(bytes.toList(), unknownLengthBody(bytes).readPageBytes(5L).toList())
    }

    @Test
    fun `cache hit avoids a call and decode invalidation refetches only once`() = runBlocking {
        val root = Files.createTempDirectory("online-volume-cache").toFile()
        try {
            val identity = cacheIdentity()
            val cache = OnlinePageCache(root)
            val pages = response(listOf("page-1")).pages
            val key =
                OnlinePageCacheKey.create(identity, onlinePageIdentities(REVISION, pages).single())
            cache.getOrLoad(key, OnlinePageLoadPriority.FOREGROUND) { byteArrayOf(1, 2, 3) }
            val factory = callFactory(HttpOutcome(200, byteArrayOf(4, 5, 6)))
            val volume = volume(pages, cache, identity, factory)
            try {
                assertEquals(listOf(1, 2, 3), volume.loadPageBytes(0).map { it.toInt() })
                assertEquals(0, factory.calls.get())
                assertTrue(volume.invalidatePage(0))
                assertFalse(volume.invalidatePage(0))
                assertEquals(listOf(4, 5, 6), volume.loadPageBytes(0).map { it.toInt() })
                assertEquals(1, factory.calls.get())
            } finally {
                volume.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `page and thumbnail requests share one in flight call`() = runBlocking {
        val root = Files.createTempDirectory("online-volume-flight").toFile()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val identity = cacheIdentity()
            val factory =
                callFactory(
                    HttpOutcome(
                        200,
                        byteArrayOf(7, 8, 9),
                        started = started,
                        release = release,
                    )
                )
            val volume =
                volume(
                    response(listOf("page-1")).pages,
                    OnlinePageCache(root),
                    identity,
                    factory,
                )
            try {
                val page = async(start = CoroutineStart.UNDISPATCHED) { volume.loadPageBytes(0) }
                assertTrue(started.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                val thumbnail =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        volume.loadThumbnailPageBytes(0)
                    }
                release.countDown()
                val results = withTimeout(TEST_TIMEOUT_MS) { listOf(page, thumbnail).awaitAll() }
                assertTrue(results.all { it.contentEquals(byteArrayOf(7, 8, 9)) })
                assertEquals(1, factory.calls.get())
            } finally {
                release.countDown()
                volume.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `foreground retries one transient failure while speculative does not`() = runBlocking {
        val foregroundRoot = Files.createTempDirectory("online-volume-foreground").toFile()
        val speculativeRoot = Files.createTempDirectory("online-volume-speculative").toFile()
        try {
            val foregroundFactory =
                callFactory(
                    FailureOutcome(IOException("temporary")),
                    HttpOutcome(200, byteArrayOf(1)),
                )
            val foreground =
                volume(
                    response(listOf("page-1")).pages,
                    OnlinePageCache(foregroundRoot),
                    cacheIdentity(),
                    foregroundFactory,
                )
            try {
                assertEquals(listOf(1), foreground.loadPageBytes(0).map { it.toInt() })
                assertEquals(2, foregroundFactory.calls.get())
            } finally {
                foreground.close()
            }

            val speculativeFactory =
                callFactory(
                    FailureOutcome(IOException("temporary")),
                    HttpOutcome(200, byteArrayOf(2)),
                )
            val speculative =
                volume(
                    response(listOf("page-1")).pages,
                    OnlinePageCache(speculativeRoot),
                    cacheIdentity(),
                    speculativeFactory,
                )
            try {
                val error = runCatching { speculative.loadThumbnailPageBytes(0) }.exceptionOrNull()
                assertTrue(error is ComicOpenException)
                assertEquals(1, speculativeFactory.calls.get())
            } finally {
                speculative.close()
            }
        } finally {
            foregroundRoot.deleteRecursively()
            speculativeRoot.deleteRecursively()
        }
    }

    @Test
    fun `foreground retries one server failure`() = runBlocking {
        val root = Files.createTempDirectory("online-volume-server-retry").toFile()
        try {
            val factory = callFactory(HttpOutcome(503), HttpOutcome(200, byteArrayOf(6)))
            val volume =
                volume(
                    response(listOf("page-1")).pages,
                    OnlinePageCache(root),
                    cacheIdentity(),
                    factory,
                )
            try {
                assertEquals(listOf(6), volume.loadPageBytes(0).map { it.toInt() })
                assertEquals(2, factory.calls.get())
            } finally {
                volume.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `authorization response refreshes descriptors once and retries`() = runBlocking {
        val root = Files.createTempDirectory("online-volume-refresh").toFile()
        try {
            val factory = callFactory(HttpOutcome(401), HttpOutcome(200, byteArrayOf(3, 4)))
            val refreshes = AtomicInteger()
            val identity = cacheIdentity()
            val refreshedPages = response(listOf("page-1"), urlSuffix = "-fresh").pages
            val volume =
                volume(
                    response(listOf("page-1")).pages,
                    OnlinePageCache(root),
                    identity,
                    factory,
                    refreshChapter = {
                        refreshes.incrementAndGet()
                        OnlineChapterRefresh(refreshedPages, identity)
                    },
                )
            try {
                assertEquals(listOf(3, 4), volume.loadPageBytes(0).map { it.toInt() })
                assertEquals(1, refreshes.get())
                assertEquals(2, factory.calls.get())
            } finally {
                volume.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `prefetch authorization failure does not refresh descriptors`() = runBlocking {
        val root = Files.createTempDirectory("online-volume-prefetch-401").toFile()
        try {
            val factory = callFactory(HttpOutcome(401))
            val refreshes = AtomicInteger()
            val volume =
                volume(
                    response(listOf("page-1")).pages,
                    OnlinePageCache(root),
                    cacheIdentity(),
                    factory,
                    refreshChapter = {
                        refreshes.incrementAndGet()
                        OnlineChapterRefresh(response(listOf("page-1")).pages, cacheIdentity())
                    },
                )
            try {
                val error = runCatching { volume.prefetchPage(0) }.exceptionOrNull()
                assertTrue(error is ComicOpenException)
                assertEquals(0, refreshes.get())
                assertEquals(1, factory.calls.get())
            } finally {
                volume.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `descriptor refresh resets the decode retry budget`() = runBlocking {
        val root = Files.createTempDirectory("online-volume-retry-reset").toFile()
        try {
            val volume =
                volume(
                    response(listOf("page-1")).pages,
                    OnlinePageCache(root),
                    cacheIdentity(),
                    Call.Factory { error("Network is not used by retry-budget tests") },
                )
            try {
                assertTrue(volume.invalidatePage(0))
                assertFalse(volume.invalidatePage(0))
                assertTrue(
                    volume.replaceDescriptors(
                        OnlineChapterRefresh(response(listOf("page-1")).pages, cacheIdentity())
                    )
                )
                // The replacement brings fresh bytes, so the page may be retried once more.
                assertTrue(volume.invalidatePage(0))
            } finally {
                volume.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `closed volume does not protect a replacement identity`() = runBlocking {
        val root = Files.createTempDirectory("online-volume-close-protect").toFile()
        try {
            val cache = OnlinePageCache(root)
            val identity = cacheIdentity()
            val volume =
                volume(
                    response(listOf("page-1")).pages,
                    cache,
                    identity,
                    Call.Factory { error("Network is not used by close-protection tests") },
                )
            val replacement =
                OnlinePageCacheIdentity.create(
                    pluginId = "plugin.test",
                    pluginVersion = "1.0.0",
                    accessScope = "public",
                    sourceId = "comic-1",
                    chapterId = CHAPTER_ID,
                    revision = "chapter-r2",
                )
            volume.close()
            assertTrue(
                volume.replaceDescriptors(
                    OnlineChapterRefresh(response(listOf("page-1")).pages, replacement)
                )
            )
            // The closed volume must not leak cache protection for the replacement identity.
            assertFalse(
                cache.isProtected(cache.pageFile(OnlinePageCacheKey.create(replacement, "page-1")))
            )
            assertFalse(
                cache.isProtected(cache.pageFile(OnlinePageCacheKey.create(identity, "page-1")))
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `retry after above thirty seconds does not block the reader`() = runBlocking {
        val root = Files.createTempDirectory("online-volume-retry-after").toFile()
        try {
            val factory =
                callFactory(
                    HttpOutcome(429, headers = mapOf("Retry-After" to "31")),
                    HttpOutcome(200, byteArrayOf(5)),
                )
            val volume =
                volume(
                    response(listOf("page-1")).pages,
                    OnlinePageCache(root),
                    cacheIdentity(),
                    factory,
                )
            try {
                val error = runCatching { volume.loadPageBytes(0) }.exceptionOrNull()
                assertTrue(error is ComicOpenException)
                assertEquals(1, factory.calls.get())
            } finally {
                volume.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `retry after within thirty seconds retries once`() = runBlocking {
        val root = Files.createTempDirectory("online-volume-retry-after-zero").toFile()
        try {
            val factory =
                callFactory(
                    HttpOutcome(429, headers = mapOf("Retry-After" to "0")),
                    HttpOutcome(200, byteArrayOf(7)),
                )
            val volume =
                volume(
                    response(listOf("page-1")).pages,
                    OnlinePageCache(root),
                    cacheIdentity(),
                    factory,
                )
            try {
                assertEquals(listOf(7), volume.loadPageBytes(0).map { it.toInt() })
                assertEquals(2, factory.calls.get())
            } finally {
                volume.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `excessive retry after on 5xx preserves the status code`() = runBlocking {
        val root = Files.createTempDirectory("online-volume-retry-after-5xx").toFile()
        try {
            val factory = callFactory(HttpOutcome(503, headers = mapOf("Retry-After" to "60")))
            val volume =
                volume(
                    response(listOf("page-1")).pages,
                    OnlinePageCache(root),
                    cacheIdentity(),
                    factory,
                )
            try {
                val error = runCatching { volume.loadPageBytes(0) }.exceptionOrNull()
                assertTrue(error is ComicOpenException)
                assertEquals(503, (error as ComicOpenException).httpCode)
                assertTrue(error.message!!.contains("503"))
            } finally {
                volume.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `foreground network failures back off and then succeed`() = runBlocking {
        val root = Files.createTempDirectory("online-volume-backoff-recover").toFile()
        val observedDelays = mutableListOf<Long>()
        try {
            val factory =
                callFactory(
                    FailureOutcome(IOException("Unable to resolve host")),
                    FailureOutcome(IOException("Connection reset")),
                    HttpOutcome(200, byteArrayOf(9)),
                )
            val volume =
                volume(
                    response(listOf("page-1")).pages,
                    OnlinePageCache(root),
                    cacheIdentity(),
                    factory,
                    retryDelaysMillis = listOf(500, 2_000),
                    retryDelay = { observedDelays.add(it) },
                )
            try {
                assertEquals(listOf(9), volume.loadPageBytes(0).map { it.toInt() })
                assertEquals(3, factory.calls.get())
                // 两次退避都按配置值真实执行，而非立即重试
                assertEquals(listOf(500L, 2_000L), observedDelays)
            } finally {
                volume.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `foreground network failures give up after the backoff budget`() = runBlocking {
        val root = Files.createTempDirectory("online-volume-backoff-exhaust").toFile()
        val observedDelays = mutableListOf<Long>()
        try {
            val factory = callFactory(FailureOutcome(IOException("Unable to resolve host")))
            val volume =
                volume(
                    response(listOf("page-1")).pages,
                    OnlinePageCache(root),
                    cacheIdentity(),
                    factory,
                    retryDelaysMillis = listOf(500, 2_000),
                    retryDelay = { observedDelays.add(it) },
                )
            try {
                val error = runCatching { volume.loadPageBytes(0) }.exceptionOrNull()
                assertTrue(error is ComicOpenException)
                assertEquals(3, factory.calls.get())
                assertEquals(listOf(500L, 2_000L), observedDelays)
            } finally {
                volume.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `speculative loads do not retry network failures`() = runBlocking {
        val root = Files.createTempDirectory("online-volume-speculative").toFile()
        try {
            val factory = callFactory(FailureOutcome(IOException("Unable to resolve host")))
            val volume =
                volume(
                    response(listOf("page-1")).pages,
                    OnlinePageCache(root),
                    cacheIdentity(),
                    factory,
                    retryDelaysMillis = listOf(1, 1),
                )
            try {
                val error = runCatching { volume.loadThumbnailPageBytes(0) }.exceptionOrNull()
                assertTrue(error is ComicOpenException)
                assertEquals(1, factory.calls.get())
            } finally {
                volume.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun response(
        pageIds: List<String?>,
        urlSuffix: String = "",
    ): PluginPagesResponse =
        PluginPagesResponse(
            sourceId = "comic-1",
            chapterId = CHAPTER_ID,
            pages =
                pageIds.mapIndexed { index, pageId ->
                    PageDescriptor(
                        pageId = pageId,
                        index = index,
                        url = "https://example.com/page-$index$urlSuffix.jpg",
                    )
                },
        )

    private fun volume(
        pageIds: List<String?>,
        revision: String = "chapter-r1",
    ): OnlineChapterVolume {
        val identity =
            OnlinePageCacheIdentity.create(
                pluginId = "plugin.test",
                pluginVersion = "1.0.0",
                accessScope = "public",
                sourceId = "comic-1",
                chapterId = CHAPTER_ID,
                revision = revision,
            )
        val root = Files.createTempDirectory("online-volume-identity").toFile()
        temporaryRoots += root
        return OnlineChapterVolume(
            chapterId = CHAPTER_ID,
            title = "Chapter 1",
            sourceRevision = revision,
            pages = response(pageIds).pages,
            client = Call.Factory { error("Network is not used by identity tests") },
            cache = OnlinePageCache(root),
            initialCacheIdentity = identity,
        )
    }

    private fun volume(
        pages: List<PageDescriptor>,
        cache: OnlinePageCache,
        identity: OnlinePageCacheIdentity,
        client: Call.Factory,
        refreshChapter: (suspend () -> OnlineChapterRefresh?)? = null,
        retryDelaysMillis: List<Long> = listOf(500, 2_000),
        retryDelay: suspend (Long) -> Unit = {},
    ): OnlineChapterVolume =
        OnlineChapterVolume(
            chapterId = CHAPTER_ID,
            title = "Chapter 1",
            sourceRevision = REVISION,
            pages = pages,
            client = client,
            cache = cache,
            initialCacheIdentity = identity,
            refreshChapter = refreshChapter,
            networkRetryDelaysMillis = retryDelaysMillis,
            retryDelay = retryDelay,
        )

    private fun cacheIdentity(): OnlinePageCacheIdentity =
        OnlinePageCacheIdentity.create(
            pluginId = "plugin.test",
            pluginVersion = "1.0.0",
            accessScope = "public",
            sourceId = "comic-1",
            chapterId = CHAPTER_ID,
            revision = REVISION,
        )

    private fun callFactory(vararg outcomes: CallOutcome): CountingCallFactory {
        val queue = ArrayDeque<CallOutcome>().apply { outcomes.forEach(::addLast) }
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val outcome =
                        synchronized(queue) { queue.pollFirst() }
                            ?: throw IOException("Unexpected extra request")
                    when (outcome) {
                        is FailureOutcome -> throw outcome.error
                        is HttpOutcome -> {
                            outcome.started?.countDown()
                            outcome.release?.let { gate ->
                                if (!gate.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                                    throw IOException(
                                        "Timed out waiting to release the fake response"
                                    )
                                }
                            }
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(outcome.code)
                                .message("test")
                                .body(outcome.bytes.toResponseBody("image/jpeg".toMediaType()))
                                .apply {
                                    outcome.headers.forEach { (name, value) -> header(name, value) }
                                }
                                .build()
                        }
                    }
                }
                .build()
        return CountingCallFactory(client)
    }

    private class CountingCallFactory(private val delegate: Call.Factory) : Call.Factory {
        val calls = AtomicInteger()

        override fun newCall(request: Request): Call {
            calls.incrementAndGet()
            return delegate.newCall(request)
        }
    }

    private sealed interface CallOutcome

    private data class FailureOutcome(val error: IOException) : CallOutcome

    private data class HttpOutcome(
        val code: Int,
        val bytes: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
        val started: CountDownLatch? = null,
        val release: CountDownLatch? = null,
    ) : CallOutcome

    private fun unknownLengthBody(bytes: ByteArray): ResponseBody =
        object : ResponseBody() {
            override fun contentType(): MediaType? = null

            override fun contentLength(): Long = -1L

            override fun source(): BufferedSource = Buffer().write(bytes)
        }

    private companion object {
        const val CHAPTER_ID = "chapter-1"
        const val REVISION = "chapter-r1"
        const val TEST_TIMEOUT_MS = 5_000L
    }
}
