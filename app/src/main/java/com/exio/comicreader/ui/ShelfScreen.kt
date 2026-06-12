package com.exio.comicreader.ui

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/** 书架内容区的三态：作为 Crossfade 的 key，列表内容增删不触发整区动画 */
private enum class ShelfPhase { LOADING, EMPTY, CONTENT }

/** 单本漫画选择器接受的 MIME 类型（顶栏菜单和空状态按钮共用） */
private val COMIC_MIME_TYPES = arrayOf(
    "application/zip",
    "application/x-cbz",
    "application/octet-stream",
)

/** 首页书架：封面网格 + 目录扫描 + 排版抽屉 + 顶栏添加菜单 + 长按删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    onOpenComic: (Long) -> Unit,
    onOpenFavorites: () -> Unit,
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

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.addComic(uri, onReady = onOpenComic)
    }

    // OpenDocumentTree：系统目录选择器。launcher 挂屏幕层级而非菜单内容里：
    // 选目录期间本进程可能被杀，结果只会投递给重建后立即重新注册的接收器
    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }

    var showAddSheet by remember { mutableStateOf(false) }

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
                    IconButton(onClick = onOpenFavorites) {
                        Icon(
                            painter = painterResource(R.drawable.ic_favorite),
                            contentDescription = "图片收藏",
                        )
                    }
                    // 添加是"必须但低频"的功能：顶栏小图标拿最低调的常驻位，
                    // 空书架时的主推入口是空状态里的按钮（渐进式显著度）。
                    // 点开走底部 sheet——与排版抽屉、目录管理同一套视觉语言
                    IconButton(onClick = { showAddSheet = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加漫画")
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
                // 透明底色：顶栏不保有独立主题色，换肤瞬切才不会被 M3 内部
                // 弹簧拖慢（策略与前提见 Theme.kt 的换肤注释）。本屏内容不会
                // 滚到顶栏底下；若将来加 scrollBehavior 需重新评估
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        // 空书架时"添加"就是此刻的首要动作，给实体按钮；
                        // 库建好后该动作降级回顶栏小图标（渐进式显著度）
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "书架还是空的",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { treePicker.launch(null) }) {
                                Text("添加漫画目录")
                            }
                            TextButton(onClick = { picker.launch(COMIC_MIME_TYPES) }) {
                                Text("或添加单本漫画")
                            }
                        }
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
                                onLongClick = { pendingDelete = comic },
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
                onAddFolder = {
                    showAddSheet = false
                    treePicker.launch(null)
                },
                onAddFile = {
                    showAddSheet = false
                    picker.launch(COMIC_MIME_TYPES)
                },
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

/**
 * 添加入口的 sheet 内容：两个动作行。sheet 行的宽度优势用来放说明文字——
 * 把"目录 = 长期同步的库 / 单本 = 一次性导入"的本质差异讲清楚
 */
@Composable
private fun AddSheetContent(
    onAddFolder: () -> Unit,
    onAddFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
    ) {
        StandardSheetTitle("添加漫画")
        ListItem(
            headlineContent = { Text("添加漫画目录") },
            supportingContent = { Text("选择文件夹建立漫画库，内容变化自动同步") },
            leadingContent = {
                Icon(
                    painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onAddFolder),
        )
        ListItem(
            headlineContent = { Text("添加单本漫画") },
            supportingContent = { Text("导入单个文件，立即开始阅读") },
            leadingContent = {
                Icon(
                    painterResource(R.drawable.ic_file),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onAddFile),
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
