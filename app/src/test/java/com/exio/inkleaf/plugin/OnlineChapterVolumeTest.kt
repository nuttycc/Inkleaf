package com.exio.inkleaf.plugin

import com.exio.inkleaf.data.ComicOpenException
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineChapterVolumeTest {
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

        assertNotEquals(volume.pageIdentity(0), volume.pageIdentity(1))
    }

    @Test
    fun `blank page ids are rejected by the volume invariant`() {
        assertThrows(IllegalArgumentException::class.java) {
            volume(pageIds = listOf(" "))
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
    ): OnlineChapterVolume =
        OnlineChapterVolume(
            chapterId = CHAPTER_ID,
            title = "Chapter 1",
            sourceRevision = revision,
            pages = response(pageIds).pages,
            client = Call.Factory { error("Network is not used by identity tests") },
        )

    private fun unknownLengthBody(bytes: ByteArray): ResponseBody =
        object : ResponseBody() {
            override fun contentType(): MediaType? = null

            override fun contentLength(): Long = -1L

            override fun source(): BufferedSource = Buffer().write(bytes)
        }

    private companion object {
        const val CHAPTER_ID = "chapter-1"
    }
}
