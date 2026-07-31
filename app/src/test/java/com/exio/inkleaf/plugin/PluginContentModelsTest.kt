package com.exio.inkleaf.plugin

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginContentModelsTest {
    @Test
    fun `descriptors without feeds remain compatible`() {
        val decoded =
            PluginContentCodec.describe(
                PluginContentCodec.json.parseToJsonElement(
                    """{"schemaVersion":1,"actions":[],"filters":[],"settings":[]}"""
                )
            )

        assertTrue(decoded.feeds.isEmpty())
    }

    @Test
    fun `feed descriptors retain their own supported filters`() {
        val encoded =
            PluginContentCodec.json.encodeToJsonElement(
                PluginDescribeResponse(
                    feeds =
                        listOf(
                            PluginFeedDescriptor(
                                id = "rank",
                                title = "Ranking",
                                filters =
                                    listOf(
                                        PluginFilterDescriptor(
                                            id = "period",
                                            title = "Period",
                                            type = "select",
                                            options = listOf(PluginFilterOption("day", "Daily")),
                                        ),
                                        PluginFilterDescriptor("text", "Text filter"),
                                        PluginFilterDescriptor(
                                            "future",
                                            "Future",
                                            type = "futureFilter",
                                        ),
                                    ),
                            )
                        )
                )
            )

        val feed = PluginContentCodec.describe(encoded).feeds.single()
        assertEquals("rank", feed.id)
        assertEquals(listOf("period"), feed.filters.map { it.id })
    }

    @Test
    fun `feed and filter identifiers must be unique within their scope`() {
        val duplicateFeeds =
            PluginDescribeResponse(
                feeds =
                    listOf(
                        PluginFeedDescriptor("rank", "Ranking"),
                        PluginFeedDescriptor("rank", "Duplicate"),
                    )
            )
        val duplicateOptions =
            PluginDescribeResponse(
                feeds =
                    listOf(
                        PluginFeedDescriptor(
                            "rank",
                            "Ranking",
                            filters =
                                listOf(
                                    PluginFilterDescriptor(
                                        "period",
                                        "Period",
                                        options =
                                            listOf(
                                                PluginFilterOption("day", "Daily"),
                                                PluginFilterOption("day", "Duplicate"),
                                            ),
                                    )
                                ),
                        )
                    )
            )

        assertTrue(
            runCatching {
                    PluginContentCodec.describe(
                        PluginContentCodec.json.encodeToJsonElement(duplicateFeeds)
                    )
                }
                .exceptionOrNull() is PluginContentValidationException
        )
        assertTrue(
            runCatching {
                    PluginContentCodec.describe(
                        PluginContentCodec.json.encodeToJsonElement(duplicateOptions)
                    )
                }
                .exceptionOrNull() is PluginContentValidationException
        )
    }

    @Test
    fun `unknown descriptor types are isolated from supported descriptors`() {
        val encoded =
            PluginContentCodec.json.encodeToJsonElement(
                PluginDescribeResponse(
                    actions =
                        listOf(
                            PluginActionDescriptor("refresh", "Refresh"),
                            PluginActionDescriptor("custom", "Custom", kind = "futureWidget"),
                        ),
                    filters =
                        listOf(PluginFilterDescriptor("genre", "Genre", type = "futureFilter")),
                )
            )
        val decoded = PluginContentCodec.describe(encoded)
        assertEquals(listOf("refresh"), decoded.actions.map { it.id })
        assertTrue(decoded.filters.isEmpty())
    }

    @Test
    fun `search identity and result limit are validated`() {
        val valid =
            PluginSearchPage(
                items =
                    listOf(
                        ComicSummary("comic-1", "Comic", opaqueContext = JsonPrimitive("opaque"))
                    )
            )
        val decoded =
            PluginContentCodec.searchPage(
                PluginContentCodec.json.encodeToJsonElement(valid),
                "io.example.source",
            )
        assertEquals("comic-1", decoded.items.single().sourceId)

        val oversized =
            PluginSearchPage(
                items =
                    List(PluginContentLimits.MAX_SEARCH_ITEMS + 1) {
                        ComicSummary("comic-$it", "Comic")
                    }
            )
        assertTrue(
            runCatching {
                    PluginContentCodec.searchPage(
                        PluginContentCodec.json.encodeToJsonElement(oversized),
                        "io.example.source",
                    )
                }
                .exceptionOrNull() is PluginContentValidationException
        )
    }

    @Test
    fun `search item source IDs must be unique`() {
        val duplicateItems =
            PluginSearchPage(
                items =
                    listOf(
                        ComicSummary("comic-1", "First"),
                        ComicSummary("comic-1", "Duplicate"),
                    )
            )

        assertTrue(
            runCatching {
                    PluginContentCodec.searchPage(
                        PluginContentCodec.json.encodeToJsonElement(duplicateItems),
                        "io.example.source",
                    )
                }
                .exceptionOrNull() is PluginContentValidationException
        )
    }

    @Test
    fun `browse pages share content identity validation`() {
        val duplicateItems =
            PluginSearchPage(
                items =
                    listOf(
                        ComicSummary("comic-1", "First"),
                        ComicSummary("comic-1", "Duplicate"),
                    )
            )

        assertTrue(
            runCatching {
                    PluginContentCodec.browsePage(
                        PluginContentCodec.json.encodeToJsonElement(duplicateItems),
                        "io.example.source",
                    )
                }
                .exceptionOrNull() is PluginContentValidationException
        )
    }

    @Test
    fun `browse requests encode feed cursor limit and filters`() {
        val encoded =
            PluginContentCodec.encode(
                    PluginBrowseRequest(
                        feedId = "rank",
                        cursor = "21",
                        limit = 21,
                        filters = mapOf("period" to "week"),
                    )
                )
                .jsonObject

        assertEquals(JsonPrimitive("rank"), encoded["feedId"])
        assertEquals(JsonPrimitive("21"), encoded["cursor"])
        assertEquals(JsonPrimitive(21), encoded["limit"])
        assertEquals(JsonPrimitive("week"), encoded["filters"]?.jsonObject?.get("period"))
    }

    @Test
    fun `page indexes must be contiguous and URLs must be network URLs`() {
        val response =
            PluginPagesResponse(
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
                }
                .exceptionOrNull() is PluginContentValidationException
        )
    }

    @Test
    fun `page ids must be nonblank and unique`() {
        val invalidPageIds = listOf(listOf(" "), listOf("page-1", "page-1"))

        invalidPageIds.forEach { pageIds ->
            val response =
                PluginPagesResponse(
                    sourceId = "comic-1",
                    chapterId = "chapter-1",
                    pages =
                        pageIds.mapIndexed { index, pageId ->
                            PageDescriptor(
                                pageId = pageId,
                                index = index,
                                url = "https://example.com/page-$index.jpg",
                            )
                        },
                )
            assertTrue(
                runCatching {
                        PluginContentCodec.pages(
                            PluginContentCodec.json.encodeToJsonElement(response),
                            "io.example.source",
                        )
                    }
                    .exceptionOrNull() is PluginContentValidationException
            )
        }
    }

    @Test
    fun `image headers must be valid OkHttp ASCII headers`() {
        val response =
            PluginPagesResponse(
                sourceId = "comic-1",
                chapterId = "chapter-1",
                pages =
                    listOf(
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
                }
                .exceptionOrNull() is PluginContentValidationException
        )
    }
}
