package com.exio.inkleaf.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.data.AddFolderOutcome
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.db.FolderWithCount
import com.exio.inkleaf.data.db.LibraryFolderEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 目录管理 sheet 的状态持有者（与 SettingsScreen 同生命周期） */
class FoldersViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ComicRepository(app)

    /**
     * 目录列表（含每个目录的漫画数）；comics 表一变计数自动更新。
     * null = 数据库尚未发射首批数据；空列表 = 确实没有目录。
     * 初始值不能用 emptyList()，否则 sheet 打开瞬间会闪一帧空状态文案
     *
     * Eagerly：ViewModel 随设置页一创建就订阅，让 Room 查询在用户点开
     * sheet 之前就跑完。否则（Lazily）首次订阅发生在打开 sheet 那一刻，
     * 数据 null→列表 的到达会驱动 Crossfade + animateContentSize 把 sheet
     * 从 0 高度撑到满列表，与入场滑入动画抢帧 → 第一次打开明显卡顿。
     */
    val folders: StateFlow<List<FolderWithCount>?> = repo.observeFolders()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 一次性提示消息（Snackbar 展示后调 consumeMessage 清空） */
    var message by mutableStateOf<String?>(null)
        private set

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            try {
                // 编排（插库 → 首扫 → 补封面）在 Repository，这里只映射消息；
                // 添加成功不弹提示——列表里新行和计数本身就是反馈
                if (repo.addFolderAndSync(uri) is AddFolderOutcome.Duplicate) {
                    message = "该目录已在漫画库中"
                }
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
