package com.exio.inkleaf.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.data.BookmarkRepository
import com.exio.inkleaf.data.BookmarkResolution
import com.exio.inkleaf.data.db.BookmarkEntity
import com.exio.inkleaf.data.db.BookmarkWithComic
import com.exio.inkleaf.plugin.OnlinePageBookmark
import com.exio.inkleaf.plugin.PluginContentCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

internal sealed interface BookmarkThumbnailState {
    data object Loading : BookmarkThumbnailState

    data class Ready(val image: ImageBitmap) : BookmarkThumbnailState

    data object Unavailable : BookmarkThumbnailState
}

internal sealed interface SavedEvent {
    data class BookmarkRemoved(val bookmark: BookmarkEntity) : SavedEvent

    data class OnlineBookmarkRemoved(val bookmark: OnlinePageBookmark) : SavedEvent

    data class Message(val text: String) : SavedEvent
}

class SavedViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = BookmarkRepository(app)
    private val onlineRepository =
        (app as com.exio.inkleaf.InkleafApplication).onlineContentRepository
    private val hiddenBookmarkIds = MutableStateFlow<Set<Long>>(emptySet())
    internal val thumbnailStates = mutableStateMapOf<Long, BookmarkThumbnailState>()
    private val repositoryBookmarks =
        repository
            .observeAll()
            .onEach(::reconcileRemovedBookmarks)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)
    private val eventChannel = Channel<SavedEvent>(Channel.BUFFERED)
    private var onlineRefreshJob: Job? = null

    internal var onlineBookmarks by mutableStateOf<List<OnlineSavedBookmarkUi>?>(null)
        private set
    internal var onlineFavorites by mutableStateOf<List<OnlineSavedFavoriteUi>?>(null)
        private set

    val bookmarks: StateFlow<List<BookmarkWithComic>?> =
        combine(
                repositoryBookmarks,
                hiddenBookmarkIds,
            ) { storedBookmarks, hiddenIds ->
                storedBookmarks?.filterNot { it.bookmark.id in hiddenIds }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    internal val events = eventChannel.receiveAsFlow()

    init {
        refreshOnlineRecords()
    }

    private fun reconcileRemovedBookmarks(storedBookmarks: List<BookmarkWithComic>) {
        val storedIds = storedBookmarks.mapTo(mutableSetOf()) { it.bookmark.id }
        hiddenBookmarkIds.update { hiddenIds -> hiddenIds.intersect(storedIds) }
        thumbnailStates.keys.filterNot(storedIds::contains).forEach(thumbnailStates::remove)
    }

    fun loadThumbnail(bookmark: BookmarkEntity) {
        if (thumbnailStates.containsKey(bookmark.id)) return
        thumbnailStates[bookmark.id] = BookmarkThumbnailState.Loading

        viewModelScope.launch {
            thumbnailStates[bookmark.id] =
                try {
                    repository.loadThumbnail(bookmark)?.let(BookmarkThumbnailState::Ready)
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
                    SavedEvent.Message(error.message?.let { "移除书签失败：$it" } ?: "移除书签失败")
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
                    SavedEvent.Message(error.message?.let { "恢复书签失败：$it" } ?: "恢复书签失败")
                )
            }
        }
    }

    fun resolve(bookmark: BookmarkEntity, onResolved: (BookmarkResolution) -> Unit) {
        viewModelScope.launch {
            val resolution =
                try {
                    repository.resolve(bookmark)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    BookmarkResolution.Unavailable(error.message ?: "无法打开这个书签")
                }
            onResolved(resolution)
        }
    }

    fun showMessage(message: String) {
        viewModelScope.launch {
            eventChannel.send(SavedEvent.Message(message))
        }
    }

    fun refreshOnlineRecords() {
        onlineRefreshJob?.cancel()
        onlineRefreshJob =
            viewModelScope.launch {
                try {
                    val (bookmarks, favorites) =
                        withContext(Dispatchers.IO) {
                            val records = onlineRepository.list()
                            val bookmarkItems =
                                records.flatMap { record ->
                                    record.pageBookmarks.map { bookmark ->
                                        val location = bookmark.location
                                        OnlineSavedBookmarkUi(
                                            key =
                                                "online-bookmark:" +
                                                    PluginContentCodec.json.encodeToString(
                                                        location.identity
                                                    ),
                                            title = record.titleSnapshot(),
                                            chapterTitle =
                                                bookmark.chapterTitleSnapshot
                                                    ?: record.chapterTitle(location),
                                            pageIndex = location.pageIndex,
                                            addedAtMs = bookmark.addedAtMs,
                                            cover = record.detail?.cover,
                                            availability = record.availability,
                                            target = record.readerTarget(location),
                                            stored = bookmark,
                                        )
                                    }
                                }
                                .sortedByDescending { it.addedAtMs }
                            val favoriteItems =
                                records.flatMap { record ->
                                    record.pageFavorites.map { favorite ->
                                        val location = favorite.location
                                        OnlineSavedFavoriteUi(
                                            key =
                                                "online-favorite:" +
                                                    PluginContentCodec.json.encodeToString(
                                                        location.identity
                                                    ),
                                            title = record.titleSnapshot(),
                                            chapterTitle =
                                                favorite.chapterTitleSnapshot
                                                    ?: record.chapterTitle(location),
                                            pageIndex = location.pageIndex,
                                            addedAtMs = favorite.addedAtMs,
                                            snapshotFile =
                                                runCatching {
                                                    onlineRepository.resolvePageFavoriteSnapshot(
                                                        favorite
                                                    )
                                                }.getOrNull()?.takeIf { it.isFile },
                                            availability = record.availability,
                                            target = record.readerTarget(location),
                                            stored = favorite,
                                        )
                                    }
                                }
                                .sortedByDescending { it.addedAtMs }
                            bookmarkItems to favoriteItems
                        }
                    onlineBookmarks = bookmarks
                    onlineFavorites = favorites
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    onlineBookmarks = emptyList()
                    onlineFavorites = emptyList()
                    eventChannel.send(
                        SavedEvent.Message(
                            error.message?.let { "加载在线保存记录失败：$it" }
                                ?: "加载在线保存记录失败"
                        )
                    )
                }
            }
    }

    internal fun removeOnlineBookmark(item: OnlineSavedBookmarkUi) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(onlineRepository.removePageBookmark(item.stored.location.identity))
                }
                refreshOnlineRecords()
                eventChannel.send(SavedEvent.OnlineBookmarkRemoved(item.stored))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                eventChannel.send(
                    SavedEvent.Message(error.message?.let { "移除书签失败：$it" } ?: "移除书签失败")
                )
            }
        }
    }

    fun restoreOnlineBookmark(bookmark: OnlinePageBookmark) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    onlineRepository.addPageBookmark(
                        bookmark.location,
                        bookmark.chapterTitleSnapshot,
                    )
                }
                refreshOnlineRecords()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                eventChannel.send(
                    SavedEvent.Message(error.message?.let { "恢复书签失败：$it" } ?: "恢复书签失败")
                )
            }
        }
    }

    internal fun removeOnlineFavorite(item: OnlineSavedFavoriteUi) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(onlineRepository.removePageFavorite(item.stored.location.identity))
                }
                refreshOnlineRecords()
                eventChannel.send(SavedEvent.Message("已取消收藏"))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                eventChannel.send(
                    SavedEvent.Message(error.message?.let { "取消收藏失败：$it" } ?: "取消收藏失败")
                )
            }
        }
    }
}
