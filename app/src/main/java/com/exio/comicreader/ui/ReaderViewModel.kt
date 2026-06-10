package com.exio.comicreader.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.comicreader.data.ComicBook
import com.exio.comicreader.data.ComicOpenException
import com.exio.comicreader.data.ComicRepository
import kotlinx.coroutines.launch

/** 阅读页状态机：打开中 / 失败 / 就绪（带恢复的起始页码） */
sealed interface ReaderUiState {
    data object Loading : ReaderUiState
    data class Error(val message: String) : ReaderUiState
    data class Ready(
        val book: ComicBook,
        val startPage: Int,
        val title: String,
    ) : ReaderUiState
}

/**
 * 阅读页的状态与资源持有者。
 *
 * 为什么引入 ViewModel：它存活于 Activity 重建（旋转屏幕、深色模式切换等
 * "配置变更"）之外。之前 ComicBook 由 Composable 持有，旋转一次就要
 * 重新复制整个 zip；现在搬进 ViewModel，旋转时 state 原封不动，
 * 界面瞬间恢复。
 */
class ReaderViewModel(
    app: Application,
    private val comicId: Long,
) : AndroidViewModel(app) {
    private val repo = ComicRepository(app)

    var state by mutableStateOf<ReaderUiState>(ReaderUiState.Loading)
        private set

    private var book: ComicBook? = null

    init {
        viewModelScope.launch {
            state = try {
                val comic = repo.getComic(comicId)
                    ?: throw ComicOpenException("书架记录不存在")
                val opened = ComicBook.open(getApplication(), Uri.parse(comic.uri))
                book = opened
                // 首次打开回填页数和封面；Room Flow 会自动刷新书架
                repo.backfillMetadata(comic, opened)
                ReaderUiState.Ready(
                    book = opened,
                    // 原文件可能被换成页数更少的版本，夹紧防止越界
                    startPage = comic.lastReadPage.coerceIn(0, opened.pageCount - 1),
                    title = comic.title,
                )
            } catch (e: ComicOpenException) {
                ReaderUiState.Error(e.message ?: "打开失败")
            }
        }
    }

    fun saveProgress(page: Int) {
        viewModelScope.launch { repo.saveProgress(comicId, page) }
    }

    /** Error 态的出口：从书架移除这条打不开的记录，完成后回调返回书架 */
    fun removeFromShelf(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.getComic(comicId)?.let { repo.deleteComic(it) }
            onDone()
        }
    }

    /**
     * ViewModel 真正销毁时调用（返回书架弹出导航栈、或 App 退出）。
     * 注意旋转屏幕不会走到这里——这正是资源不被重复释放/创建的关键。
     */
    override fun onCleared() {
        book?.close()
    }
}
