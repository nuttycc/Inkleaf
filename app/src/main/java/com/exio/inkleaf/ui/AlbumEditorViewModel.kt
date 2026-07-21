package com.exio.inkleaf.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.data.AlbumPageDraft
import com.exio.inkleaf.data.AlbumRepository
import com.exio.inkleaf.data.ShelfSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AlbumEditorUiState(
    val title: String = "新建图册",
    val pages: List<AlbumPageDraft> = emptyList(),
    val coverPageId: String? = null,
    val initialTitle: String = "新建图册",
    val initialPageIds: List<String> = emptyList(),
    val initialCoverPageId: String? = null,
    val isPersisted: Boolean = false,
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val isSaving: Boolean = false,
    val loadError: String? = null,
    val failedNames: List<String> = emptyList(),
    val message: String? = null,
) {
    val hasUnsavedChanges: Boolean
        get() = title != initialTitle ||
                pages.map { it.id } != initialPageIds ||
                coverPageId != initialCoverPageId
}

class AlbumEditorViewModel(
    app: Application,
    comicId: Long?,
) : AndroidViewModel(app) {
    private val repository = AlbumRepository(app)
    private val settingsRepo = ShelfSettingsRepository(app)
    private val sessionId = repository.newSessionId()
    private var currentComicId: Long? = comicId

    val lastPickedFolder: StateFlow<String?> = settingsRepo.lastPickedFolder
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _state = MutableStateFlow(
        AlbumEditorUiState(
            isLoading = comicId != null,
            isPersisted = comicId != null,
        )
    )
    val state: StateFlow<AlbumEditorUiState> = _state.asStateFlow()

    private var sessionFinalized = false

    init {
        if (comicId != null) {
            loadAlbum(comicId)
        }
    }

    fun retryLoad() {
        currentComicId?.let(::loadAlbum)
    }

    fun updateTitle(value: String) {
        _state.value = _state.value.copy(title = value)
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.isImporting || _state.value.isSaving) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isImporting = true)
            runCatchingPreservingCancellation { repository.stageUris(sessionId, uris) }
                .onSuccess(::appendImportResult)
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        isImporting = false,
                        message = error.userMessage("导入图片失败"),
                    )
                }
        }
    }

    fun importFolder(treeUri: Uri) {
        if (_state.value.isImporting || _state.value.isSaving) return
        _state.value = _state.value.copy(isImporting = true)
        rememberLastPickedFolder(treeUri)
        viewModelScope.launch {
            runCatchingPreservingCancellation { repository.stageFolder(sessionId, treeUri) }
                .onSuccess(::appendImportResult)
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        isImporting = false,
                        message = error.userMessage("读取文件夹失败"),
                    )
                }
        }
    }

    private fun rememberLastPickedFolder(uri: Uri) {
        viewModelScope.launch { settingsRepo.rememberLastPickedFolder(uri.toString()) }
    }

    fun movePage(movingPageId: String, targetPageId: String) {
        if (movingPageId == targetPageId || _state.value.isSaving) return
        val pages = _state.value.pages.toMutableList()
        val fromIndex = pages.indexOfFirst { it.id == movingPageId }
        val targetIndex = pages.indexOfFirst { it.id == targetPageId }
        if (fromIndex == -1 || targetIndex == -1) return

        val movingPage = pages.removeAt(fromIndex)
        pages.add(targetIndex, movingPage)
        _state.value = _state.value.copy(pages = pages)
    }

    fun removePage(pageId: String) {
        if (_state.value.isSaving) return
        val oldState = _state.value
        val remainingPages = oldState.pages.filterNot { it.id == pageId }
        val coverPageId = if (oldState.coverPageId == pageId) {
            remainingPages.firstOrNull()?.id
        } else {
            oldState.coverPageId
        }
        _state.value = oldState.copy(
            pages = remainingPages,
            coverPageId = coverPageId,
        )
    }

    fun restorePage(page: AlbumPageDraft, index: Int, previousCoverPageId: String?) {
        val current = _state.value
        if (current.isSaving || current.pages.any { it.id == page.id }) return
        val pages = current.pages.toMutableList().apply {
            add(index.coerceIn(0, size), page)
        }
        _state.value = current.copy(
            pages = pages,
            coverPageId = previousCoverPageId?.takeIf { coverId ->
                pages.any { it.id == coverId }
            } ?: current.coverPageId ?: pages.firstOrNull()?.id,
        )
    }

    fun setCover(pageId: String) {
        if (_state.value.pages.any { it.id == pageId }) {
            _state.value = _state.value.copy(coverPageId = pageId)
        }
    }

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        if (current.isSaving || current.isImporting) return
        if (current.title.isBlank()) {
            _state.value = current.copy(message = "请输入图册标题")
            return
        }
        if (current.pages.isEmpty()) {
            _state.value = current.copy(message = "请先添加图片")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true)
            runCatchingPreservingCancellation {
                val savedId = repository.saveAlbum(
                    comicId = currentComicId,
                    title = current.title.trim(),
                    orderedPages = current.pages,
                    coverPageId = current.coverPageId,
                )
                savedId to repository.loadAlbum(savedId)
            }.onSuccess { (savedId, snapshot) ->
                currentComicId = savedId
                val savedCoverPageId = snapshot.comic.coverPageId
                    ?: snapshot.pages.firstOrNull()?.id
                _state.value = _state.value.copy(
                    title = snapshot.comic.title,
                    pages = snapshot.pages,
                    coverPageId = savedCoverPageId,
                    initialTitle = snapshot.comic.title,
                    initialPageIds = snapshot.pages.map { it.id },
                    initialCoverPageId = savedCoverPageId,
                    isPersisted = true,
                    isSaving = false,
                )
                onSaved()
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    isSaving = false,
                    message = error.userMessage("保存图册失败"),
                )
            }
        }
    }

    fun discard(onDiscarded: () -> Unit) {
        if (_state.value.isSaving) return
        viewModelScope.launch {
            runCatchingPreservingCancellation { repository.discardSession(sessionId) }
                .onSuccess {
                    sessionFinalized = true
                    onDiscarded()
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        message = error.userMessage("清理临时图片失败，请重试"),
                    )
                }
        }
    }

    fun clearFailures() {
        _state.value = _state.value.copy(failedNames = emptyList())
    }

    fun notifyBusy() {
        val current = _state.value
        val message = when {
            current.isSaving -> "正在保存图册，请稍候"
            current.isImporting -> "正在导入图片，请稍候"
            else -> return
        }
        _state.value = current.copy(message = message)
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun loadAlbum(comicId: Long) {
        _state.value = _state.value.copy(
            isLoading = true,
            loadError = null,
            message = null,
        )
        viewModelScope.launch {
            runCatchingPreservingCancellation { repository.loadAlbum(comicId) }
                .onSuccess { snapshot ->
                    _state.value = _state.value.copy(
                        title = snapshot.comic.title,
                        pages = snapshot.pages,
                        coverPageId = snapshot.comic.coverPageId
                            ?: snapshot.pages.firstOrNull()?.id,
                        initialTitle = snapshot.comic.title,
                        initialPageIds = snapshot.pages.map { it.id },
                        initialCoverPageId = snapshot.comic.coverPageId
                            ?: snapshot.pages.firstOrNull()?.id,
                        isPersisted = true,
                        isLoading = false,
                        loadError = null,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        loadError = error.userMessage("无法打开图册"),
                    )
                }
        }
    }

    private fun appendImportResult(result: com.exio.inkleaf.data.AlbumImportResult) {
        val current = _state.value
        val pages = current.pages + result.pages
        val coverPageId = current.coverPageId ?: pages.firstOrNull()?.id
        _state.value = current.copy(
            pages = pages,
            coverPageId = coverPageId,
            isImporting = false,
            failedNames = (current.failedNames + result.failedNames).distinct(),
            message = when {
                result.pages.isEmpty() && result.failedNames.isEmpty() -> "没有找到可导入的图片"
                result.pages.isEmpty() -> "所选图片均无法导入"
                result.failedNames.isNotEmpty() ->
                    "已导入 ${result.pages.size} 张，跳过 ${result.failedNames.size} 张"

                else -> null
            },
        )
    }

    override fun onCleared() {
        if (!sessionFinalized) {
            runCatching { repository.discardSessionNow(sessionId) }
        }
    }
}

private inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

private fun Throwable.userMessage(fallback: String): String {
    return message?.takeIf { it.isNotBlank() } ?: fallback
}
