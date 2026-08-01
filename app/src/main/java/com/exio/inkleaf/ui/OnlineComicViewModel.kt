package com.exio.inkleaf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.plugin.ChapterSummary
import com.exio.inkleaf.plugin.ComicDetail
import com.exio.inkleaf.plugin.ComicSummary
import com.exio.inkleaf.plugin.OnlineAvailability
import com.exio.inkleaf.plugin.OnlineComicRecord
import com.exio.inkleaf.plugin.OnlineContentRepository
import com.exio.inkleaf.plugin.OnlineReadingPosition
import com.exio.inkleaf.plugin.OnlineUserReference
import com.exio.inkleaf.plugin.PluginChapterRequest
import com.exio.inkleaf.plugin.PluginContentCodec
import com.exio.inkleaf.plugin.PluginDetailRequest
import com.exio.inkleaf.plugin.PluginRpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import java.nio.charset.StandardCharsets

internal data class OnlineComicUiState(
    val sourceName: String,
    val detail: ComicDetail? = null,
    val chapters: List<ChapterSummary> = emptyList(),
    val hasLoadedChapters: Boolean = false,
    val isInitialLoading: Boolean = detail == null,
    val isRefreshing: Boolean = false,
    val isStale: Boolean = false,
    val isBookmarked: Boolean = false,
    val position: OnlineReadingPosition? = null,
    val errorMessage: String? = null,
)

internal suspend fun observeOnlineComicPosition(
    repository: OnlineContentRepository,
    pluginId: String,
    sourceId: String,
    onPosition: (OnlineReadingPosition?) -> Unit,
) {
    repository.revision.collectLatest {
        onPosition(withContext(Dispatchers.IO) { repository.get(pluginId, sourceId)?.position })
    }
}

internal class OnlineComicViewModel(
    app: Application,
    private val pluginId: String,
    private val sourceId: String,
    opaqueContextJson: String?,
    summaryJson: String?,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) : AndroidViewModel(app) {
    private val application = getApplication<InkleafApplication>()
    private val routeOpaqueContext = opaqueContextJson?.parseJsonElementOrNull()
    private val seed = summaryJson?.parseRouteSeedOrNull()
    private val bookmarkMutationMutex = Mutex()
    private var bookmarkMutationVersion = 0
    private var persistedBookmarkState = false
    private var refreshJob: Job? = null
    private var refreshGeneration = 0

    private val _state =
        MutableStateFlow(
            OnlineComicUiState(
                sourceName = pluginId,
                detail = seed?.toDetail(sourceId),
                isInitialLoading = seed == null,
                isRefreshing = seed != null,
            )
        )
    val state: StateFlow<OnlineComicUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observeOnlineComicPosition(
                repository = application.onlineContentRepository,
                pluginId = pluginId,
                sourceId = sourceId,
            ) { position ->
                _state.value = _state.value.copy(position = position)
            }
        }
        refresh(force = false)
    }

    fun retry() = refresh(force = true)

    fun chapterContext(chapter: ChapterSummary): JsonElement? =
        chapter.opaqueContext ?: _state.value.detail?.opaqueContext ?: routeOpaqueContext

    fun toggleBookmark() {
        val previousState = _state.value.isBookmarked
        val nextState = !previousState
        val mutationVersion = ++bookmarkMutationVersion
        _state.value = _state.value.copy(isBookmarked = nextState)
        viewModelScope.launch {
            try {
                bookmarkMutationMutex.withLock {
                    if (mutationVersion != bookmarkMutationVersion) return@withLock
                    withContext(Dispatchers.IO) {
                        application.onlineContentRepository.setReference(
                            pluginId = pluginId,
                            sourceId = sourceId,
                            reference = OnlineUserReference.BOOKMARK,
                            present = nextState,
                        )
                    }
                    persistedBookmarkState = nextState
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (mutationVersion == bookmarkMutationVersion) {
                    _state.value =
                        _state.value.copy(
                            isBookmarked = persistedBookmarkState,
                            errorMessage =
                                error.message?.let { "追漫状态保存失败：$it" }
                                    ?: "追漫状态保存失败",
                        )
                }
            }
        }
    }

    private fun refresh(force: Boolean) {
        val generation = ++refreshGeneration
        val bookmarkVersionAtStart = bookmarkMutationVersion
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(errorMessage = null)
            val startup =
                withContext(Dispatchers.IO) {
                    val installed = application.pluginManager.installed()
                    val plugin = installed.firstOrNull { it.state.pluginId == pluginId }
                    StartupSnapshot(
                        sourceName = plugin?.manifest?.name ?: pluginId,
                        pluginVersion = plugin?.state?.activeVersion,
                        record = application.onlineContentRepository.get(pluginId, sourceId),
                    )
                }
            publishSnapshot(startup, bookmarkVersionAtStart)

            val cached = startup.record
            if (!force && cached.isFresh(startup.pluginVersion, clockMs(), ttlMs)) {
                _state.value =
                    _state.value.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        isStale = false,
                    )
                return@launch
            }

            _state.value =
                _state.value.copy(
                    isInitialLoading = _state.value.detail == null,
                    isRefreshing = _state.value.detail != null,
                    isStale =
                        cached?.detail != null ||
                            cached?.chapters?.isNotEmpty() == true ||
                            cached?.chaptersFetchedAtMs?.let { it > 0L } == true,
                )
            try {
                val loadedDetail =
                    application.pluginCatalog.detail(
                        pluginId,
                        PluginDetailRequest(sourceId, routeOpaqueContext),
                    )
                _state.value =
                    _state.value.copy(
                        detail = loadedDetail,
                        isInitialLoading = false,
                        isRefreshing = true,
                    )
                persistDetail(loadedDetail, startup.pluginVersion)

                val loadedChapters =
                    application.pluginCatalog.chapters(
                        pluginId,
                        PluginChapterRequest(
                            sourceId,
                            loadedDetail.opaqueContext ?: routeOpaqueContext,
                        ),
                    )
                _state.value =
                    _state.value.copy(
                        chapters = loadedChapters.chapters,
                        hasLoadedChapters = true,
                        isStale = false,
                    )
                persistChapters(loadedChapters, startup.pluginVersion)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                markUnavailable(error)
                _state.value =
                    _state.value.copy(
                        errorMessage = error.message ?: "加载漫画详情失败",
                    )
            } finally {
                if (generation == refreshGeneration) {
                    _state.value =
                        _state.value.copy(
                            isInitialLoading = false,
                            isRefreshing = false,
                        )
                }
            }
        }
    }

    private fun publishSnapshot(startup: StartupSnapshot, bookmarkVersionAtStart: Int) {
        val record = startup.record
        val mayPublishBookmark = bookmarkMutationVersion == bookmarkVersionAtStart
        val bookmarked = record?.references?.contains(OnlineUserReference.BOOKMARK) == true
        if (mayPublishBookmark) persistedBookmarkState = bookmarked
        _state.value =
            _state.value.copy(
                sourceName = startup.sourceName,
                detail = record?.detail ?: _state.value.detail,
                chapters = record?.chapters ?: _state.value.chapters,
                hasLoadedChapters = record?.chaptersFetchedAtMs?.let { it > 0L } == true,
                isInitialLoading = record?.detail == null && _state.value.detail == null,
                isBookmarked = if (mayPublishBookmark) bookmarked else _state.value.isBookmarked,
                position = record?.position ?: _state.value.position,
            )
    }

    private suspend fun persistDetail(detail: ComicDetail, pluginVersion: String?) {
        runCatchingStorage {
            application.onlineContentRepository.recordDetail(pluginId, detail, pluginVersion)
        }
    }

    private suspend fun persistChapters(
        response: com.exio.inkleaf.plugin.PluginChaptersResponse,
        pluginVersion: String?,
    ) {
        runCatchingStorage {
            application.onlineContentRepository.recordChapters(pluginId, response, pluginVersion)
        }
    }

    private suspend fun markUnavailable(error: Exception) {
        runCatchingStorage {
            application.onlineContentRepository.setAvailability(
                pluginId,
                sourceId,
                error.toOnlineAvailability(),
            )
        }
    }

    private suspend fun <T> runCatchingStorage(block: () -> T): T? =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private data class StartupSnapshot(
        val sourceName: String,
        val pluginVersion: String?,
        val record: OnlineComicRecord?,
    )

    private companion object {
        const val DEFAULT_TTL_MS = 15L * 60L * 1000L
    }
}

internal fun OnlineComicRecord?.isFresh(
    pluginVersion: String?,
    nowMs: Long,
    ttlMs: Long,
): Boolean {
    val record = this ?: return false
    if (record.availability != OnlineAvailability.AVAILABLE) return false
    if (
        record.detail == null ||
            record.detailFetchedAtMs <= 0L ||
            record.chaptersFetchedAtMs <= 0L
    ) {
        return false
    }
    return record.detailPluginVersion == pluginVersion &&
        record.chaptersPluginVersion == pluginVersion &&
        nowMs - record.detailFetchedAtMs in 0 until ttlMs &&
        nowMs - record.chaptersFetchedAtMs in 0 until ttlMs
}

private fun String.parseJsonElementOrNull(): JsonElement? =
    runCatching { PluginContentCodec.json.parseToJsonElement(this) }.getOrNull()

private fun String.parseRouteSeedOrNull(): OnlineComicRouteSeed? =
    runCatching { PluginContentCodec.json.decodeFromString<OnlineComicRouteSeed>(this) }.getOrNull()

@Serializable
internal data class OnlineComicRouteSeed(
    val title: String,
    val subtitle: String? = null,
    val coverUrl: String? = null,
    val coverReferer: String? = null,
    val tags: List<String> = emptyList(),
) {
    fun toDetail(sourceId: String) =
        ComicDetail(
            sourceId = sourceId,
            title = title,
            subtitle = subtitle,
            cover = coverUrl?.let { com.exio.inkleaf.plugin.PageImage(it, referer = coverReferer) },
            tags = tags,
        )
}

internal fun ComicSummary.toRouteSeed() =
    OnlineComicRouteSeed(
        title = title.takeUtf8(MAX_ROUTE_TITLE_BYTES),
        subtitle = subtitle?.takeUtf8(MAX_ROUTE_SUBTITLE_BYTES),
        coverUrl = cover?.takeIf { it.headers.isEmpty() }?.url?.takeUtf8(MAX_ROUTE_URL_BYTES),
        coverReferer =
            cover?.takeIf { it.headers.isEmpty() }?.referer?.takeUtf8(MAX_ROUTE_REFERER_BYTES),
        tags =
            tags.take(MAX_ROUTE_SEED_TAGS).map { it.takeUtf8(MAX_ROUTE_TAG_BYTES) }.filter {
                it.isNotBlank()
            },
    )

private fun String.takeUtf8(maxBytes: Int): String {
    if (toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return this
    val result = StringBuilder()
    var index = 0
    var bytes = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val chars = String(Character.toChars(codePoint))
        val nextBytes = chars.toByteArray(StandardCharsets.UTF_8).size
        if (bytes + nextBytes > maxBytes) break
        result.append(chars)
        bytes += nextBytes
        index += Character.charCount(codePoint)
    }
    return result.toString()
}

private const val MAX_ROUTE_SEED_TAGS = 8
private const val MAX_ROUTE_TITLE_BYTES = 1024
private const val MAX_ROUTE_SUBTITLE_BYTES = 1024
private const val MAX_ROUTE_URL_BYTES = 4096
private const val MAX_ROUTE_REFERER_BYTES = 2048
private const val MAX_ROUTE_TAG_BYTES = 256

internal fun Throwable.toOnlineAvailability(): OnlineAvailability {
    val code = (this as? PluginRpcException)?.error?.code
    return when (code) {
        com.exio.inkleaf.plugin.PluginErrorCode.AUTH_REQUIRED -> OnlineAvailability.AUTH_REQUIRED
        com.exio.inkleaf.plugin.PluginErrorCode.PLUGIN_DISABLED ->
            OnlineAvailability.PLUGIN_DISABLED
        com.exio.inkleaf.plugin.PluginErrorCode.NOT_FOUND -> OnlineAvailability.CONTENT_MISSING
        else -> OnlineAvailability.TEMPORARY_ERROR
    }
}
