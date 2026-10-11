package com.exio.inkleaf.plugin

import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.OnlinePageCache
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineChapterSourceTest {
    private val temporaryRoots = mutableListOf<File>()

    @After
    fun deleteTemporaryRoots() {
        temporaryRoots.forEach { it.deleteRecursively() }
        temporaryRoots.clear()
    }

    @Test
    fun `open returns the revision the source advertises`() = runBlocking {
        val opened = source(pages(listOf("p1", "p2"), revision = "r1")).open(chapter(), null)

        try {
            assertEquals("r1", opened.revision)
            assertEquals(2, opened.volume.pages.size)
            assertTrue(opened.volume.cacheKeyPrefix.isNotBlank())
        } finally {
            opened.volume.close()
        }
    }

    @Test
    fun `open rejects a chapter that advertises no pages`() {
        assertThrows(ComicOpenException::class.java) {
            runBlocking { source(pages(emptyList())).open(chapter(), null) }
        }
    }

    @Test
    fun `open writes one manifest for the opened chapter`() = runBlocking {
        val cache = newCache()
        val opened = source(pages(listOf("p1")), cache = cache).open(chapter(), null)

        try {
            assertEquals(1, cache.manifestFiles().count())
        } finally {
            opened.volume.close()
        }
    }

    @Test
    fun `open derives a revision from page ids when the source omits one`() = runBlocking {
        val opened = source(pages(listOf("p1", "p2"), revision = null)).open(chapter(revision = null), null)

        try {
            assertTrue(opened.revision.startsWith("page-ids:"))
        } finally {
            opened.volume.close()
        }
    }

    @Test
    fun `revalidate adopts the same pages under a new plugin version`() = runBlocking {
        val port =
            FakePages(
                pages(listOf("p1"), revision = "r1"),
                pages(listOf("p1"), revision = "r1"),
            )
        val source = source(port)
        val opened = source.open(chapter(), null)
        port.pluginVersion = "2.0.0"

        try {
            val result = source.revalidate(opened.volume, chapter(), null, isCurrent = { true })

            assertEquals(OnlineChapterRevalidation.Replaced(true), result)
        } finally {
            opened.volume.close()
        }
    }

    @Test
    fun `revalidate reports an unchanged source as replaced in place`() = runBlocking {
        val source =
            source(
                pages(listOf("p1"), revision = "r1"),
                pages(listOf("p1"), revision = "r1"),
            )
        val opened = source.open(chapter(), null)

        try {
            val result = source.revalidate(opened.volume, chapter(), null, isCurrent = { true })

            assertEquals(OnlineChapterRevalidation.Replaced(false), result)
        } finally {
            opened.volume.close()
        }
    }

    @Test
    fun `revalidate returns a replacement volume when the revision moves`() = runBlocking {
        val port =
            FakePages(
                pages(listOf("p1"), revision = "r1"),
                pages(listOf("p1"), revision = "r2"),
            )
        val source = source(port)
        val opened = source.open(chapter(), null)

        try {
            val result = source.revalidate(opened.volume, chapter(), null, isCurrent = { true })

            val replacement = result as OnlineChapterRevalidation.NewRevision
            assertEquals("r2", replacement.revision)
            assertEquals(1, replacement.volume.pages.size)
            assertNotEquals(opened.volume.cacheKeyPrefix, replacement.volume.cacheKeyPrefix)
            replacement.volume.close()
        } finally {
            opened.volume.close()
        }
    }

    @Test
    fun `revalidate does nothing once the caller reports the volume superseded`() = runBlocking {
        val port =
            FakePages(
                pages(listOf("p1"), revision = "r1"),
                pages(listOf("p1"), revision = "r2"),
            )
        val source = source(port)
        val opened = source.open(chapter(), null)

        try {
            val result = source.revalidate(opened.volume, chapter(), null, isCurrent = { false })

            assertEquals(OnlineChapterRevalidation.None, result)
        } finally {
            opened.volume.close()
        }
    }

    @Test
    fun `revalidate ignores a source that no longer advertises pages`() = runBlocking {
        val port = FakePages(pages(listOf("p1"), revision = "r1"), pages(emptyList()))
        val source = source(port)
        val opened = source.open(chapter(), null)

        try {
            val result = source.revalidate(opened.volume, chapter(), null, isCurrent = { true })

            assertEquals(OnlineChapterRevalidation.None, result)
        } finally {
            opened.volume.close()
        }
    }

    @Test
    fun `revalidate keeps the volume when the same revision returns different pages`() =
        runBlocking {
            val port =
                FakePages(
                    pages(listOf("p1"), revision = "r1"),
                    pages(listOf("p2"), revision = "r1"),
                )
            val source = source(port)
            val opened = source.open(chapter(), null)

            try {
                val result = source.revalidate(opened.volume, chapter(), null, isCurrent = { true })

                assertEquals(OnlineChapterRevalidation.None, result)
            } finally {
                opened.volume.close()
            }
        }

    @Test
    fun `a rejected page refreshes the descriptors through the port and retries`() = runBlocking {
        val port =
            FakePages(
                pages(listOf("p1"), revision = "r1"),
                pages(listOf("p1"), revision = "r1"),
            )
        val source = source(port, client = httpFactory(401, 200))
        val opened = source.open(chapter(), null)

        try {
            assertEquals(listOf(1, 2, 3), opened.volume.loadPageBytes(0).map { it.toInt() })
            assertEquals(2, port.fetches)
        } finally {
            opened.volume.close()
        }
    }

    @Test
    fun `page reads retry once when the plugin version moves mid-read`() = runBlocking {
        val versions = listOf("1.0.0", "2.0.0", "2.0.0", "2.0.0").iterator()
        var reads = 0
        val port =
            VersionedChapterPages(
                readPages = {
                    reads += 1
                    pages(listOf("p1"), revision = "r1")
                },
                activeVersion = { versions.next() },
            )

        val fetched = port.fetch(request())

        assertEquals("2.0.0", fetched.pluginVersion)
        assertEquals(2, reads)
    }

    @Test
    fun `page reads give up when the plugin version keeps moving`() {
        val versions = listOf("1.0.0", "2.0.0", "1.0.0", "2.0.0").iterator()
        val port =
            VersionedChapterPages(
                readPages = { pages(listOf("p1"), revision = "r1") },
                activeVersion = { versions.next() },
            )

        assertThrows(ComicOpenException::class.java) {
            runBlocking { port.fetch(request()) }
        }
    }

    @Test
    fun `page reads fail when no plugin version is active`() {
        val port =
            VersionedChapterPages(
                readPages = { pages(listOf("p1"), revision = "r1") },
                activeVersion = { null },
            )

        assertThrows(ComicOpenException::class.java) {
            runBlocking { port.fetch(request()) }
        }
    }

    private fun source(
        vararg responses: PluginPagesResponse,
        cache: OnlinePageCache = newCache(),
        client: Call.Factory = offlineClient(),
    ): OnlineChapterSource =
        OnlineChapterSource(
            pluginId = PLUGIN_ID,
            sourceId = SOURCE_ID,
            pages = FakePages(*responses),
            client = client,
            cache = cache,
        )

    private fun source(port: OnlineChapterPages): OnlineChapterSource =
        OnlineChapterSource(
            pluginId = PLUGIN_ID,
            sourceId = SOURCE_ID,
            pages = port,
            client = offlineClient(),
            cache = newCache(),
        )

    private fun source(port: OnlineChapterPages, client: Call.Factory): OnlineChapterSource =
        OnlineChapterSource(
            pluginId = PLUGIN_ID,
            sourceId = SOURCE_ID,
            pages = port,
            client = client,
            cache = newCache(),
        )

    private fun offlineClient(): Call.Factory = Call.Factory { error("Network is not used") }

    private fun newCache(): OnlinePageCache {
        val root = Files.createTempDirectory("online-chapter-source").toFile()
        temporaryRoots += root
        return OnlinePageCache(root)
    }

    private fun httpFactory(vararg codes: Int): Call.Factory {
        val remaining = ArrayDeque(codes.toList())
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val code = remaining.removeFirst()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("code-$code")
                    .body(
                        byteArrayOf(1, 2, 3)
                            .toResponseBody(if (code == 200) "image/jpeg".toMediaTypeOrNull() else null)
                    )
                    .build()
            }
            .build()
    }

    private fun chapter(revision: String? = "r1"): ChapterSummary =
        ChapterSummary(CHAPTER_ID, "Chapter 1", revision = revision)

    private fun request(): PluginPagesRequest =
        PluginPagesRequest(sourceId = SOURCE_ID, chapterId = CHAPTER_ID)

    private fun pages(
        pageIds: List<String?>,
        revision: String? = "r1",
    ): PluginPagesResponse =
        PluginPagesResponse(
            sourceId = SOURCE_ID,
            chapterId = CHAPTER_ID,
            revision = revision,
            pages =
                pageIds.mapIndexed { index, pageId ->
                    PageDescriptor(
                        pageId = pageId,
                        index = index,
                        url = "https://example.test/$index.jpg",
                    )
                },
        )

    private class FakePages(private val queued: MutableList<PluginPagesResponse>) :
        OnlineChapterPages {
        var pluginVersion: String = "1.0.0"
        var fetches: Int = 0
            private set

        constructor(vararg responses: PluginPagesResponse) : this(responses.toMutableList())

        override suspend fun fetch(request: PluginPagesRequest): FetchedChapterPages {
            fetches += 1
            return FetchedChapterPages(queued.removeFirst(), pluginVersion, FETCHED_AT_MS)
        }
    }

    private companion object {
        const val PLUGIN_ID = "plugin.test"
        const val SOURCE_ID = "comic-1"
        const val CHAPTER_ID = "chapter-1"
        const val FETCHED_AT_MS = 1_000L
    }
}
