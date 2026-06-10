package com.exio.comicreader.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.comicreader.data.ComicRepository
import com.exio.comicreader.data.CoverAspect
import com.exio.comicreader.data.CoverCrop
import com.exio.comicreader.data.GridColumnsMode
import com.exio.comicreader.data.ShelfLayoutSettings
import com.exio.comicreader.data.ShelfSettingsRepository
import com.exio.comicreader.data.db.ComicEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 扫描状态：进行中 / 一次性提示消息（展示后清空） */
data class ScanState(
    val isScanning: Boolean = false,
    val message: String? = null,
)

class ShelfViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ComicRepository(app)
    private val settingsRepo = ShelfSettingsRepository(app)

    /** 书架列表：数据库一变自动推送 */
    val comics: StateFlow<List<ComicEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * 排版设置。initial 给默认构造的设置对象：DataStore 首次发射前
     * 网格就有合法参数可用，且默认值与"从未写入"的语义一致，不会闪变
     */
    val layoutSettings: StateFlow<ShelfLayoutSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Lazily, ShelfLayoutSettings())

    fun setColumns(value: GridColumnsMode) {
        viewModelScope.launch { settingsRepo.setColumns(value) }
    }

    fun setAspect(value: CoverAspect) {
        viewModelScope.launch { settingsRepo.setAspect(value) }
    }

    fun setCrop(value: CoverCrop) {
        viewModelScope.launch { settingsRepo.setCrop(value) }
    }

    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private var scanJob: Job? = null
    private var coverJob: Job? = null

    /** 自动刷新入口；Job.isActive 一行实现"扫描中不重复扫" */
    fun refresh() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _scanState.value = ScanState(isScanning = true)
            val result = repo.syncAllFolders()
            _scanState.value = ScanState(
                isScanning = false,
                message = if (result.failedFolders.isNotEmpty()) {
                    "无法访问目录：${result.failedFolders.joinToString("、")}，请检查或重新添加"
                } else {
                    null
                },
            )
            // 扫描完在后台补封面（独立 Job：补封面期间不阻塞下一次刷新）
            if (coverJob?.isActive != true) {
                coverJob = viewModelScope.launch { repo.backfillCovers() }
            }
        }
    }

    fun consumeMessage() {
        _scanState.value = _scanState.value.copy(message = null)
    }

    /** 手动添加单个文件（与目录扫描共存） */
    fun addComic(uri: Uri, onReady: (Long) -> Unit) {
        viewModelScope.launch {
            val comic = repo.addOrGetComic(uri)
            onReady(comic.id)
        }
    }

    fun deleteComic(comic: ComicEntity) {
        viewModelScope.launch { repo.deleteComic(comic) }
    }
}
