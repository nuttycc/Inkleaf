package com.exio.inkleaf.ui

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.exio.inkleaf.R
import com.exio.inkleaf.data.CoverAspect
import com.exio.inkleaf.data.CoverCrop
import com.exio.inkleaf.data.GridColumnsMode
import com.exio.inkleaf.data.LibraryScanner
import com.exio.inkleaf.data.ShelfGroupFilterKind
import com.exio.inkleaf.data.ShelfGroupSelection
import com.exio.inkleaf.data.ShelfLayoutSettings
import com.exio.inkleaf.data.db.BookSourceType
import com.exio.inkleaf.data.db.ComicEntity
import com.exio.inkleaf.data.db.GroupWithCount
import kotlinx.coroutines.delay
import java.io.File

/** 书架内容区的三态：作为 Crossfade 的 key，列表内容增删不触发整区动画 */
private enum class ShelfPhase { LOADING, EMPTY, CONTENT }

internal fun buildFolderPickerInitialUri(lastPickedFolder: String?): Uri? {
    if (lastPickedFolder == null) return null

    return try {
        val treeUri = lastPickedFolder.toUri()
        if (
            treeUri.scheme != ContentResolver.SCHEME_CONTENT ||
            treeUri.authority.isNullOrBlank() ||
            !DocumentsContract.isTreeUri(treeUri)
        ) {
            return null
        }
        val documentId: String? = DocumentsContract.getTreeDocumentId(treeUri)
        if (documentId.isNullOrBlank()) return null
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            documentId,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** 首页书架：封面网格 + 目录扫描 + 排版抽屉 + 顶栏添加菜单 + 长按删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    onOpenComic: (Long) -> Unit,
    onCreateAlbum: () -> Unit,
    onEditAlbum: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShelfViewModel = viewModel(),
) {
    val context = LocalContext.current
    val comics by viewModel.comics.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val layout by viewModel.layoutSettings.collectAsStateWithLifecycle()
    val lastPickedFolder by viewModel.lastPickedFolder.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<ComicEntity?>(null) }
    var pendingAction by remember { mutableStateOf<ComicEntity?>(null) }
    var pendingGroupAssignment by remember { mutableStateOf<ComicEntity?>(null) }
    var pendingGroupDelete by remember { mutableStateOf<GroupWithCount?>(null) }
    var pendingGroupRename by remember { mutableStateOf<GroupWithCount?>(null) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showGroupSheet by remember { mutableStateOf(false) }
    var showLayoutSheet by remember { mutableStateOf(false) }
    var pendingSaveAlbumId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingSaveAlbumTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var showScanProgress by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(scanState.isScanning, scanState.isManual) {
        showScanProgress = false
        if (scanState.isScanning && scanState.isManual) {
            delay(2_000)
            showScanProgress = true
        }
    }

    // OpenMultipleDocuments：系统多文件选择器。取消返回空列表而非 null，
    // 所以用 isNotEmpty() 判空——照搬旧的单选判空会触发空批量调用
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addComics(uris)
    }

    // OpenDocumentTree：系统目录选择器。launcher 挂屏幕层级而非菜单内容里：
    // 选目录期间本进程可能被杀，结果只会投递给重建后立即重新注册的接收器
    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }

    val seriesTreePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.addSeriesFolder(uri)
    }

    val albumFileCreator = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.comicbook+zip")
    ) { uri ->
        val comicId = pendingSaveAlbumId
        val title = pendingSaveAlbumTitle
        pendingSaveAlbumId = null
        pendingSaveAlbumTitle = null
        if (uri != null && comicId != null) {
            viewModel.exportAlbum(comicId, title ?: "图册", uri)
        }
    }

    var showAddSheet by remember { mutableStateOf(false) }

    SnackbarMessageEffect(
        message = scanState.message,
        hostState = snackbarHostState,
        onConsumed = viewModel::consumeMessage,
    )

    scanState.seriesConfirmations.firstOrNull()?.let { request ->
        ConfirmDialog(
            title = "继续扫描大型 PDF 目录？",
            text = buildString {
                append("「${request.displayName}」已扫描到至少 ")
                append("${request.metrics.pdfCount} 个 PDF、")
                append("${request.metrics.directoryCount} 个目录和 ")
                append("${request.metrics.entryCount} 个项目。\n\n")
                append("继续后会重新扫描，并允许达到绝对安全上限。")
            },
            confirmLabel = "继续扫描",
            onConfirm = viewModel::confirmSeriesScan,
            onDismiss = viewModel::dismissSeriesScanConfirmation,
        )
    }

    if (showScanProgress && scanState.isScanning && scanState.isManual) {
        AlertDialog(
            onDismissRequest = viewModel::cancelScan,
            icon = { CircularProgressIndicator() },
            title = { Text("正在扫描 PDF 目录") },
            text = { Text("正在读取目录结构。大型或云端目录可能需要一些时间。") },
            confirmButton = {
                TextButton(onClick = viewModel::cancelScan) { Text("取消扫描") }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            // Top-level destinations use a single-row TopAppBar so title and actions stay
            // on one baseline. Group filter is a pinned second row (same idea as Saved tabs).
            Column {
                TopAppBar(
                    title = { Text("书架") },
                    actions = {
                        // 添加是"必须但低频"的功能：顶栏小图标拿最低调的常驻位，
                        // 空书架时的主推入口是空状态里的按钮（渐进式显著度）。
                        // 点开走底部 sheet——与排版抽屉、目录管理同一套视觉语言
                        IconButton(onClick = { showAddSheet = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "添加内容")
                        }
                        // core 图标集没有 Tune，用自建的矢量资源（res/drawable/ic_tune.xml）
                        IconButton(onClick = { showLayoutSheet = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_tune),
                                contentDescription = "书架排版",
                            )
                        }
                        // 刷新已自动化：App 回到前台时由 ShelfViewModel 监听
                        // 进程生命周期自动扫描（ProcessLifecycleOwner）
                        // 目录管理已收进设置页（设置 → 漫画库目录）
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                ShelfGroupFilterBar(
                    label = groupTitle(selectedGroup, groups),
                    onClick = { showGroupSheet = true },
                )
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp),
                snackbar = { data ->
                    Snackbar(
                        snackbarData = data,
                        modifier = Modifier.widthIn(max = 360.dp),
                    )
                },
            )
        },
    ) { innerPadding ->
        // 下拉刷新指示器只跟随"手动"刷新：自动扫描保持完全静默，
        // 结果通过网格的 item 级增删自然呈现
        PullToRefreshBox(
            isRefreshing = scanState.isScanning && scanState.isManual,
            onRefresh = { viewModel.refresh(manual = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val list = comics
            // Crossfade 的 key 用三态枚举而不是列表本身：扫描中增删条目
            // 不触发动画（LazyGrid 自己按 item key 处理），只有
            // 加载中/空/有内容 之间的切换才淡入淡出
            val phase = when {
                list == null -> ShelfPhase.LOADING
                list.isEmpty() -> ShelfPhase.EMPTY
                else -> ShelfPhase.CONTENT
            }
            Crossfade(
                targetState = phase,
                animationSpec = tween(200),
                label = "shelfPhase",
            ) { current ->
                when (current) {
                    // Room 首批数据未到：留白（且通常被启动画面盖住）
                    ShelfPhase.LOADING -> Box(modifier = Modifier.fillMaxSize())
                    ShelfPhase.EMPTY -> Box(
                        // 空状态本身不需要滚动，但下拉刷新手势靠嵌套滚动
                        // 传递——没有可滚动子项时空书架就拉不动了
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Filtered list can be empty for two reasons: brand-new library, or
                        // the active group filter matches nothing. Copy and CTAs differ.
                        ShelfEmptyState(
                            selection = selectedGroup,
                            groupLabel = groupTitle(selectedGroup, groups),
                            onAddContent = { showAddSheet = true },
                            modifier = Modifier.padding(32.dp),
                        )
                    }

                    ShelfPhase.CONTENT -> LazyVerticalGrid(
                        // 固定列数或按最小宽度自适应，由排版设置驱动
                        columns = layout.columns.fixedCount
                            ?.let { GridCells.Fixed(it) }
                            ?: GridCells.Adaptive(minSize = GridDefaults.AdaptiveMinCellWidth),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 渐变期间旧分支仍在组合，list 可能已退回空——orEmpty 兜底
                        items(list.orEmpty(), key = { it.id }) { comic ->
                            ComicCard(
                                comic = comic,
                                aspect = layout.aspect.ratio,
                                crop = layout.crop,
                                onClick = { onOpenComic(comic.id) },
                                onLongClick = { pendingAction = comic },
                            )
                        }
                    }
                }
            }
        }
    }

    // 排版抽屉：抽屉只遮住屏幕下部，上方网格仍可见——点选即生效，
    // 网格当场变化就是"实时预览"
    if (showLayoutSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLayoutSheet = false },
            sheetState = rememberExpandOnlySheetState(),
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            LayoutSheetContent(
                settings = layout,
                onColumnsChange = viewModel::setColumns,
                onAspectChange = viewModel::setAspect,
                onCropChange = viewModel::setCrop,
            )
        }
    }

    // 添加入口的 sheet：与排版抽屉、目录管理共用同一套底部 sheet 语言
    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            AddSheetContent(
                onCreateAlbum = {
                    showAddSheet = false
                    onCreateAlbum()
                },
                onAddFolder = {
                    showAddSheet = false
                    treePicker.launch(buildFolderPickerInitialUri(lastPickedFolder))
                },
                onAddSeriesFolder = {
                    showAddSheet = false
                    seriesTreePicker.launch(buildFolderPickerInitialUri(lastPickedFolder))
                },
                onAddFile = {
                    showAddSheet = false
                    picker.launch(LibraryScanner.COMIC_PICKER_MIME_TYPES)
                },
            )
        }
    }

    if (showGroupSheet) {
        ModalBottomSheet(
            onDismissRequest = { showGroupSheet = false },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            GroupPickerSheetContent(
                groups = groups,
                selected = selectedGroup,
                onSelect = {
                    viewModel.selectGroup(it)
                    showGroupSheet = false
                },
                onCreate = { showCreateGroupDialog = true },
                onRename = { pendingGroupRename = it },
                onDelete = { pendingGroupDelete = it },
            )
        }
    }

    pendingAction?.let { comic ->
        ModalBottomSheet(
            onDismissRequest = { pendingAction = null },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            ComicActionSheetContent(
                comic = comic,
                isAlbum = comic.sourceType == BookSourceType.CREATED_ALBUM,
                onEditAlbum = {
                    pendingAction = null
                    onEditAlbum(comic.id)
                },
                onShareAlbum = {
                    pendingAction = null
                    viewModel.shareAlbum(comic) { intent ->
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            viewModel.showMessage("没有可用的分享应用")
                        }
                    }
                },
                onSaveAlbum = {
                    pendingAction = null
                    viewModel.prepareAlbumFileName(comic) { fileName ->
                        pendingSaveAlbumId = comic.id
                        pendingSaveAlbumTitle = comic.title
                        albumFileCreator.launch(fileName)
                    }
                },
                onAssignGroup = {
                    pendingAction = null
                    pendingGroupAssignment = comic
                },
                onDelete = {
                    pendingAction = null
                    pendingDelete = comic
                },
            )
        }
    }

    pendingGroupAssignment?.let { comic ->
        ModalBottomSheet(
            onDismissRequest = { pendingGroupAssignment = null },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            AssignGroupSheetContent(
                comic = comic,
                groups = groups,
                onSelect = { groupId ->
                    viewModel.setComicGroup(comic, groupId)
                    pendingGroupAssignment = null
                },
            )
        }
    }

    if (showCreateGroupDialog) {
        GroupNameDialog(
            title = "新建分组",
            confirmLabel = "创建",
            onDismiss = { showCreateGroupDialog = false },
            onConfirm = {
                viewModel.createGroup(it)
                showCreateGroupDialog = false
                showGroupSheet = false
            },
        )
    }

    pendingGroupRename?.let { item ->
        GroupNameDialog(
            title = "重命名分组",
            initialName = item.group.name,
            confirmLabel = "保存",
            onDismiss = { pendingGroupRename = null },
            onConfirm = {
                viewModel.renameGroup(item.group.id, it)
                pendingGroupRename = null
            },
        )
    }

    pendingGroupDelete?.let { item ->
        ConfirmDialog(
            title = "删除分组",
            text = "删除「${item.group.name}」？\n\n" +
                    "该分组内的 ${item.comicCount} 本漫画会变为未分组，漫画不会被移除。",
            confirmLabel = "删除",
            onConfirm = {
                viewModel.deleteGroup(item.group.id)
                pendingGroupDelete = null
            },
            onDismiss = { pendingGroupDelete = null },
        )
    }

    pendingDelete?.let { comic ->
        val isAlbum = comic.sourceType == BookSourceType.CREATED_ALBUM
        val rescanHint = when {
            isAlbum -> ""
            comic.folderId == null || comic.isMissing -> ""
            viewModel.isSeriesComic(comic) -> "\n注意：移除后将停止同步该 PDF 章节目录。"
            else -> "\n注意：该漫画来自库目录，重新扫描后会再次出现。"
        }
        ConfirmDialog(
            title = if (isAlbum) "删除图册" else "从书架移除",
            text = if (isAlbum) {
                "删除《${comic.title}》？\n应用内复制的图片会被彻底删除，已导出的 CBZ 不受影响。"
            } else {
                "移除《${comic.title}》？\n原文件不会被删除。$rescanHint"
            },
            confirmLabel = if (isAlbum) "删除" else "移除",
            onConfirm = {
                viewModel.deleteComic(comic)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun GroupPickerSheetContent(
    groups: List<GroupWithCount>?,
    selected: ShelfGroupSelection,
    onSelect: (ShelfGroupSelection) -> Unit,
    onCreate: () -> Unit,
    onRename: (GroupWithCount) -> Unit,
    onDelete: (GroupWithCount) -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetColumn(modifier = modifier, scrollable = true, selectable = true) {
        StandardSheetTitle("选择分组")
        GroupFilterRow(
            title = "全部",
            selected = selected.kind == ShelfGroupFilterKind.ALL,
            onClick = { onSelect(ShelfGroupSelection()) },
        )
        GroupFilterRow(
            title = "未分组",
            selected = selected.kind == ShelfGroupFilterKind.UNGROUPED,
            onClick = {
                onSelect(ShelfGroupSelection(ShelfGroupFilterKind.UNGROUPED))
            },
        )
        groups.orEmpty().forEach { item ->
            val isSelected = selected.kind == ShelfGroupFilterKind.GROUP &&
                    selected.groupId == item.group.id
            InkleafChoiceListItem(
                headline = item.group.name,
                selected = isSelected,
                onClick = {
                    onSelect(
                        ShelfGroupSelection(
                            kind = ShelfGroupFilterKind.GROUP,
                            groupId = item.group.id,
                        )
                    )
                },
                supportingContent = { Text("${item.comicCount} 本漫画") },
                trailingContent = {
                    Row {
                        IconButton(onClick = { onRename(item) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "重命名分组")
                        }
                        IconButton(onClick = { onDelete(item) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除分组")
                        }
                    }
                },
            )
        }
        InkleafActionListItem(
            headline = "新建分组",
            onClick = onCreate,
            leadingContent = {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
    }
}

@Composable
private fun GroupFilterRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InkleafChoiceListItem(
        headline = title,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun ComicActionSheetContent(
    comic: ComicEntity,
    isAlbum: Boolean,
    onEditAlbum: () -> Unit,
    onShareAlbum: () -> Unit,
    onSaveAlbum: () -> Unit,
    onAssignGroup: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetColumn(modifier = modifier, scrollable = true) {
        StandardSheetTitle(comic.title)
        if (isAlbum) {
            InkleafActionListItem(
                headline = "编辑图册",
                onClick = onEditAlbum,
                leadingContent = {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            InkleafActionListItem(
                headline = "分享 CBZ",
                onClick = onShareAlbum,
                leadingContent = {
                    Icon(
                        painterResource(R.drawable.ic_share),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            InkleafActionListItem(
                headline = "保存为 CBZ 文件",
                onClick = onSaveAlbum,
                leadingContent = {
                    Icon(
                        painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }
        InkleafActionListItem(
            headline = "设置分组",
            onClick = onAssignGroup,
            leadingContent = {
                Icon(
                    painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
        InkleafActionListItem(
            headline = if (isAlbum) "删除图册" else "从书架移除",
            onClick = onDelete,
            leadingContent = {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
        )
    }
}

@Composable
private fun AssignGroupSheetContent(
    comic: ComicEntity,
    groups: List<GroupWithCount>?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetColumn(modifier = modifier, scrollable = true, selectable = true) {
        StandardSheetTitle("设置分组")
        GroupFilterRow(
            title = "未分组",
            selected = comic.groupId == null,
            onClick = { onSelect(null) },
        )
        groups.orEmpty().forEach { item ->
            GroupFilterRow(
                title = item.group.name,
                selected = comic.groupId == item.group.id,
                onClick = { onSelect(item.group.id) },
            )
        }
    }
}

@Composable
private fun GroupNameDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialName: String = "",
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            InkleafNameField(
                value = name,
                onValueChange = { name = it },
                label = "分组名",
                singleLine = true,
                modifier = modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun groupTitle(
    selected: ShelfGroupSelection,
    groups: List<GroupWithCount>?,
): String = when (selected.kind) {
    ShelfGroupFilterKind.ALL -> "全部"
    ShelfGroupFilterKind.UNGROUPED -> "未分组"
    ShelfGroupFilterKind.GROUP ->
        groups?.firstOrNull { it.group.id == selected.groupId }?.group?.name ?: "全部"
}

/**
 * Empty shelf body. Library-empty (filter = all) offers one primary CTA into the shared add
 * sheet; group-filter empty only explains how to switch groups — imports stay on the top bar.
 */
@Composable
private fun ShelfEmptyState(
    selection: ShelfGroupSelection,
    groupLabel: String,
    onAddContent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLibraryEmpty = selection.kind == ShelfGroupFilterKind.ALL
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Same glyph as the bottom-nav shelf tab so empty state matches destination identity
        // (history / bookmark / favorite empties do the same with their tab icons).
        Icon(
            imageVector = Icons.Filled.Home,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (isLibraryEmpty) {
            Text(
                text = "还没有漫画",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "添加文件夹建立漫画库，也可导入文件或创建图册。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            // Progressive disclosure: empty state elevates add; later it lives as a top-bar icon.
            Button(onClick = onAddContent) {
                Text("添加内容")
            }
        } else {
            val title = when (selection.kind) {
                ShelfGroupFilterKind.UNGROUPED -> "没有未分组的漫画"
                ShelfGroupFilterKind.GROUP -> "这个分组里还没有漫画"
                ShelfGroupFilterKind.ALL -> error("library-empty branch handles ALL")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "点上方「$groupLabel」可切换分组。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Pinned under the top app bar: shows the active group and opens the existing picker sheet.
 */
@Composable
private fun ShelfGroupFilterBar(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "选择分组，$label" }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 添加入口的 sheet 内容：三个动作行。sheet 行的宽度优势用来放说明文字——
 * 把"目录 = 长期同步的库 / PDF 目录 = 单本多章 / 单本 = 一次性导入"的本质差异讲清楚
 */
@Composable
private fun AddSheetContent(
    onCreateAlbum: () -> Unit,
    onAddFolder: () -> Unit,
    onAddSeriesFolder: () -> Unit,
    onAddFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetColumn(modifier = modifier, scrollable = true) {
        StandardSheetTitle("添加内容")
        InkleafActionListItem(
            headline = "从图片创建图册",
            supporting = "选择图片、调整顺序并制作成可分享的 CBZ",
            onClick = onCreateAlbum,
            leadingContent = {
                Icon(
                    painterResource(R.drawable.ic_image),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
        InkleafActionListItem(
            headline = "添加漫画目录",
            supporting = "选择文件夹建立漫画库，内容变化自动同步",
            onClick = onAddFolder,
            leadingContent = {
                Icon(
                    painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
        InkleafActionListItem(
            headline = "添加 PDF 章节目录",
            supporting = "把文件夹内多个 PDF 作为一本书的章节导入",
            onClick = onAddSeriesFolder,
            leadingContent = {
                Icon(
                    painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
        InkleafActionListItem(
            headline = "添加漫画文件",
            supporting = "可多选，导入一个或多个文件到书架",
            onClick = onAddFile,
            leadingContent = {
                Icon(
                    painterResource(R.drawable.ic_file),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
    }
}

/** 排版抽屉内容：三组单选（列数 / 封面比例 / 封面填充） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayoutSheetContent(
    settings: ShelfLayoutSettings,
    onColumnsChange: (GridColumnsMode) -> Unit,
    onAspectChange: (CoverAspect) -> Unit,
    onCropChange: (CoverCrop) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        Text(text = "书架排版", style = MaterialTheme.typography.titleLarge)

        SheetSectionLabel("列数")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            GridColumnsMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.columns == mode,
                    onClick = { onColumnsChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, GridColumnsMode.entries.size),
                ) {
                    Text(mode.fixedCount?.toString() ?: "自适应")
                }
            }
        }

        SheetSectionLabel("封面比例")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            CoverAspect.entries.forEachIndexed { index, aspect ->
                SegmentedButton(
                    selected = settings.aspect == aspect,
                    onClick = { onAspectChange(aspect) },
                    shape = SegmentedButtonDefaults.itemShape(index, CoverAspect.entries.size),
                ) {
                    Text(
                        when (aspect) {
                            CoverAspect.STANDARD -> "2:3 标准"
                            CoverAspect.BOOK -> "3:4 图书"
                            CoverAspect.SQUARE -> "1:1 方形"
                        }
                    )
                }
            }
        }

        SheetSectionLabel("封面填充")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            CoverCrop.entries.forEachIndexed { index, crop ->
                SegmentedButton(
                    selected = settings.crop == crop,
                    onClick = { onCropChange(crop) },
                    shape = SegmentedButtonDefaults.itemShape(index, CoverCrop.entries.size),
                ) {
                    Text(
                        when (crop) {
                            CoverCrop.CROP -> "裁剪填充"
                            CoverCrop.TOP_CROP -> "顶部裁剪"
                            CoverCrop.CONTAIN -> "完整显示"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicCard(
    comic: ComicEntity,
    aspect: Float,
    crop: CoverCrop,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentAlpha = if (comic.isMissing) 0.45f else 1f

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = if (comic.sourceType == BookSourceType.CREATED_ALBUM) {
                    "打开图册操作"
                } else {
                    "打开漫画操作"
                },
                onLongClick = onLongClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(contentAlpha),
                contentAlignment = Alignment.Center,
            ) {
                // exists() 是磁盘 IO，不能在每次重组都跑——以 comic 行为 key
                // 缓存判定结果：该行任何字段变化（进度更新、封面回填）都会
                // 触发重查，封面"长出来"的刷新链路不受影响
                val coverFile = remember(comic) {
                    comic.coverPath?.let(::File)?.takeIf { it.exists() }
                }
                if (coverFile != null) {
                    AsyncImage(
                        model = coverFile,
                        contentDescription = comic.title,
                        // 填充方式是纯显示期行为：换设置不需要重新生成封面文件
                        contentScale = when (crop) {
                            CoverCrop.CONTAIN -> ContentScale.Fit
                            else -> ContentScale.Crop
                        },
                        alignment = when (crop) {
                            CoverCrop.TOP_CROP -> Alignment.TopCenter
                            else -> Alignment.Center
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = comic.title.take(1),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (comic.isMissing) {
                Text(
                    text = "已失效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = comic.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(contentAlpha),
        )
        Text(
            text = if (comic.pageCount > 0) {
                "${comic.lastReadPage + 1} / ${comic.pageCount}"
            } else {
                "未读"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(contentAlpha),
        )
    }
}
