package com.exio.inkleaf.plugin

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginContentModelsTest {
    @Test
    fun `unknown descriptor types are isolated from supported descriptors`() {
        val encoded = PluginContentCodec.json.encodeToJsonElement(
            PluginDescribeResponse(
                actions = listOf(
                    PluginActionDescriptor("refresh", "Refresh"),
                    PluginActionDescriptor("custom", "Custom", kind = "futureWidget"),
                ),
                filters = listOf(PluginFilterDescriptor("genre", "Genre", type = "futureFilter")),
            )
        )
        val decoded = PluginContentCodec.describe(encoded)
        assertEquals(listOf("refresh"), decoded.actions.map { it.id })
        assertTrue(decoded.filters.isEmpty())
    }

    @Test
    fun `search identity and result limit are validated`() {
        val valid = PluginSearchPage(
            items = listOf(ComicSummary("comic-1", "Comic", opaqueContext = JsonPrimitive("opaque")))
        )
        val decoded = PluginContentCodec.searchPage(
            PluginContentCodec.json.encodeToJsonElement(valid),
            "io.example.source",
        )
        assertEquals("comic-1", decoded.items.single().sourceId)

        val oversized = PluginSearchPage(
            items = List(PluginContentLimits.MAX_SEARCH_ITEMS + 1) { ComicSummary("comic-$it", "Comic") }
        )
        assertTrue(
            runCatching {
                PluginContentCodec.searchPage(
                    PluginContentCodec.json.encodeToJsonElement(oversized),
                    "io.example.source",
                )
            }.exceptionOrNull() is PluginContentValidationException
        )
    }

    @Test
    fun `page indexes must be contiguous and URLs must be network URLs`() {
        val response = PluginPagesResponse(
            sourceId = "comic-1",
            chapterId = "chapter-1",
            pages = listOf(PageDescriptor(index = 1, url = "file:///tmp/page.jpg")),
        )
        assertTrue(
            runCatching {
                PluginContentCodec.pages(
                    PluginContentCodec.json.encodeToJsonElement(response),
                    "io.example.source",
                )
            }.exceptionOrNull() is PluginContentValidationException
        )
    }

    @Test
    fun `image headers must be valid OkHttp ASCII headers`() {
        val response = PluginPagesResponse(
            sourceId = "comic-1",
            chapterId = "chapter-1",
            pages = listOf(
                PageDescriptor(
                    index = 0,
                    url = "https://example.com/page.jpg",
                    headers = mapOf("X-Title" to "漫画"),
                )
            ),
        )

        assertTrue(
            runCatching {
                PluginContentCodec.pages(
                    PluginContentCodec.json.encodeToJsonElement(response),
                    "io.example.source",
                )
            }.exceptionOrNull() is PluginContentValidationException
        )
    }
}
