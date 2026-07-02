package com.exio.inkleaf.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.data.AddComicOutcome
import com.exio.inkleaf.data.AddFolderOutcome
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.CoverAspect
import com.exio.inkleaf.data.CoverCrop
import com.exio.inkleaf.data.GridColumnsMode
import com.exio.inkleaf.data.GroupWriteOutcome
import com.exio.inkleaf.data.ScanResult
import com.exio.inkleaf.data.ShelfGroupFilterKind
import com.exio.inkleaf.data.ShelfGroupSelection
import com.exio.inkleaf.data.ShelfLayoutSettings
import com.exio.inkleaf.data.ShelfSettingsRepository
import com.exio.inkleaf.data.db.ComicEntity
import com.exio.inkleaf.data.db.GroupWithCount
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 扫描状态：进行中（区分用户手动发起）/ 一次性提示消息（展示后清空）。
 * isManual 决定 UI 表现：手动刷新有下拉指示器和结果反馈，自动扫描全静默
 */
data class ScanState(
    val isScanning: Boolean = false,
    val isManual: Boolean = false,
    val message: String? = null,
)

class ShelfViewModel(app: Application) : AndroidViewModel(app), DefaultLifecycleObserver {
    private val repo = ComicRepository(app)
    private val settingsRepo = ShelfSettingsRepository(app)

    /**
     * 书架列表：数据库一变自动推送。
     * null = Room 尚未发射首批数据；空列表 = 书架确实为空。
     * 不能拿 emptyList() 当初始值——那会让 UI 在数据到达前先闪一帧空状态
     */
    val comics: StateFlow<List<ComicEntity>?> = combine(
        repo.observeAll(),
        settingsRepo.selectedGroup,
    ) { comics, selection ->
        filterComics(comics, selection)
    }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val groups: StateFlow<List<GroupWithCount>?> = repo.observeGroups()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedGroup: StateFlow<ShelfGroupSelection> = settingsRepo.selectedGroup
        .stateIn(viewModelScope, SharingStarted.Eagerly, ShelfGroupSelection())

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

    init {
        // 进程级生命周期：ON_START 只在"App 从后台回到前台"时发生——
        // 页面导航、旋转屏幕、弹对话框都不影响进程状态，不会误触发。
        // 注册时 Lifecycle 会把当前状态回放给新观察者（进程此刻已是
        // STARTED），onStart 随注册立即执行一次，冷启动首扫由同一条
        // 路径覆盖——不再需要单独的 init { refresh() }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // 如果当前筛选的分组被删除，回到"全部"；否则用户会看到一个
        // 永远为空、也没有入口解释原因的书架。
        viewModelScope.launch {
            combine(groups, selectedGroup) { groups, selection -> groups to selection }
                .collect { (list, selection) ->
                    if (list == null || selection.kind != ShelfGroupFilterKind.GROUP) return@collect
                    val selectedGroupId = selection.groupId
                    if (selectedGroupId == null) {
                        settingsRepo.setSelectedGroup(ShelfGroupSelection())
                        return@collect
                    }
                    val selectedExists = list.any { it.group.id == selectedGroupId }
                    if (!selectedExists && !repo.groupExists(selectedGroupId)) {
                        settingsRepo.setSelectedGroup(ShelfGroupSelection())
                    }
                }
        }
    }

    /**
     * App 回到前台（含冷启动）：自动同步书架。
     * 不做冷却节流：扫描是每目录一次 IPC 的轻量遍历且全程静默，
     * 而"刚去文件管理器改完文件切回来"恰恰是最该扫的时刻
     */
    override fun onStart(owner: LifecycleOwner) {
        refresh()
    }

    /** 手动/自动刷新入口；Job.isActive 一行实现"扫描中不重复扫" */
    fun refresh(manual: Boolean = false) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _scanState.value = ScanState(isScanning = true, isManual = manual)
            val result = repo.syncAllFolders()
            _scanState.value = ScanState(
                isScanning = false,
                message = when {
                    result.failedFolders.isNotEmpty() ->
                        "无法访问目录：${result.failedFolders.joinToString("、")}，请检查或重新添加"
                    // 只有手动刷新才汇报变化；自动扫描静默，无变化也不打扰
                    manual -> summarize(result)
                    else -> null
                },
            )
            // 扫描完在后台补封面（独立 Job：补封面期间不阻塞下一次刷新）
            if (coverJob?.isActive != true) {
                coverJob = viewModelScope.launch { repo.backfillCovers() }
            }
        }
    }

    /** 手动刷新的结果反馈；书架无变化时返回 null（安静收起） */
    private fun summarize(result: ScanResult): String? {
        val parts = buildList {
            if (result.added > 0) add("新增 ${result.added} 本")
            if (result.markedMissing > 0) add("${result.markedMissing} 本失效")
            if (result.restored > 0) add("恢复 ${result.restored} 本")
        }
        return if (parts.isEmpty()) null else parts.joinToString("，")
    }

    fun consumeMessage() {
        _scanState.value = _scanState.value.copy(message = null)
    }

    /** 手动添加单个文件（与目录扫描共存） */
    fun addComic(uri: Uri) {
        viewModelScope.launch {
            val msg = when (repo.addOrGetComic(uri)) {
                is AddComicOutcome.Added -> "已添加到书架"
                is AddComicOutcome.AlreadyInLibrary -> "这本漫画已在书架中"
                is AddComicOutcome.Restored -> "已恢复到书架"
            }
            _scanState.value = _scanState.value.copy(message = msg)
        }
    }

    /**
     * 从书架 FAB 菜单添加库目录。编排在 Repository（与设置页共享），
     * 这里只把结果映射到 Snackbar：用户主动发起的操作必须有反馈，
     * 即使"什么都没扫到"也要说一声，不能毫无动静
     */
    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            val msg = try {
                when (val outcome = repo.addFolderAndSync(uri)) {
                    is AddFolderOutcome.Duplicate -> "该目录已在漫画库中"
                    is AddFolderOutcome.Added ->
                        summarize(outcome.scan)
                            ?: if (outcome.scan.alreadyInLibrary > 0) {
                                "已添加目录，目录中的漫画已在书架"
                            } else {
                                "已添加目录，未发现漫画文件"
                            }
                }
            } catch (e: SecurityException) {
                "无法获得该目录的持久访问权限"
            }
            _scanState.value = _scanState.value.copy(message = msg)
        }
    }

    fun deleteComic(comic: ComicEntity) {
        viewModelScope.launch { repo.deleteComic(comic) }
    }

    fun selectGroup(selection: ShelfGroupSelection) {
        viewModelScope.launch { settingsRepo.setSelectedGroup(selection) }
    }

    fun createGroup(name: String) {
        viewModelScope.launch {
            when (val outcome = repo.createGroup(name)) {
                is GroupWriteOutcome.Success -> {
                    outcome.groupId?.let {
                        settingsRepo.setSelectedGroup(
                            ShelfGroupSelection(ShelfGroupFilterKind.GROUP, it)
                        )
                    }
                }

                GroupWriteOutcome.BlankName -> showMessage("分组名不能为空")
                GroupWriteOutcome.Duplicate -> showMessage("分组已存在")
                GroupWriteOutcome.Missing -> showMessage("分组不存在")
            }
        }
    }

    fun renameGroup(groupId: Long, name: String) {
        viewModelScope.launch {
            when (repo.renameGroup(groupId, name)) {
                is GroupWriteOutcome.Success -> Unit
                GroupWriteOutcome.BlankName -> showMessage("分组名不能为空")
                GroupWriteOutcome.Duplicate -> showMessage("分组已存在")
                GroupWriteOutcome.Missing -> showMessage("分组不存在")
            }
        }
    }

    fun deleteGroup(groupId: Long) {
        viewModelScope.launch {
            repo.deleteGroup(groupId)
            if (selectedGroup.value.groupId == groupId) {
                settingsRepo.setSelectedGroup(ShelfGroupSelection())
            }
        }
    }

    fun setComicGroup(comic: ComicEntity, groupId: Long?) {
        viewModelScope.launch {
            when (repo.setComicGroup(comic.id, groupId)) {
                is GroupWriteOutcome.Success -> showMessage(
                    groupId
                        ?.let { id -> groups.value?.firstOrNull { it.group.id == id }?.group?.name }
                        ?.let { "已移动到「$it」" }
                        ?: "已设为未分组"
                )

                GroupWriteOutcome.BlankName -> Unit
                GroupWriteOutcome.Duplicate -> Unit
                GroupWriteOutcome.Missing -> showMessage("分组不存在")
            }
        }
    }

    private fun filterComics(
        comics: List<ComicEntity>,
        selection: ShelfGroupSelection,
    ): List<ComicEntity> = when (selection.kind) {
        ShelfGroupFilterKind.ALL -> comics
        ShelfGroupFilterKind.UNGROUPED -> comics.filter { it.groupId == null }
        ShelfGroupFilterKind.GROUP -> comics.filter { it.groupId == selection.groupId }
    }

    private fun showMessage(message: String) {
        _scanState.value = _scanState.value.copy(message = message)
    }

    /**
     * 观察者持有 this，而 ProcessLifecycleOwner 是进程级单例：
     * 不在这里移除，VM 会被它一直引用——内存泄漏
     */
    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }
}
