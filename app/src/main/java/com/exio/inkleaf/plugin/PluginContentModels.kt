package com.exio.inkleaf.plugin

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class PluginDescribeResponse(
    val schemaVersion: Int = 1,
    val feeds: List<PluginFeedDescriptor> = emptyList(),
    val actions: List<PluginActionDescriptor> = emptyList(),
    val filters: List<PluginFilterDescriptor> = emptyList(),
    val settings: List<PluginSettingDescriptor> = emptyList(),
)

@Serializable
data class PluginFeedDescriptor(
    val id: String,
    val title: String,
    val filters: List<PluginFilterDescriptor> = emptyList(),
)

@Serializable
data class PluginActionDescriptor(
    val id: String,
    val title: String,
    val kind: String = "action",
    val destructive: Boolean = false,
    val enabled: Boolean = true,
)

@Serializable
data class PluginFilterDescriptor(
    val id: String,
    val title: String,
    val type: String = "text",
    val options: List<PluginFilterOption> = emptyList(),
)

@Serializable data class PluginFilterOption(val id: String, val title: String)

@Serializable
data class PluginSettingDescriptor(
    val id: String,
    val title: String,
    val type: String = "text",
    val secret: Boolean = false,
    val required: Boolean = false,
    val defaultValue: String? = null,
    /** Select settings reuse the filter-option structure. */
    val options: List<PluginFilterOption> = emptyList(),
    /**
     * Optional group heading. Sources that expose many knobs would otherwise render as one long
     * flat list. Omitting it keeps the pre-1.2 behaviour, so older plugins need no changes.
     */
    val section: String? = null,
)

@Serializable
data class PluginSearchRequest(
    val query: String,
    val cursor: String? = null,
    val limit: Int = 40,
    val filters: Map<String, String> = emptyMap(),
)

@Serializable
data class PluginBrowseRequest(
    val feedId: String,
    val cursor: String? = null,
    val limit: Int = 40,
    val filters: Map<String, String> = emptyMap(),
)

@Serializable
data class PluginSearchPage(
    val items: List<ComicSummary> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class ComicSummary(
    val sourceId: String,
    val title: String,
    val subtitle: String? = null,
    val cover: PageImage? = null,
    val tags: List<String> = emptyList(),
    val opaqueContext: JsonElement? = null,
)

@Serializable
data class ComicDetail(
    val sourceId: String,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val cover: PageImage? = null,
    val tags: List<String> = emptyList(),
    val status: String? = null,
    val opaqueContext: JsonElement? = null,
)

@Serializable
data class PluginDetailRequest(val sourceId: String, val opaqueContext: JsonElement? = null)

@Serializable
data class PluginChapterRequest(val sourceId: String, val opaqueContext: JsonElement? = null)

@Serializable
data class ChapterSummary(
    val chapterId: String,
    val title: String,
    val number: Double? = null,
    val publishedAt: String? = null,
    val revision: String? = null,
    val available: Boolean = true,
    val opaqueContext: JsonElement? = null,
)

@Serializable
data class PluginChaptersResponse(
    val sourceId: String,
    val chapters: List<ChapterSummary> = emptyList(),
    val revision: String? = null,
)

@Serializable
data class PluginPagesRequest(
    val sourceId: String,
    val chapterId: String,
    val revision: String? = null,
    val opaqueContext: JsonElement? = null,
)

@Serializable
data class PluginPagesResponse(
    val sourceId: String,
    val chapterId: String,
    val revision: String? = null,
    val pages: List<PageDescriptor> = emptyList(),
)

@Serializable
data class PageDescriptor(
    val pageId: String? = null,
    val index: Int,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val opaqueContext: JsonElement? = null,
)

@Serializable
data class PageImage(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
)

@Serializable
data class PluginActionRequest(
    val actionId: String,
    val input: JsonElement = JsonObject(emptyMap()),
)

data class PluginSearchResult(
    val pluginId: String,
    val page: PluginSearchPage? = null,
    val error: PluginRpcError? = null,
)

class PluginContentValidationException(message: String) : Exception(message)

object PluginContentCodec {
    val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(value: Any): JsonElement =
        when (value) {
            is PluginDescribeResponse -> json.encodeToJsonElement(value)
            is PluginSearchRequest -> json.encodeToJsonElement(value)
            is PluginBrowseRequest -> json.encodeToJsonElement(value)
            is PluginDetailRequest -> json.encodeToJsonElement(value)
            is PluginChapterRequest -> json.encodeToJsonElement(value)
            is PluginPagesRequest -> json.encodeToJsonElement(value)
            is PluginActionRequest -> json.encodeToJsonElement(value)
            else ->
                throw IllegalArgumentException("Unsupported plugin DTO: ${value::class.java.name}")
        }

    fun describe(value: JsonElement): PluginDescribeResponse =
        normalizeDescribe(json.decodeFromJsonElement(value))

    fun searchPage(value: JsonElement, pluginId: String): PluginSearchPage =
        json.decodeFromJsonElement<PluginSearchPage>(value).also { page ->
            validateContentPage(page, pluginId, "search")
        }

    fun browsePage(value: JsonElement, pluginId: String): PluginSearchPage =
        json.decodeFromJsonElement<PluginSearchPage>(value).also { page ->
            validateContentPage(page, pluginId, "browse")
        }

    fun detail(value: JsonElement, pluginId: String): ComicDetail =
        json.decodeFromJsonElement<ComicDetail>(value).also { detail ->
            validateId(detail.sourceId, "detail.sourceId")
            validatePluginId(pluginId)
            detail.cover?.let { validateImage(it, "detail.cover") }
            validateOpaque(detail.opaqueContext)
        }

    fun chapters(value: JsonElement, pluginId: String): PluginChaptersResponse =
        json.decodeFromJsonElement<PluginChaptersResponse>(value).also { response ->
            validateId(response.sourceId, "chapters.sourceId")
            validatePluginId(pluginId)
            if (response.chapters.size > PluginContentLimits.MAX_CHAPTERS) {
                throw PluginContentValidationException("Plugin returned too many chapters")
            }
            if (response.chapters.map { it.chapterId }.toSet().size != response.chapters.size) {
                throw PluginContentValidationException("Chapter ids must be unique")
            }
            response.chapters.forEach { chapter ->
                validateId(chapter.chapterId, "chapter.chapterId")
                validateText(chapter.title, "chapter.title", 512)
                validateOpaque(chapter.opaqueContext)
            }
        }

    fun pages(value: JsonElement, pluginId: String): PluginPagesResponse =
        json.decodeFromJsonElement<PluginPagesResponse>(value).also { response ->
            validateId(response.sourceId, "pages.sourceId")
            validatePluginId(pluginId)
            validateId(response.chapterId, "pages.chapterId")
            if (response.pages.size > PluginContentLimits.MAX_PAGES) {
                throw PluginContentValidationException("Plugin returned too many pages")
            }
            val pageIds = HashSet<String>()
            response.pages.forEachIndexed { index, page ->
                if (page.index != index || page.index < 0) {
                    throw PluginContentValidationException("Page indexes must be contiguous")
                }
                page.pageId?.let { pageId ->
                    validateId(pageId, "page.pageId")
                    if (!pageIds.add(pageId)) {
                        throw PluginContentValidationException("Page ids must be unique")
                    }
                }
                validateUrl(page.url, "page.url")
                validateHeaders(page.headers, "page.headers")
                page.referer?.let { validateUrl(it, "page.referer") }
                validateOpaque(page.opaqueContext)
            }
        }

    fun actionResult(value: JsonElement): JsonElement {
        if (
            value.toString().toByteArray(StandardCharsets.UTF_8).size >
                PluginContentLimits.MAX_ACTION_RESULT_BYTES
        ) {
            throw PluginContentValidationException("Action result exceeds the host limit")
        }
        return value
    }

    private fun normalizeDescribe(value: PluginDescribeResponse): PluginDescribeResponse {
        if (value.schemaVersion != 1)
            throw PluginContentValidationException("Unsupported descriptor schema")
        if (
            value.actions.size > PluginContentLimits.MAX_DESCRIPTORS ||
                value.feeds.size > PluginContentLimits.MAX_DESCRIPTORS ||
                value.filters.size > PluginContentLimits.MAX_DESCRIPTORS ||
                value.settings.size > PluginContentLimits.MAX_DESCRIPTORS
        ) {
            throw PluginContentValidationException("Descriptor contains too many entries")
        }
        val feedIds = HashSet<String>(value.feeds.size)
        value.feeds.forEach { feed ->
            validateId(feed.id, "feed.id")
            if (!feedIds.add(feed.id)) {
                throw PluginContentValidationException("Feed ids must be unique")
            }
            validateText(feed.title, "feed.title", 256)
            if (feed.filters.size > PluginContentLimits.MAX_DESCRIPTORS) {
                throw PluginContentValidationException("Feed contains too many filters")
            }
            validateFilters(feed.filters, "feed.filter")
            feed.filters
                .filter { it.type == "select" }
                .forEach { filter ->
                    if (filter.options.isEmpty()) {
                        throw PluginContentValidationException(
                            "Select feed filters require options"
                        )
                    }
                }
        }
        value.actions.forEach { action ->
            validateId(action.id, "action.id")
            validateText(action.title, "action.title", 256)
        }
        validateFilters(value.filters, "filter")
        val settingIds = HashSet<String>(value.settings.size)
        value.settings.forEach { setting ->
            validateId(setting.id, "setting.id")
            // Values are keyed by id, so duplicates would silently overwrite one another.
            if (!settingIds.add(setting.id)) {
                throw PluginContentValidationException("Setting ids must be unique")
            }
            validateText(setting.title, "setting.title", 256)
            setting.section?.let { validateText(it, "setting.section", 256) }
            if (setting.options.size > PluginContentLimits.MAX_DESCRIPTORS) {
                throw PluginContentValidationException("Setting contains too many options")
            }
            val optionIds = HashSet<String>(setting.options.size)
            setting.options.forEach { option ->
                validateId(option.id, "setting.option.id")
                if (!optionIds.add(option.id)) {
                    throw PluginContentValidationException("Setting option ids must be unique")
                }
                validateText(option.title, "setting.option.title", 256)
            }
            if (setting.type == "select" && setting.options.isEmpty()) {
                throw PluginContentValidationException("Select settings require options")
            }
        }
        return value.copy(
            feeds =
                value.feeds.map { feed ->
                    feed.copy(
                        filters = feed.filters.filter { it.type in SUPPORTED_FEED_FILTER_TYPES }
                    )
                },
            actions = value.actions.filter { it.kind in SUPPORTED_ACTION_KINDS },
            filters = value.filters.filter { it.type in SUPPORTED_FILTER_TYPES },
            settings = value.settings.filter { it.type in SUPPORTED_SETTING_TYPES },
        )
    }

    private fun validateFilters(filters: List<PluginFilterDescriptor>, field: String) {
        val filterIds = HashSet<String>(filters.size)
        filters.forEach { filter ->
            validateId(filter.id, "$field.id")
            if (!filterIds.add(filter.id)) {
                throw PluginContentValidationException("Filter ids must be unique")
            }
            validateText(filter.title, "$field.title", 256)
            if (filter.options.size > PluginContentLimits.MAX_DESCRIPTORS) {
                throw PluginContentValidationException("Filter contains too many options")
            }
            val optionIds = HashSet<String>(filter.options.size)
            filter.options.forEach { option ->
                validateId(option.id, "$field.option.id")
                if (!optionIds.add(option.id)) {
                    throw PluginContentValidationException("Filter option ids must be unique")
                }
                validateText(option.title, "$field.option.title", 256)
            }
        }
    }

    private fun validateContentPage(page: PluginSearchPage, pluginId: String, field: String) {
        validatePluginId(pluginId)
        if (page.items.size > PluginContentLimits.MAX_SEARCH_ITEMS) {
            throw PluginContentValidationException("Plugin returned too many $field items")
        }
        val sourceIds = HashSet<String>(page.items.size)
        page.items.forEach { item ->
            validateId(item.sourceId, "$field.item.sourceId")
            if (!sourceIds.add(item.sourceId)) {
                throw PluginContentValidationException(
                    "Plugin returned duplicate $field item sourceId: ${item.sourceId}"
                )
            }
            validateText(item.title, "$field.item.title", 512)
            item.cover?.let { image -> validateImage(image, "cover") }
            validateOpaque(item.opaqueContext)
        }
        page.nextCursor?.let { validateText(it, "$field.nextCursor", 4096) }
    }

    private fun validatePluginId(pluginId: String) {
        if (!PluginIds.isValid(pluginId))
            throw PluginContentValidationException("Invalid plugin id")
    }

    private fun validateId(value: String, field: String) {
        if (value.isBlank() || value.length > 512 || value.any(Char::isISOControl)) {
            throw PluginContentValidationException("Invalid $field")
        }
    }

    private fun validateText(value: String, field: String, maxLength: Int) {
        if (value.length > maxLength || value.any(Char::isISOControl)) {
            throw PluginContentValidationException("Invalid $field")
        }
    }

    private fun validateUrl(value: String, field: String) {
        val lower = value.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://")) || value.length > 8192) {
            throw PluginContentValidationException("Invalid $field")
        }
    }

    private fun validateImage(value: PageImage, field: String) {
        validateUrl(value.url, "$field.url")
        value.referer?.let { validateUrl(it, "$field.referer") }
        validateHeaders(value.headers, "$field.headers")
    }

    private fun validateHeaders(headers: Map<String, String>, field: String) {
        if (!PluginNetworkPolicy.areValidHttpHeaders(headers)) {
            throw PluginContentValidationException("$field exceeds the host limit")
        }
    }

    private fun validateOpaque(value: JsonElement?) {
        if (
            value != null &&
                value.toString().toByteArray(StandardCharsets.UTF_8).size >
                    PluginContentLimits.MAX_OPAQUE_BYTES
        ) {
            throw PluginContentValidationException("Opaque context is too large")
        }
    }

    private val SUPPORTED_ACTION_KINDS =
        setOf("action", "login", "logout", "clearSession", "verifyCredentials")
    private val SUPPORTED_FEED_FILTER_TYPES = setOf("select")
    private val SUPPORTED_FILTER_TYPES = setOf("text", "select", "multiSelect", "boolean")
    private val SUPPORTED_SETTING_TYPES = setOf("text", "secret", "boolean", "select")
}

object PluginContentLimits {
    const val MAX_SEARCH_ITEMS = 200
    const val MAX_CHAPTERS = 5_000
    const val MAX_PAGES = 2_000
    const val MAX_DESCRIPTORS = 128
    const val MAX_OPAQUE_BYTES = 64 * 1024
    const val MAX_ACTION_RESULT_BYTES = 512 * 1024
}

/** Small source-aware facade used by discovery/search UI and manual diagnostics. */
class PluginCatalog(private val runtimeManager: PluginRuntimeManager) {
    suspend fun describe(pluginId: String): PluginDescribeResponse {
        val result = runtimeManager.describe(pluginId)
        return withContext(Dispatchers.Default) { PluginContentCodec.describe(result) }
    }

    suspend fun search(
        request: PluginSearchRequest,
        pluginIds: List<String>? = null,
    ): List<PluginSearchResult> = request.run {
        require(query.length <= 512) { "Search query is too long" }
        val boundedLimit = limit.coerceIn(1, PluginContentLimits.MAX_SEARCH_ITEMS)
        val selectedPluginIds =
            pluginIds
                ?: withContext(Dispatchers.IO) {
                    runtimeManager
                        .installedPlugins()
                        .filter {
                            !it.state.disabled &&
                                it.state.health == PluginHealth.HEALTHY &&
                                it.state.activeVersion != null
                        }
                        .map { it.state.pluginId }
                }
        coroutineScope {
            selectedPluginIds
                .distinct()
                .map { pluginId ->
                    async {
                        try {
                            val params = copy(limit = boundedLimit)
                            val result =
                                runtimeManager.invoke(
                                    pluginId,
                                    "search",
                                    PluginContentCodec.encode(params),
                                )
                            PluginSearchResult(
                                pluginId,
                                withContext(Dispatchers.Default) {
                                    PluginContentCodec.searchPage(result, pluginId)
                                },
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            val rpcError =
                                if (error is PluginRpcException) error.error
                                else
                                    PluginRpcError(
                                        PluginErrorCode.PLUGIN_PROTOCOL,
                                        error.message ?: "Search failed",
                                    )
                            PluginSearchResult(pluginId, error = rpcError)
                        }
                    }
                }
                .awaitAll()
        }
    }

    suspend fun browse(pluginId: String, request: PluginBrowseRequest): PluginSearchPage =
        request.run {
            require(
                feedId.isNotBlank() && feedId.length <= 512 && feedId.none(Char::isISOControl)
            ) {
                "Invalid feed id"
            }
            require(cursor == null || cursor.length <= 4096 && cursor.none(Char::isISOControl)) {
                "Invalid browse cursor"
            }
            require(filters.size <= PluginContentLimits.MAX_DESCRIPTORS) {
                "Too many browse filters"
            }
            require(
                filters.all { (key, value) ->
                    key.isNotBlank() &&
                        key.length <= 512 &&
                        key.none(Char::isISOControl) &&
                        value.length <= 4096 &&
                        value.none(Char::isISOControl)
                }
            ) {
                "Invalid browse filters"
            }
            val params = copy(limit = limit.coerceIn(1, PluginContentLimits.MAX_SEARCH_ITEMS))
            val result =
                runtimeManager.invoke(pluginId, "browse", PluginContentCodec.encode(params))
            withContext(Dispatchers.Default) { PluginContentCodec.browsePage(result, pluginId) }
        }

    suspend fun detail(pluginId: String, request: PluginDetailRequest): ComicDetail {
        val result = runtimeManager.invoke(pluginId, "detail", PluginContentCodec.encode(request))
        val detail =
            withContext(Dispatchers.Default) { PluginContentCodec.detail(result, pluginId) }
        if (detail.sourceId != request.sourceId) {
            throw PluginContentValidationException("Detail sourceId does not match the request")
        }
        return detail
    }

    suspend fun chapters(pluginId: String, request: PluginChapterRequest): PluginChaptersResponse {
        val result = runtimeManager.invoke(pluginId, "chapters", PluginContentCodec.encode(request))
        val chapters =
            withContext(Dispatchers.Default) { PluginContentCodec.chapters(result, pluginId) }
        if (chapters.sourceId != request.sourceId) {
            throw PluginContentValidationException("Chapters sourceId does not match the request")
        }
        return chapters
    }

    suspend fun pages(pluginId: String, request: PluginPagesRequest): PluginPagesResponse {
        val result = runtimeManager.invoke(pluginId, "pages", PluginContentCodec.encode(request))
        val pages = withContext(Dispatchers.Default) { PluginContentCodec.pages(result, pluginId) }
        if (pages.sourceId != request.sourceId || pages.chapterId != request.chapterId) {
            throw PluginContentValidationException("Pages identity does not match the request")
        }
        return pages
    }

    suspend fun invokeAction(pluginId: String, request: PluginActionRequest): JsonElement =
        PluginContentCodec.actionResult(
            runtimeManager.invoke(pluginId, "invokeAction", PluginContentCodec.encode(request))
        )
}
