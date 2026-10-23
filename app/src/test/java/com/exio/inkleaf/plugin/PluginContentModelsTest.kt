package com.exio.inkleaf.plugin

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `action scope is retained and unknown scopes fall back to source`() {
        val encoded =
            PluginContentCodec.json.encodeToJsonElement(
                PluginDescribeResponse(
                    actions =
                        listOf(
                            PluginActionDescriptor("refresh", "Refresh"),
                            PluginActionDescriptor(
                                "favorite",
                                "Favorite",
                                scope = PluginActionScope.COMIC,
                            ),
                            PluginActionDescriptor("future", "Future", scope = "futureSurface"),
                        )
                )
            )

        val decoded = PluginContentCodec.describe(encoded)
        assertEquals(listOf("refresh", "favorite", "future"), decoded.actions.map { it.id })
        assertEquals(PluginActionScope.SOURCE, decoded.actions[0].scope)
        assertEquals(PluginActionScope.COMIC, decoded.actions[1].scope)
        assertEquals(PluginActionScope.SOURCE, decoded.actions[2].scope)
        assertEquals(
            listOf("favorite"),
            decoded.actionsInScope(PluginActionScope.COMIC).map { it.id },
        )
        assertEquals(
            listOf("refresh", "future"),
            decoded.actionsInScope(PluginActionScope.SOURCE).map { it.id },
        )
    }

    @Test
    fun `radio settings require options and are retained`() {
        val retained =
            PluginContentCodec.describe(
                PluginContentCodec.json.encodeToJsonElement(
                    PluginDescribeResponse(
                        settings =
                            listOf(
                                PluginSettingDescriptor(
                                    id = "route",
                                    title = "Route",
                                    type = "radio",
                                    options =
                                        listOf(
                                            PluginFilterOption("auto", "Auto"),
                                            PluginFilterOption(
                                                "www.example.com",
                                                "www.example.com",
                                            ),
                                        ),
                                )
                            )
                    )
                )
            )
        assertEquals(listOf("route"), retained.settings.map { it.id })

        val missingOptions =
            PluginDescribeResponse(
                settings =
                    listOf(PluginSettingDescriptor(id = "route", title = "Route", type = "radio"))
            )
        assertTrue(
            runCatching {
                    PluginContentCodec.describe(
                        PluginContentCodec.json.encodeToJsonElement(missingOptions)
                    )
                }
                .exceptionOrNull() is PluginContentValidationException
        )
    }

    @Test
    fun `action picker targets require an option setting and default to absent`() {
        val encoded =
            PluginContentCodec.json.encodeToJsonElement(
                PluginDescribeResponse(
                    actions =
                        listOf(
                            PluginActionDescriptor("refresh", "Refresh"),
                            PluginActionDescriptor(
                                "refreshDomains",
                                "Refresh",
                                pickerSettingId = "route",
                            ),
                            PluginActionDescriptor("select", "Select", pickerSettingId = "choice"),
                            PluginActionDescriptor(
                                "missing",
                                "Missing",
                                pickerSettingId = "missing",
                            ),
                            PluginActionDescriptor("text", "Text", pickerSettingId = "account"),
                            PluginActionDescriptor("future", "Future", pickerSettingId = "future"),
                            PluginActionDescriptor(
                                "unsupported",
                                "Unsupported",
                                kind = "futureKind",
                            ),
                        ),
                    settings =
                        listOf(
                            PluginSettingDescriptor(
                                "route",
                                "Route",
                                type = "radio",
                                options = listOf(PluginFilterOption("auto", "Auto")),
                            ),
                            PluginSettingDescriptor(
                                "choice",
                                "Choice",
                                type = "select",
                                options = listOf(PluginFilterOption("default", "Default")),
                            ),
                            PluginSettingDescriptor("account", "Account"),
                            PluginSettingDescriptor("future", "Future", type = "futureType"),
                        ),
                )
            )

        val decoded = PluginContentCodec.describe(encoded)
        assertEquals(
            listOf("refresh", "refreshDomains", "select", "missing", "text", "future"),
            decoded.actions.map { it.id },
        )
        assertEquals(
            listOf(null, "route", "choice", null, null, null),
            decoded.actions.map { it.pickerSettingId },
        )
    }

    @Test
    fun `action section is retained and defaults to absent`() {
        val encoded =
            PluginContentCodec.json.encodeToJsonElement(
                PluginDescribeResponse(
                    actions =
                        listOf(
                            PluginActionDescriptor("login", "Login", kind = "login", section = "登录"),
                            PluginActionDescriptor("refresh", "Refresh"),
                        )
                )
            )

        val decoded = PluginContentCodec.describe(encoded)
        assertEquals("登录", decoded.actions[0].section)
        assertEquals(null, decoded.actions[1].section)
    }

    @Test
    fun `session check decodes the reported state`() {
        fun decode(json: String) =
            PluginContentCodec.sessionCheck(PluginContentCodec.json.parseToJsonElement(json))

        val signedIn = decode("""{"signedIn":true,"account":"alice"}""")
        assertTrue(signedIn.signedIn)
        assertEquals("alice", signedIn.account)
        assertFalse(decode("""{"signedIn":false}""").signedIn)
        assertFalse(decode("{}").signedIn)
        assertEquals(null, decode("{}").account)
    }

    @Test
    fun `favorite actions and feed empty messages are retained`() {
        val encoded =
            PluginContentCodec.json.encodeToJsonElement(
                PluginDescribeResponse(
                    feeds =
                        listOf(
                            PluginFeedDescriptor(
                                id = "favorites",
                                title = "Favorites",
                                emptyMessage = "Nothing saved yet",
                            )
                        ),
                    actions =
                        listOf(
                            PluginActionDescriptor(
                                "favorite",
                                "收藏",
                                kind = PluginActionKind.FAVORITE,
                                scope = PluginActionScope.COMIC,
                                optionTitle = "收藏夹",
                            ),
                            PluginActionDescriptor(
                                "removeFavorite",
                                "移出收藏",
                                scope = PluginActionScope.COMIC,
                                destructive = true,
                            ),
                        ),
                )
            )

        val decoded = PluginContentCodec.describe(encoded)
        assertEquals("Nothing saved yet", decoded.feeds.single().emptyMessage)
        assertEquals(PluginActionKind.FAVORITE, decoded.actions[0].kind)
        assertEquals("收藏夹", decoded.actions[0].optionTitle)
        assertTrue(decoded.actions[1].destructive)
    }

    @Test
    fun `browse pages retain supported dynamic filters and drop unknown types`() {
        val filters =
            listOf(
                PluginFilterDescriptor(
                    "folder",
                    "收藏夹",
                    type = "select",
                    options =
                        listOf(
                            PluginFilterOption("0", "全部"),
                            PluginFilterOption("1", "同人志"),
                        ),
                ),
                PluginFilterDescriptor("future", "Future", type = "range"),
            )
        val encoded =
            PluginContentCodec.json.encodeToJsonElement(
                PluginSearchPage(filters = filters)
            )

        val decoded = PluginContentCodec.browsePage(encoded, "io.example.source")

        assertEquals(listOf("folder"), decoded.filters.map { it.id })
        assertEquals(listOf("0", "1"), decoded.filters.single().options.map { it.id })
    }

    @Test
    fun `select dynamic filters require options`() {
        val encoded =
            PluginContentCodec.json.encodeToJsonElement(
                PluginSearchPage(
                    filters = listOf(PluginFilterDescriptor("folder", "收藏夹", type = "select"))
                )
            )

        assertTrue(
            runCatching { PluginContentCodec.browsePage(encoded, "io.example.source") }
                .exceptionOrNull() is PluginContentValidationException
        )
    }

    @Test
    fun `action options validate ids and the selected option`() {
        fun decode(json: String) =
            PluginContentCodec.actionOptions(PluginContentCodec.json.parseToJsonElement(json))

        val options =
            decode(
                """{"options":[{"id":"12","title":"A"},{"id":"34","title":"B"}],"selected":"34"}"""
            )
        assertEquals(listOf("12", "34"), options.options.map { it.id })
        assertEquals("34", options.selected)

        listOf(
                """{"options":[{"id":"12","title":"A"},{"id":"12","title":"B"}]}""",
                """{"options":[{"id":"12","title":"A"}],"selected":"99"}""",
                """{"options":[{"id":"","title":"A"}]}""",
            )
            .forEach { json ->
                assertTrue(
                    runCatching { decode(json) }.exceptionOrNull()
                        is PluginContentValidationException
                )
            }
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
    fun `page access scope is optional validated and retained`() {
        val page = PageDescriptor(index = 0, url = "https://example.com/page.jpg")
        val legacy =
            PluginContentCodec.pages(
                PluginContentCodec.json.parseToJsonElement(
                    """{"sourceId":"comic-1","chapterId":"chapter-1","pages":[{"index":0,"url":"https://example.com/page.jpg"}]}"""
                ),
                "io.example.source",
            )
        assertEquals(null, legacy.accessScope)

        val scoped =
            PluginContentCodec.pages(
                PluginContentCodec.json.encodeToJsonElement(
                    PluginPagesResponse(
                        sourceId = "comic-1",
                        chapterId = "chapter-1",
                        pages = listOf(page),
                        accessScope = "member-tier-a",
                    )
                ),
                "io.example.source",
            )
        assertEquals("member-tier-a", scoped.accessScope)

        listOf(" ", "bad\u0000scope", "a".repeat(513)).forEach { invalidScope ->
            val error =
                runCatching {
                        PluginContentCodec.pages(
                            PluginContentCodec.json.encodeToJsonElement(
                                PluginPagesResponse(
                                    sourceId = "comic-1",
                                    chapterId = "chapter-1",
                                    pages = listOf(page),
                                    accessScope = invalidScope,
                                )
                            ),
                            "io.example.source",
                        )
                    }
                    .exceptionOrNull()
            assertTrue(error is PluginContentValidationException)
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
