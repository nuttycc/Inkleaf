package com.exio.comicreader.ui

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.comicreader.data.FavoriteRepository
import com.exio.comicreader.data.db.FavoritePageEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FavoritesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = FavoriteRepository(app)

    val favorites: StateFlow<List<FavoritePageEntity>?> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    var message by mutableStateOf<String?>(null)
        private set

    fun removeFavorite(favorite: FavoritePageEntity, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                repo.remove(favorite)
                message = "已取消收藏"
                onDone()
            } catch (e: Exception) {
                message = e.message?.let { "取消收藏失败：$it" } ?: "取消收藏失败"
            }
        }
    }

    fun openSource(favorite: FavoritePageEntity, onOpen: (Long, Int) -> Unit) {
        viewModelScope.launch {
            val comic = repo.findSourceComic(favorite)
            if (comic == null) {
                message = "原书已不在书架"
            } else {
                onOpen(comic.id, favorite.pageIndex)
            }
        }
    }

    fun exportFavorite(favorite: FavoritePageEntity) {
        viewModelScope.launch {
            try {
                repo.exportToGallery(favorite)
                message = "已导出到相册 ComicReader"
            } catch (e: Exception) {
                message = e.message?.let { "导出失败：$it" } ?: "导出失败"
            }
        }
    }

    fun shareIntent(favorite: FavoritePageEntity): Intent? = repo.shareIntent(favorite)

    fun showMessage(value: String) {
        message = value
    }

    fun consumeMessage() {
        message = null
    }
}
