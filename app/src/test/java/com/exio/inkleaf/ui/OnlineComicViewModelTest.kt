package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.ComicDetail
import com.exio.inkleaf.plugin.ComicSummary
import com.exio.inkleaf.plugin.OnlineComicRecord
import com.exio.inkleaf.plugin.OnlineContentKey
import com.exio.inkleaf.plugin.OnlineAvailability
import com.exio.inkleaf.plugin.PageImage
import com.exio.inkleaf.plugin.PluginChaptersResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineComicViewModelTest {
    @Test
    fun `matching fresh detail and chapter snapshots skip refresh`() {
        val record = record(detailFetchedAtMs = 100L, chaptersFetchedAtMs = 100L)

        assertTrue(record.isFresh(pluginVersion = "1.0.0", nowMs = 150L, ttlMs = 100L))
    }

    @Test
    fun `empty chapter response can still be fresh`() {
        val record = record(detailFetchedAtMs = 100L, chaptersFetchedAtMs = 100L)

        assertTrue(record.chapters.isEmpty())
        assertTrue(record.isFresh(pluginVersion = "1.0.0", nowMs = 150L, ttlMs = 100L))
    }

    @Test
    fun `legacy timestamps and plugin changes require refresh`() {
        val legacy = record(detailFetchedAtMs = 0L, chaptersFetchedAtMs = 0L)
        val current = record(detailFetchedAtMs = 100L, chaptersFetchedAtMs = 100L)

        assertFalse(legacy.isFresh(pluginVersion = "1.0.0", nowMs = 150L, ttlMs = 100L))
        assertFalse(current.isFresh(pluginVersion = "2.0.0", nowMs = 150L, ttlMs = 100L))
        assertFalse(current.isFresh(pluginVersion = "1.0.0", nowMs = 201L, ttlMs = 100L))
        assertFalse(
            current
                .copy(availability = OnlineAvailability.TEMPORARY_ERROR)
                .isFresh(pluginVersion = "1.0.0", nowMs = 150L, ttlMs = 100L)
        )
    }

    @Test
    fun `route seed is bounded and excludes authenticated cover headers`() {
        val summary =
            ComicSummary(
                sourceId = SOURCE_ID,
                title = "漫".repeat(1000),
                subtitle = "A".repeat(2000),
                cover =
                    PageImage(
                        url = "https://example.com/cover.jpg",
                        headers = mapOf("Authorization" to "secret"),
                    ),
                tags = List(20) { "标".repeat(200) },
            )

        val seed = summary.toRouteSeed()

        assertTrue(seed.title.toByteArray().size <= 1024)
        assertTrue(seed.subtitle.orEmpty().toByteArray().size <= 1024)
        assertEquals(null, seed.coverUrl)
        assertEquals(8, seed.tags.size)
        assertTrue(seed.tags.all { it.toByteArray().size <= 256 })
    }

    @Test
    fun `browse summary seeds visible detail without inventing full metadata`() {
        val summary =
            ComicSummary(
                sourceId = SOURCE_ID,
                title = "Comic",
                subtitle = "Author",
                cover = PageImage(url = "https://example.com/cover.jpg"),
                tags = listOf("Drama"),
            )

        val detail = summary.toRouteSeed().toDetail(SOURCE_ID)

        assertEquals("Comic", detail.title)
        assertEquals("Author", detail.subtitle)
        assertEquals(summary.cover, detail.cover)
        assertEquals(listOf("Drama"), detail.tags)
        assertEquals(null, detail.description)
        assertEquals(null, detail.status)
    }

    private fun record(detailFetchedAtMs: Long, chaptersFetchedAtMs: Long) =
        OnlineComicRecord(
            key = OnlineContentKey(PLUGIN_ID, SOURCE_ID),
            detail = ComicDetail(SOURCE_ID, "Comic"),
            detailFetchedAtMs = detailFetchedAtMs,
            detailPluginVersion = "1.0.0",
            chapters = PluginChaptersResponse(SOURCE_ID).chapters,
            chaptersFetchedAtMs = chaptersFetchedAtMs,
            chaptersPluginVersion = "1.0.0",
        )

    private companion object {
        const val PLUGIN_ID = "io.example.source"
        const val SOURCE_ID = "comic-1"
    }
}
