package com.exio.inkleaf.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.data.AddComicOutcome
import com.exio.inkleaf.data.AddFolderOutcome
import com.exio.inkleaf.data.AddSeriesFolderOutcome
import com.exio.inkleaf.data.AlbumExporter
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.CoverAspect
import com.exio.inkleaf.data.CoverCrop
import com.exio.inkleaf.data.GridColumnsMode
import com.exio.inkleaf.data.GroupWriteOutcome
import com.exio.inkleaf.data.LibraryScanner
import com.exio.inkleaf.data.ScanResult
import com.exio.inkleaf.data.SeriesScanConfirmation
import com.exio.inkleaf.data.ShelfGroupFilterKind
import com.exio.inkleaf.data.ShelfGroupSelection
import com.exio.inkleaf.data.ShelfLayoutSettings
import com.exio.inkleaf.data.ShelfSettingsRepository
import com.exio.inkleaf.data.db.ComicEntity
import com.exio.inkleaf.data.db.FolderWithCount
import com.exio.inkleaf.data.db.GroupWithCount
import com.exio.inkleaf.plugin.OnlineComicRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 扫描状态：进行中（区分用户手动发起）/ 一次性提示消息（展示后清空）。 isManual 决定 UI 表现：手动刷新有下拉指示器和结果反馈，自动扫描全静默 */
data class ScanState(
    val isScanning: Boolean = false,
    val isManual: Boolean = false,
    val message: String? = null,
    val seriesConfirmations: List<SeriesScanConfirmation> = emptyList(),
)

@OptIn(FlowPreview::class)
class ShelfViewModel(app: Application) : AndroidViewModel(app), DefaultLifecycleObserver {
    private val repo = ComicRepository(app)
    private val albumExporter = AlbumExporter(app)
    private val settingsRepo = ShelfSettingsRepository(app)

    /**
     * 书架列表：数据库一变自动推送。 null = Room 尚未发射首批数据；空列表 = 书架确实为空。 不能拿 emptyList() 当初始值——那会让 UI
     * 在数据到达前先闪一帧空状态。 WhileSubscribed：阅读页盖住书架时停止收集上游，翻页进度写库不再 驱动无人观看的重查与重过滤；已缓存的列表保留，返回书架不闪加载态
     */
    val comics: StateFlow<List<ComicEntity>?> =
        combine(
                repo.observeAll(),
                settingsRepo.selectedGroup,
            ) { comics, selection ->
                filterComics(comics, selection)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val groups: StateFlow<List<GroupWithCount>?> =
        repo.observeGroups().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val folders: StateFlow<List<FolderWithCount>?> =
        repo.observeFolders().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val onlineRepo = (app as InkleafApplication).onlineContentRepository

    /** 在线追漫列表：revision 变化时重新读取文件存储。null = 尚未首次加载 */
    val onlineBookmarked: StateFlow<List<OnlineComicRecord>?> =
        onlineRepo.revision
            .debounce(300)
            .map { withContext(Dispatchers.IO) { onlineRepo.listBookmarked() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val selectedGroup: StateFlow<ShelfGroupSelection> =
        settingsRepo.selectedGroup.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ShelfGroupSelection(),
        )

    /** 排版设置。initial 给默认构造的设置对象：DataStore 首次发射前 网格就有合法参数可用，且默认值与"从未写入"的语义一致，不会闪变 */
    val layoutSettings: StateFlow<ShelfLayoutSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Lazily, ShelfLayoutSettings())

    val lastPickedFolder: StateFlow<String?> =
        settingsRepo.lastPickedFolder.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private fun rememberLastPickedFolder(uri: Uri) {
        viewModelScope.launch { settingsRepo.rememberLastPickedFolder(uri.toString()) }
    }

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
    private var albumExportJob: Job? = null

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
                    // 仓库层已保证 GROUP 选中必带 groupId（见 selectedGroup 的规范化）
                    val selectedGroupId = selection.groupId ?: return@collect
                    val selectedExists = list.any { it.group.id == selectedGroupId }
                    if (!selectedExists && !repo.groupExists(selectedGroupId)) {
                        settingsRepo.setSelectedGroup(ShelfGroupSelection())
                    }
                }
        }
    }

    /** App 回到前台（含冷启动）：自动同步书架。 不做冷却节流：扫描是每目录一次 IPC 的轻量遍历且全程静默， 而"刚去文件管理器改完文件切回来"恰恰是最该扫的时刻 */
    override fun onStart(owner: LifecycleOwner) {
        refresh()
    }

    /** 手动/自动刷新入口；Job.isActive 一行实现"扫描中不重复扫" */
    fun refresh(manual: Boolean = false) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _scanState.value = ScanState(isScanning = true, isManual = manual)
            val result = repo.syncAllFolders()
            val problems = result.failedFolders + result.warnings
            _scanState.value =
                ScanState(
                    isScanning = false,
                    isManual = manual,
                    seriesConfirmations = result.seriesConfirmations.takeIf { manual }.orEmpty(),
                    message =
                        when {
                            problems.isNotEmpty() -> problems.joinToString("；")
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

    private fun scanLimitLabel(limit: LibraryScanner.ScanLimit): String =
        when (limit) {
            LibraryScanner.ScanLimit.PDFS -> "PDF 数量"
            LibraryScanner.ScanLimit.DIRECTORIES -> "目录数量"
            LibraryScanner.ScanLimit.ENTRIES -> "文件项目数量"
            LibraryScanner.ScanLimit.DEPTH -> "目录深度"
        }

    fun consumeMessage() {
        _scanState.value = _scanState.value.copy(message = null)
    }

    /**
     * 手动添加一个或多个文件（与目录扫描共存）。顺序循环调用 addOrGetComic， syncMutex 保证去重安全；逐个计数后聚合成一条 Snackbar 文案，避免
     * 多次覆盖只剩最后一条。
     *
     * 持久 URI 权限有系统配额（API 29- 为 128，API 30+ 为 512），多选批量 导入会显著消耗。配额不足时提前提示并放弃添加——比起"添加成功但重启
     * 后无法访问"的坏体验，明确拒绝更诚实
     */
    fun addComics(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val msg =
                try {
                    val remaining = persistedUriQuotaRemaining()
                    if (uris.size > remaining) {
                        "持久权限配额不足（剩余 $remaining 个），请改用「添加漫画目录」批量导入"
                    } else {
                        val counts = IntArray(3) // [Added, AlreadyInLibrary, Restored]
                        for (uri in uris) {
                            when (repo.addOrGetComic(uri)) {
                                is AddComicOutcome.Added -> counts[0]++
                                is AddComicOutcome.AlreadyInLibrary -> counts[1]++
                                is AddComicOutcome.Restored -> counts[2]++
                            }
                        }
                        summarizeBatch(counts[0], counts[1], counts[2])
                    }
                } catch (e: SecurityException) {
                    "无法获得持久访问权限"
                }
            _scanState.value = _scanState.value.copy(message = msg)
        }
    }

    /** 持久 URI 权限剩余配额。系统上限：API 29- 为 128，API 30+ 为 512。 这里只统计已持有的读权限数量，保守估计剩余空间 */
    private fun persistedUriQuotaRemaining(): Int {
        val used =
            getApplication<Application>().contentResolver.persistedUriPermissions.count {
                it.isReadPermission
            }
        val limit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) 512 else 128
        return (limit - used).coerceAtLeast(0)
    }

    /** 把批量添加的三态计数合并成一句话，省略为零的项 */
    private fun summarizeBatch(added: Int, alreadyInLibrary: Int, restored: Int): String {
        val parts = mutableListOf<String>()
        if (added > 0) parts.add("已添加 $added 本")
        if (alreadyInLibrary > 0) parts.add("$alreadyInLibrary 本已在书架")
        if (restored > 0) parts.add("$restored 本已恢复")
        return if (parts.isEmpty()) "所选漫画已在书架中" else parts.joinToString("，")
    }

    /**
     * 从书架 FAB 菜单添加库目录。编排在 Repository（与设置页共享）， 这里只把结果映射到 Snackbar：用户主动发起的操作必须有反馈，
     * 即使"什么都没扫到"也要说一声，不能毫无动静
     */
    fun addFolder(uri: Uri) {
        rememberLastPickedFolder(uri)
        viewModelScope.launch {
            val msg =
                try {
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

    /** 添加 PDF 章节目录：把目录内 PDF 作为一本书导入。 */
    fun addSeriesFolder(uri: Uri, approvedLargeScan: Boolean = false) {
        if (scanJob?.isActive == true) return
        if (!approvedLargeScan) rememberLastPickedFolder(uri)
        scanJob = viewModelScope.launch {
            _scanState.value = ScanState(isScanning = true, isManual = true)
            val msg =
                try {
                    when (val outcome = repo.addSeriesFolderAndSync(uri, approvedLargeScan)) {
                        is AddSeriesFolderOutcome.Duplicate -> "该目录已在漫画库中"
                        is AddSeriesFolderOutcome.Empty ->
                            buildString {
                                append("所选目录中没有可导入的 PDF 文件")
                                if (outcome.inaccessibleDirectoryCount > 0) {
                                    append("；${outcome.inaccessibleDirectoryCount} 个目录无法扫描")
                                }
                                if (outcome.skippedVirtualPdfCount > 0) {
                                    append("；跳过 ${outcome.skippedVirtualPdfCount} 个虚拟 PDF")
                                }
                            }

                        is AddSeriesFolderOutcome.Added ->
                            buildString {
                                append("已导入《${outcome.comic.title}》，共 ${outcome.chaptersAdded} 章")
                                if (outcome.duplicateNameCount > 0) {
                                    append("；${outcome.duplicateNameCount} 个同名章节已用路径区分")
                                }
                                if (outcome.inaccessibleDirectoryCount > 0) {
                                    append("；跳过 ${outcome.inaccessibleDirectoryCount} 个无法扫描的目录")
                                }
                                if (outcome.skippedVirtualPdfCount > 0) {
                                    append("；跳过 ${outcome.skippedVirtualPdfCount} 个虚拟 PDF")
                                }
                            }

                        is AddSeriesFolderOutcome.NeedsConfirmation -> {
                            _scanState.value =
                                ScanState(seriesConfirmations = listOf(outcome.request))
                            return@launch
                        }

                        is AddSeriesFolderOutcome.HardLimit ->
                            "目录超过安全扫描上限（${scanLimitLabel(outcome.limit)}）"

                        is AddSeriesFolderOutcome.Overlap ->
                            "与《${outcome.comicTitle}》有 ${outcome.fileCount} 个重复 PDF，未导入"

                        is AddSeriesFolderOutcome.Incomplete ->
                            "扫描不完整：已发现 ${outcome.discoveredPdfCount} 个 PDF，但有 " +
                                "${outcome.inaccessibleDirectoryCount} 个目录无法访问，请稍后重试"
                    }
                } catch (e: SecurityException) {
                    "无法获得该目录的持久访问权限"
                } catch (e: LibraryScanner.FolderAccessException) {
                    "无法访问该目录，可能权限被撤销或目录已被删除"
                }
            _scanState.value = ScanState(message = msg)
        }
    }

    fun confirmSeriesScan() {
        val confirmations = _scanState.value.seriesConfirmations
        val request = confirmations.firstOrNull() ?: return
        val remaining = confirmations.drop(1)
        _scanState.value = ScanState(seriesConfirmations = remaining)
        request.folderId?.let { folderId ->
            if (scanJob?.isActive == true) return
            scanJob = viewModelScope.launch {
                _scanState.value = ScanState(isScanning = true, isManual = true)
                val result = repo.approveSeriesFolderExpansion(folderId)
                _scanState.value =
                    ScanState(
                        message =
                            (result.failedFolders + result.warnings).joinToString("；").ifBlank {
                                "目录同步完成"
                            },
                        seriesConfirmations = remaining,
                    )
            }
        } ?: addSeriesFolder(request.treeUri.toUri(), approvedLargeScan = true)
    }

    fun dismissSeriesScanConfirmation() {
        val confirmations = _scanState.value.seriesConfirmations
        if (confirmations.isEmpty()) return
        _scanState.value = ScanState(seriesConfirmations = confirmations.drop(1))
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _scanState.value = ScanState(message = "已取消扫描")
    }

    fun deleteComic(comic: ComicEntity) {
        viewModelScope.launch { repo.deleteComic(comic) }
    }

    fun shareAlbum(comic: ComicEntity, onReady: (Intent) -> Unit) {
        if (albumExportJob?.isActive == true) {
            showMessage("已有图册正在导出")
            return
        }
        showMessage("正在生成分享文件…")
        albumExportJob = viewModelScope.launch {
            try {
                onReady(albumExporter.createShareIntent(comic.id))
            } catch (e: Exception) {
                showMessage(e.message?.let { "生成分享文件失败：$it" } ?: "生成分享文件失败")
            }
        }
    }

    fun prepareAlbumFileName(comic: ComicEntity, onReady: (String) -> Unit) {
        viewModelScope.launch {
            try {
                onReady(albumExporter.suggestedFileName(comic.id))
            } catch (e: Exception) {
                showMessage(e.message?.let { "无法生成文件名：$it" } ?: "无法生成文件名")
            }
        }
    }

    fun exportAlbum(comicId: Long, title: String, uri: Uri) {
        if (albumExportJob?.isActive == true) {
            showMessage("已有图册正在导出")
            return
        }
        showMessage("正在保存图册…")
        albumExportJob = viewModelScope.launch {
            try {
                albumExporter.exportToUri(comicId, uri)
                showMessage("已保存《$title》")
            } catch (e: Exception) {
                showMessage(e.message?.let { "保存失败：$it" } ?: "保存失败")
            }
        }
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
                is GroupWriteOutcome.Success ->
                    showMessage(
                        groupId
                            ?.let { id ->
                                groups.value?.firstOrNull { it.group.id == id }?.group?.name
                            }
                            ?.let { "已移动到「$it」" } ?: "已设为未分组"
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
    ): List<ComicEntity> =
        when (selection.kind) {
            ShelfGroupFilterKind.ALL -> comics
            ShelfGroupFilterKind.UNGROUPED -> comics.filter { it.groupId == null }
            ShelfGroupFilterKind.GROUP -> comics.filter { it.groupId == selection.groupId }
        }

    fun showMessage(message: String) {
        _scanState.value = _scanState.value.copy(message = message)
    }

    /** 观察者持有 this，而 ProcessLifecycleOwner 是进程级单例： 不在这里移除，VM 会被它一直引用——内存泄漏 */
    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }
}
