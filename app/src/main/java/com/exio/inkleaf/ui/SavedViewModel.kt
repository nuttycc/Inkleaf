package com.exio.inkleaf.ui

import android.app.Application
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.data.BookmarkRepository
import com.exio.inkleaf.data.BookmarkResolution
import com.exio.inkleaf.data.db.BookmarkEntity
import com.exio.inkleaf.data.db.BookmarkWithComic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface BookmarkThumbnailState {
    data object Loading : BookmarkThumbnailState

    data class Ready(val image: ImageBitmap) : BookmarkThumbnailState

    data object Unavailable : BookmarkThumbnailState
}

internal sealed interface SavedEvent {
    data class BookmarkRemoved(val bookmark: BookmarkEntity) : SavedEvent

    data class Message(val text: String) : SavedEvent
}

class SavedViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = BookmarkRepository(app)
    private val hiddenBookmarkIds = MutableStateFlow<Set<Long>>(emptySet())
    internal val thumbnailStates = mutableStateMapOf<Long, BookmarkThumbnailState>()
    private val repositoryBookmarks = repository.observeAll()
        .onEach(::reconcileRemovedBookmarks)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    private val eventChannel = Channel<SavedEvent>(Channel.BUFFERED)

    val bookmarks: StateFlow<List<BookmarkWithComic>?> = combine(
        repositoryBookmarks,
        hiddenBookmarkIds,
    ) { storedBookmarks, hiddenIds ->
        storedBookmarks?.filterNot { it.bookmark.id in hiddenIds }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    internal val events = eventChannel.receiveAsFlow()

    private fun reconcileRemovedBookmarks(storedBookmarks: List<BookmarkWithComic>) {
        val storedIds = storedBookmarks.mapTo(mutableSetOf()) { it.bookmark.id }
        hiddenBookmarkIds.update { hiddenIds -> hiddenIds.intersect(storedIds) }
        thumbnailStates.keys
            .filterNot(storedIds::contains)
            .forEach(thumbnailStates::remove)
    }

    fun loadThumbnail(bookmark: BookmarkEntity) {
        if (thumbnailStates.containsKey(bookmark.id)) return
        thumbnailStates[bookmark.id] = BookmarkThumbnailState.Loading

        viewModelScope.launch {
            thumbnailStates[bookmark.id] = try {
                repository.loadThumbnail(bookmark)
                    ?.let(BookmarkThumbnailState::Ready)
                    ?: BookmarkThumbnailState.Unavailable
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                BookmarkThumbnailState.Unavailable
            }
        }
    }

    fun remove(bookmark: BookmarkEntity) {
        if (bookmark.id in hiddenBookmarkIds.value) return
        hiddenBookmarkIds.update { it + bookmark.id }

        viewModelScope.launch {
            try {
                repository.remove(bookmark)
                eventChannel.send(SavedEvent.BookmarkRemoved(bookmark))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                hiddenBookmarkIds.update { it - bookmark.id }
                eventChannel.send(
                    SavedEvent.Message(
                        error.message?.let { "移除书签失败：$it" } ?: "移除书签失败",
                    ),
                )
            }
        }
    }

    fun restore(bookmark: BookmarkEntity) {
        viewModelScope.launch {
            try {
                repository.restore(bookmark)
                hiddenBookmarkIds.update { it - bookmark.id }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                eventChannel.send(
                    SavedEvent.Message(
                        error.message?.let { "恢复书签失败：$it" } ?: "恢复书签失败",
                    ),
                )
            }
        }
    }

    fun resolve(bookmark: BookmarkEntity, onResolved: (BookmarkResolution) -> Unit) {
        viewModelScope.launch {
            val resolution = try {
                repository.resolve(bookmark)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                BookmarkResolution.Unavailable(
                    error.message ?: "无法打开这个书签",
                )
            }
            onResolved(resolution)
        }
    }

    fun showMessage(message: String) {
        viewModelScope.launch {
            eventChannel.send(SavedEvent.Message(message))
        }
    }
}
