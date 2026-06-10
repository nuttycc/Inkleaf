package com.exio.comicreader.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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

class ShelfViewModel(app: Application) : AndroidViewModel(app), DefaultLifecycleObserver {
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

    /** 上次发起扫描的时刻（elapsedRealtime：单调时钟，不受用户改系统时间影响） */
    private var lastScanAt = 0L

    init {
        // 进程级生命周期：ON_START 只在"App 从后台回到前台"时发生——
        // 页面导航、旋转屏幕、弹对话框都不影响进程状态，不会误触发。
        // 注册时 Lifecycle 会把当前状态回放给新观察者（进程此刻已是
        // STARTED），onStart 随注册立即执行一次，冷启动首扫由同一条
        // 路径覆盖——不再需要单独的 init { refresh() }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** App 回到前台（含冷启动）：自动同步书架 */
    override fun onStart(owner: LifecycleOwner) {
        // 冷却窗口：快速切出去看一眼通知再回来，不值得全量遍历目录树；
        // 真正去文件管理器增删文件的往返一般超过这个间隔
        if (SystemClock.elapsedRealtime() - lastScanAt < SCAN_COOLDOWN_MS) return
        refresh()
    }

    /** 手动/自动刷新入口；Job.isActive 一行实现"扫描中不重复扫" */
    fun refresh() {
        if (scanJob?.isActive == true) return
        lastScanAt = SystemClock.elapsedRealtime()
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

    /**
     * 观察者持有 this，而 ProcessLifecycleOwner 是进程级单例：
     * 不在这里移除，VM 会被它一直引用——内存泄漏
     */
    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }

    companion object {
        private const val SCAN_COOLDOWN_MS = 10_000L
    }
}
