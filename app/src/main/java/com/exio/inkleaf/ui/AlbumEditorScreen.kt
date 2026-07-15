package com.exio.inkleaf.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.exio.inkleaf.data.AlbumPageDraft
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumEditorScreen(
    comicId: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: AlbumEditorViewModel = viewModel(key = "album-editor-${comicId ?: "new"}") {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
        AlbumEditorViewModel(app, comicId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddSheet by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = viewModel::importUris,
    )
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = viewModel::importUris,
    )
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let(viewModel::importFolder)
    }

    SnackbarMessageEffect(
        message = state.message,
        hostState = snackbarHostState,
        onConsumed = viewModel::consumeMessage,
    )
    val canLeave = !state.isSaving && !state.isImporting
    val canSave = !state.isLoading &&
            !state.isImporting &&
            !state.isSaving &&
            state.hasUnsavedChanges &&
            state.title.isNotBlank() &&
            state.pages.isNotEmpty()
    val discardAndBack = {
        if (state.hasUnsavedChanges) {
            showDiscardDialog = true
        } else {
            viewModel.discard(onBack)
        }
    }
    val handleBack = {
        if (canLeave) discardAndBack() else viewModel.notifyBusy()
    }
    BackHandler(onBack = handleBack)

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(if (state.isPersisted) "编辑图册" else "创建图册") },
                navigationIcon = {
                    IconButton(
                        onClick = discardAndBack,
                        enabled = canLeave,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (state.hasUnsavedChanges) {
                                "取消编辑"
                            } else {
                                "返回"
                            },
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::save,
                        enabled = canSave,
                    ) {
                        if (state.isSaving) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text("保存中")
                            }
                        } else {
                            Text("保存")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!state.isLoading && state.pages.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { showAddSheet = true },
                            enabled = !state.isImporting && !state.isSaving,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("添加图片")
                        }
                        TextButton(
                            onClick = discardAndBack,
                            enabled = canLeave,
                        ) {
                            Text(if (state.hasUnsavedChanges) "取消" else "返回")
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        when {
            state.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            else -> AlbumEditorContent(
                state = state,
                onTitleChange = viewModel::updateTitle,
                onMovePage = viewModel::movePage,
                onRemovePage = viewModel::removePage,
                onSetCover = viewModel::setCover,
                onClearFailures = viewModel::clearFailures,
                onAdd = { showAddSheet = true },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            SheetColumn(scrollable = true) {
                StandardSheetTitle("添加图片")
                ListItem(
                    headlineContent = { Text("从系统相册选择") },
                    supportingContent = { Text("使用照片选择器多选图片") },
                    modifier = Modifier.clickable {
                        showAddSheet = false
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                )
                ListItem(
                    headlineContent = { Text("从文件中选择") },
                    supportingContent = { Text("从文件管理器多选图片") },
                    modifier = Modifier.clickable {
                        showAddSheet = false
                        filePicker.launch(arrayOf("image/*"))
                    },
                )
                ListItem(
                    headlineContent = { Text("导入文件夹") },
                    supportingContent = { Text("导入所选文件夹当前层的图片") },
                    modifier = Modifier.clickable {
                        showAddSheet = false
                        folderPicker.launch(null)
                    },
                )
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("当前图册的修改尚未保存，确定要离开吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        viewModel.discard(onBack)
                    },
                ) {
                    Text("放弃修改")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("继续编辑")
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumEditorContent(
    state: AlbumEditorUiState,
    onTitleChange: (String) -> Unit,
    onMovePage: (String, String) -> Unit,
    onRemovePage: (String) -> Unit,
    onSetCover: (String) -> Unit,
    onClearFailures: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val hapticFeedback = LocalHapticFeedback.current
    var isReordering by remember { mutableStateOf(false) }
    val reorderableState = rememberReorderableLazyGridState(gridState) { from, to ->
        val movingPageId = from.key as? String
        val targetPageId = to.key as? String
        if (movingPageId != null && targetPageId != null && movingPageId != targetPageId) {
            onMovePage(movingPageId, targetPageId)
            hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.title,
            onValueChange = onTitleChange,
            label = { Text("图册标题") },
            supportingText = if (state.title.isBlank()) {
                { Text("请输入图册标题") }
            } else {
                null
            },
            isError = state.title.isBlank(),
            singleLine = true,
            enabled = !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )

        if (state.isImporting) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "正在复制并处理图片…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        if (state.failedNames.isNotEmpty()) {
            ImportFailures(
                failedNames = state.failedNames,
                onDismiss = onClearFailures,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (state.pages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "还没有图片",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "添加图片后，可长按缩略图拖动排序，点击缩略图设置封面。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                    Button(
                        onClick = onAdd,
                        enabled = !state.isImporting && !state.isSaving,
                    ) {
                        Text("添加图片")
                    }
                }
            }
        } else {
            Text(
                text = "${state.pages.size} 张 · 长按拖动排序 · 点击设置封面",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(GridDefaults.AdaptiveMinCellWidth),
                state = gridState,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(
                    items = state.pages,
                    key = { _, page -> page.id },
                ) { index, page ->
                    val pageActionsEnabled = !state.isSaving &&
                            !state.isImporting &&
                            !isReordering
                    ReorderableItem(
                        state = reorderableState,
                        key = page.id,
                    ) { isDragging ->
                        AlbumPageItem(
                            page = page,
                            pageNumber = index + 1,
                            pageCount = state.pages.size,
                            isCover = state.coverPageId == page.id,
                            isDragging = isDragging,
                            onRemove = { onRemovePage(page.id) },
                            onSetCover = { onSetCover(page.id) },
                            onMoveBackward = if (pageActionsEnabled) {
                                state.pages.getOrNull(index - 1)?.let { previous ->
                                    {
                                        onMovePage(page.id, previous.id)
                                        hapticFeedback.performHapticFeedback(
                                            HapticFeedbackType.SegmentFrequentTick
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            onMoveForward = if (pageActionsEnabled) {
                                state.pages.getOrNull(index + 1)?.let { next ->
                                    {
                                        onMovePage(page.id, next.id)
                                        hapticFeedback.performHapticFeedback(
                                            HapticFeedbackType.SegmentFrequentTick
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            enabled = pageActionsEnabled,
                            modifier = Modifier.longPressDraggableHandle(
                                enabled = !state.isSaving && !state.isImporting,
                                onDragStarted = {
                                    isReordering = true
                                    hapticFeedback.performHapticFeedback(
                                        HapticFeedbackType.GestureThresholdActivate
                                    )
                                },
                                onDragStopped = {
                                    isReordering = false
                                    hapticFeedback.performHapticFeedback(
                                        HapticFeedbackType.GestureEnd
                                    )
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumPageItem(
    page: AlbumPageDraft,
    pageNumber: Int,
    pageCount: Int,
    isCover: Boolean,
    isDragging: Boolean,
    onRemove: () -> Unit,
    onSetCover: () -> Unit,
    onMoveBackward: (() -> Unit)?,
    onMoveForward: (() -> Unit)?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember(page.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.05f else 1f,
        label = "album page drag scale",
    )
    val shadowElevation by animateDpAsState(
        targetValue = if (isDragging) 12.dp else 0.dp,
        label = "album page drag elevation",
    )
    val accessibilityActions = buildList {
        onMoveBackward?.let { action ->
            add(CustomAccessibilityAction("移到前一页") {
                action()
                true
            })
        }
        onMoveForward?.let { action ->
            add(CustomAccessibilityAction("移到后一页") {
                action()
                true
            })
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = if (isCover) 6.dp else 1.dp,
        shadowElevation = shadowElevation,
        color = if (isCover) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = modifier
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (isDragging) 0.92f else 1f
            }
            .semantics {
                selected = isCover
                stateDescription = if (isCover) {
                    "第 $pageNumber 页，共 $pageCount 页，当前封面"
                } else {
                    "第 $pageNumber 页，共 $pageCount 页"
                }
                customActions = accessibilityActions
            }
            .then(
                if (enabled && !isDragging && !isCover) {
                    Modifier.clickable(
                        onClickLabel = "设为封面",
                        onClick = onSetCover,
                    )
                } else {
                    Modifier
                }
            )
    ) {
        Column {
            AsyncImage(
                model = File(page.filePath),
                contentDescription = page.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isCover) "封面" else "第 $pageNumber 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCover) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        enabled = enabled && !isDragging,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "第 $pageNumber 页图片操作",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("设为封面") },
                            enabled = enabled && !isDragging && !isCover,
                            onClick = {
                                showMenu = false
                                onSetCover()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除图片") },
                            enabled = enabled && !isDragging,
                            onClick = {
                                showMenu = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportFailures(
    failedNames: List<String>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${failedNames.size} 张图片未能导入",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = failedNames.take(4).joinToString("、") +
                            if (failedNames.size > 4) " 等" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        }
    }
}
