package com.exio.comicreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.exio.comicreader.R
import com.exio.comicreader.data.CoverAspect
import com.exio.comicreader.data.CoverCrop
import com.exio.comicreader.data.GridColumnsMode
import com.exio.comicreader.data.ShelfLayoutSettings
import com.exio.comicreader.data.db.ComicEntity
import java.io.File

/** 首页书架：封面网格 + 目录扫描 + 排版抽屉 + FAB 单文件添加 + 长按删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    onOpenComic: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShelfViewModel = viewModel(),
) {
    val comics by viewModel.comics.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val layout by viewModel.layoutSettings.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<ComicEntity?>(null) }
    var showLayoutSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.addComic(uri, onReady = onOpenComic)
    }

    LaunchedEffect(scanState.message) {
        scanState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("我的书架") },
                actions = {
                    // core 图标集没有 Tune，用自建的矢量资源（res/drawable/ic_tune.xml）
                    IconButton(onClick = { showLayoutSheet = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_tune),
                            contentDescription = "书架排版",
                        )
                    }
                    // 目录管理已收进设置页（设置 → 漫画库目录）
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                picker.launch(
                    arrayOf("application/zip", "application/x-cbz", "application/octet-stream")
                )
            }) {
                Icon(Icons.Filled.Add, contentDescription = "添加单个漫画文件")
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)) {
            if (scanState.isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (comics.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "书架还是空的\n右上角添加漫画库目录，或点 + 添加单个文件",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    // 固定列数或按最小宽度自适应，由排版设置驱动
                    columns = layout.columns.fixedCount
                        ?.let { GridCells.Fixed(it) }
                        ?: GridCells.Adaptive(minSize = 110.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(comics, key = { it.id }) { comic ->
                        ComicCard(
                            comic = comic,
                            aspect = layout.aspect.ratio,
                            crop = layout.crop,
                            onClick = { onOpenComic(comic.id) },
                            onLongClick = { pendingDelete = comic },
                        )
                    }
                }
            }
        }
    }

    // 排版抽屉：抽屉只遮住屏幕下部，上方网格仍可见——点选即生效，
    // 网格当场变化就是"实时预览"
    if (showLayoutSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showLayoutSheet = false },
            sheetState = sheetState,
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

    pendingDelete?.let { comic ->
        val rescanHint = if (comic.folderId != null && !comic.isMissing) {
            "\n注意：该漫画来自库目录，重新扫描后会再次出现。"
        } else {
            ""
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("从书架移除") },
            text = { Text("移除《${comic.title}》？\n原文件不会被删除。$rescanHint") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteComic(comic)
                    pendingDelete = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
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
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                val coverFile = comic.coverPath?.let(::File)?.takeIf { it.exists() }
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
