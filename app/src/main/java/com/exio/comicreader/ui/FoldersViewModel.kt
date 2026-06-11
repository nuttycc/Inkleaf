package com.exio.comicreader.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.comicreader.data.ComicRepository
import com.exio.comicreader.data.db.FolderWithCount
import com.exio.comicreader.data.db.LibraryFolderEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 目录管理 sheet 的状态持有者（与 SettingsScreen 同生命周期） */
class FoldersViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ComicRepository(app)

    /** 目录列表（含每个目录的漫画数）；comics 表一变计数自动更新 */
    val folders: StateFlow<List<FolderWithCount>> = repo.observeFolders()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 一次性提示消息（Snackbar 展示后调 consumeMessage 清空） */
    var message by mutableStateOf<String?>(null)
        private set

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            try {
                val added = repo.addFolder(uri)
                if (!added) message = "该目录已在漫画库中"
            } catch (e: SecurityException) {
                // 树权限拿不到则扫描必然失败，必须明确告知
                message = "无法获得该目录的持久访问权限"
            }
        }
    }

    fun removeFolder(folder: LibraryFolderEntity) {
        viewModelScope.launch { repo.removeFolder(folder) }
    }

    fun consumeMessage() {
        message = null
    }
}
