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
import com.exio.inkleaf.plugin.PluginActionDescriptor
import com.exio.inkleaf.plugin.PluginActionKind
import com.exio.inkleaf.plugin.PluginActionRequest
import com.exio.inkleaf.plugin.PluginActionScope
import com.exio.inkleaf.plugin.PluginErrorCode
import com.exio.inkleaf.plugin.PluginFilterOption
import com.exio.inkleaf.plugin.PluginChapterRequest
import com.exio.inkleaf.plugin.PluginContentCodec
import com.exio.inkleaf.plugin.PluginDetailRequest
import com.exio.inkleaf.plugin.PluginRpcException
import com.exio.inkleaf.plugin.actionsInScope
import java.nio.charset.StandardCharsets
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class OnlineComicUiState(
    val sourceName: String,
    val detail: ComicDetail? = null,
    val chapters: List<ChapterSummary> = emptyList(),
    val hasLoadedChapters: Boolean = false,
    val isInitialLoading: Boolean = detail == null,
    val isRefreshing: Boolean = false,
    val isStale: Boolean = false,
    val isBookmarked: Boolean = false,
    val comicActions: List<PluginActionDescriptor> = emptyList(),
    val isComicActionRunning: Boolean = false,
    val position: OnlineReadingPosition? = null,
    val errorMessage: String? = null,
)

/** Transient result of a comic action, shown once and then consumed by the screen. */
internal data class OnlineComicActionFeedback(val message: String, val isError: Boolean)

internal enum class FavoriteSheetAction {
    ADD,
    REMOVE,
}

/** The account-favorite sheet: the add action's folder choices and the remove action. */
internal data class OnlineFavoriteSheetState(
    val addAction: PluginActionDescriptor? = null,
    val removeAction: PluginActionDescriptor? = null,
    val options: List<PluginFilterOption> = emptyList(),
    val selectedOptionId: String? = null,
    val isLoading: Boolean = false,
    val runningAction: FavoriteSheetAction? = null,
    val errorMessage: String? = null,
    val authRequired: Boolean = false,
    val canRetryOptions: Boolean = false,
) {
    val needsOptions: Boolean
        get() = addAction?.optionTitle != null

    val isRunning: Boolean
        get() = runningAction != null
}

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
    private var comicActionJob: Job? = null
    private var favoriteJob: Job? = null
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

    private val _actionFeedback = MutableStateFlow<OnlineComicActionFeedback?>(null)
    val actionFeedback: StateFlow<OnlineComicActionFeedback?> = _actionFeedback.asStateFlow()

    private val _favoriteSheet = MutableStateFlow<OnlineFavoriteSheetState?>(null)
    val favoriteSheet: StateFlow<OnlineFavoriteSheetState?> = _favoriteSheet.asStateFlow()

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
        loadComicActions()
    }

    fun retry() = refresh(force = true)

    fun consumeActionFeedback() {
        _actionFeedback.value = null
    }

    /**
     * Runs a comic-scoped source action against this comic. The action decides what to do with the
     * id; the host only supplies it and reports the outcome the plugin returned.
     */
    fun invokeComicAction(action: PluginActionDescriptor) {
        if (comicActionJob?.isActive == true) return
        comicActionJob = viewModelScope.launch {
            _state.value = _state.value.copy(isComicActionRunning = true)
            try {
                val result =
                    application.pluginCatalog.invokeAction(
                        pluginId,
                        PluginActionRequest(
                            actionId = action.id,
                            input = buildJsonObject { put("sourceId", sourceId) },
                        ),
                    )
                _actionFeedback.value =
                    OnlineComicActionFeedback(
                        message = sourceActionMessage(result, action.title),
                        isError = false,
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _actionFeedback.value =
                    OnlineComicActionFeedback(
                        message = error.toContentLoadError().message,
                        isError = true,
                    )
            } finally {
                _state.value = _state.value.copy(isComicActionRunning = false)
            }
        }
    }

    /**
     * Opens the account-favorite sheet. The add action may need runtime choices (folder lists),
     * which are fetched before the sheet can offer a primary action.
     */
    fun openFavoriteSheet() {
        if (_favoriteSheet.value != null) return
        val favoriteActions =
            _state.value.comicActions.filter { it.kind == PluginActionKind.FAVORITE }
        val addAction = favoriteActions.firstOrNull { !it.destructive }
        val removeAction = favoriteActions.firstOrNull { it.destructive }
        if (addAction == null && removeAction == null) return

        _favoriteSheet.value =
            OnlineFavoriteSheetState(
                addAction = addAction,
                removeAction = removeAction,
                isLoading = addAction?.optionTitle != null,
            )
        if (addAction?.optionTitle != null) loadFavoriteOptions(addAction)
    }

    fun dismissFavoriteSheet() {
        if (_favoriteSheet.value?.isRunning == true) return
        favoriteJob?.cancel()
        favoriteJob = null
        _favoriteSheet.value = null
    }

    fun selectFavoriteOption(optionId: String) {
        val sheet = _favoriteSheet.value ?: return
        if (sheet.options.none { it.id == optionId }) return
        _favoriteSheet.value = sheet.copy(selectedOptionId = optionId)
    }

    fun retryFavoriteOptions() {
        val action = _favoriteSheet.value?.addAction ?: return
        if (action.optionTitle == null) return
        loadFavoriteOptions(action)
    }

    fun addAccountFavorite() {
        val sheet = _favoriteSheet.value ?: return
        val action = sheet.addAction ?: return
        if (sheet.isRunning) return
        val optionId = sheet.selectedOptionId
        favoriteJob?.cancel()
        favoriteJob =
            viewModelScope.launch {
                _favoriteSheet.value =
                    _favoriteSheet.value?.copy(
                        runningAction = FavoriteSheetAction.ADD,
                        errorMessage = null,
                    )
                try {
                    val result =
                        application.pluginCatalog.invokeAction(
                            pluginId,
                            PluginActionRequest(
                                actionId = action.id,
                                input =
                                    buildJsonObject {
                                        put("sourceId", sourceId)
                                        optionId?.let {
                                            put(PluginActionDescriptor.OPTION_INPUT_ID, it)
                                        }
                                    },
                            ),
                        )
                    val folderTitle = sheet.options.firstOrNull { it.id == optionId }?.title
                    application.pluginBrowseRepository.clear(pluginId)
                    _favoriteSheet.value = null
                    _actionFeedback.value =
                        OnlineComicActionFeedback(
                            message =
                                if (folderTitle != null) {
                                    "已加入「$folderTitle」"
                                } else {
                                    sourceActionMessage(result, action.title)
                                },
                            isError = false,
                        )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    publishFavoriteFailure(error)
                }
            }
    }

    fun removeAccountFavorite() {
        val sheet = _favoriteSheet.value ?: return
        val action = sheet.removeAction ?: return
        if (sheet.isRunning) return
        favoriteJob?.cancel()
        favoriteJob =
            viewModelScope.launch {
                _favoriteSheet.value =
                    _favoriteSheet.value?.copy(
                        runningAction = FavoriteSheetAction.REMOVE,
                        errorMessage = null,
                    )
                try {
                    val result =
                        application.pluginCatalog.invokeAction(
                            pluginId,
                            PluginActionRequest(
                                actionId = action.id,
                                input = buildJsonObject { put("sourceId", sourceId) },
                            ),
                        )
                    _favoriteSheet.value = null
                    _actionFeedback.value =
                        OnlineComicActionFeedback(
                            sourceActionMessage(result, action.title),
                            isError = false,
                        )
                    application.pluginBrowseRepository.clear(pluginId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    publishFavoriteFailure(error)
                }
            }
    }

    private fun loadFavoriteOptions(action: PluginActionDescriptor) {
        favoriteJob?.cancel()
        favoriteJob =
            viewModelScope.launch {
                _favoriteSheet.value =
                    _favoriteSheet.value?.copy(
                        isLoading = true,
                        errorMessage = null,
                        authRequired = false,
                        canRetryOptions = false,
                    )
                try {
                    val result =
                        application.pluginCatalog.actionOptions(
                            pluginId,
                            PluginActionRequest(
                                actionId = action.id,
                                input = buildJsonObject { put("sourceId", sourceId) },
                            ),
                        )
                    _favoriteSheet.value =
                        _favoriteSheet.value?.copy(
                            options = result.options,
                            selectedOptionId =
                                result.selected ?: result.options.firstOrNull()?.id,
                            isLoading = false,
                        )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val authRequired =
                        (error as? PluginRpcException)?.error?.code ==
                            PluginErrorCode.AUTH_REQUIRED
                    _favoriteSheet.value =
                        _favoriteSheet.value?.copy(
                            isLoading = false,
                            errorMessage = favoriteErrorMessage(error, authRequired),
                            authRequired = authRequired,
                            canRetryOptions = !authRequired,
                        )
                }
            }
    }

    private fun publishFavoriteFailure(error: Exception) {
        val authRequired =
            (error as? PluginRpcException)?.error?.code == PluginErrorCode.AUTH_REQUIRED
        _favoriteSheet.value =
            _favoriteSheet.value?.copy(
                runningAction = null,
                isLoading = false,
                errorMessage = favoriteErrorMessage(error, authRequired),
                authRequired = authRequired,
                canRetryOptions = false,
            )
    }

    private fun favoriteErrorMessage(error: Exception, authRequired: Boolean): String =
        if (authRequired) {
            error.message?.takeIf { it.isNotBlank() } ?: error.toContentLoadError().message
        } else {
            error.toContentLoadError().message
        }

    private fun loadComicActions() {
        viewModelScope.launch {
            val actions =
                try {
                    application.pluginCatalog
                        .describe(pluginId)
                        .actionsInScope(PluginActionScope.COMIC)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A source that cannot describe itself simply offers no comic actions.
                    emptyList()
                }
            _state.value = _state.value.copy(comicActions = actions)
        }
    }

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
                            errorMessage = error.message?.let { "书架状态保存失败：$it" } ?: "书架状态保存失败",
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
                        // 分类器对未知类别保留原始文案（插件自带的往往更具体）
                        errorMessage = error.toContentLoadError().message
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
        record.detail == null || record.detailFetchedAtMs <= 0L || record.chaptersFetchedAtMs <= 0L
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
            tags
                .take(MAX_ROUTE_SEED_TAGS)
                .map { it.takeUtf8(MAX_ROUTE_TAG_BYTES) }
                .filter {
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
        com.exio.inkleaf.plugin.PluginErrorCode.NOT_FOUND -> OnlineAvailability.CONTENT_MISSING
        else -> OnlineAvailability.TEMPORARY_ERROR
    }
}
