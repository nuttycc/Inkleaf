package com.exio.comicreader.ui

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.exio.comicreader.data.ComicBook
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ReaderScreen(comicId: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: ReaderViewModel = viewModel {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
        ReaderViewModel(app, comicId)
    }

    // 工具栏显隐提升到这一层：系统栏控制要在 Loading 阶段就生效，
    // 不能等 Ready 才开始（否则进入时多一次"系统栏缩进"的视觉跳变）
    var showControls by remember { mutableStateOf(false) }

    val view = LocalView.current
    val window = (view.context as? Activity)?.window

    // 统一的退出路径：先恢复系统栏、再 pop。
    // 若等离开组合后才恢复（onDispose），返回动画播完时 insets 才从 0 跳回，
    // 书架的 TopAppBar 会肉眼可见地向下弹一截；提前到退出瞬间恢复，
    // 跳变发生在纯黑的阅读页上，视觉无感
    val exitReader = {
        if (window != null) {
            WindowCompat.getInsetsController(window, view)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        onBack()
    }

    // 系统返回手势/返回键也要走 exitReader，而不是让 Navigation 直接 pop
    BackHandler(onBack = exitReader)

    if (window != null) {
        DisposableEffect(showControls) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (showControls) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose { }
        }
        // 离开阅读页时恢复系统栏，否则书架也会卡在沉浸态
        DisposableEffect(Unit) {
            onDispose {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 整个阅读页（含 Loading/Error）统一黑底：从书架进入只有一次
    // 平滑的"渐入黑色"，不会出现 白→黑 的背景突变
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Crossfade(targetState = viewModel.state, label = "reader-state") { s ->
            when (s) {
                ReaderUiState.Loading -> LoadingView(Modifier.fillMaxSize())
                is ReaderUiState.Error -> ErrorView(
                    message = s.message,
                    onBack = exitReader,
                    onRemove = { viewModel.removeFromShelf(onDone = exitReader) },
                    modifier = Modifier.fillMaxSize(),
                )

                is ReaderUiState.Ready -> ComicPager(
                    book = s.book,
                    startPage = s.startPage,
                    title = s.title,
                    cacheKeyPrefix = "comic-$comicId",
                    thumbnails = viewModel.thumbnails,
                    onNeedThumbnail = viewModel::requestThumbnail,
                    onPageChanged = viewModel::saveProgress,
                    onBack = exitReader,
                    showControls = showControls,
                    onToggleControls = { showControls = !showControls },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ComicPager(
    book: ComicBook,
    startPage: Int,
    title: String,
    cacheKeyPrefix: String,
    thumbnails: Map<Int, ImageBitmap>,
    onNeedThumbnail: (Int) -> Unit,
    onPageChanged: (Int) -> Unit,
    onBack: () -> Unit,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(
        initialPage = startPage,
        pageCount = { book.pageCount },
    )
    val scope = rememberCoroutineScope()

    // 翻页统一走"前进/后退"抽象：将来日漫右→左模式只需反转点按区到 delta 的映射
    val turnPage: (Int) -> Unit = { delta ->
        val target = (pagerState.currentPage + delta).coerceIn(0, book.pageCount - 1)
        if (target != pagerState.currentPage) {
            scope.launch { pagerState.animateScrollToPage(target) }
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .collect { page -> onPageChanged(page) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 点按与 Pager 的拖动天然不冲突：手指移动超过阈值即升级为拖动、
            // 由 Pager 接管，点按检测自动作废。工具栏上的按钮/滑杆会消费
            // 自己的事件，也不会误触发这里
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val third = size.width / 3f
                    when {
                        offset.x < third -> turnPage(-1)     // 左 1/3：上一页
                        offset.x > third * 2 -> turnPage(1)  // 右 1/3：下一页
                        else -> onToggleControls()           // 中间：工具栏开关
                    }
                }
            },
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ComicPage(
                book = book,
                page = page,
                cacheKeyPrefix = cacheKeyPrefix,
                thumbnail = thumbnails[page],
            )
        }

        if (!showControls) {
            Text(
                text = "${pagerState.currentPage + 1} / ${book.pageCount}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        ReaderTopBar(
            visible = showControls,
            title = title,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        ReaderBottomBar(
            visible = showControls,
            pagerState = pagerState,
            pageCount = book.pageCount,
            thumbnails = thumbnails,
            onNeedThumbnail = onNeedThumbnail,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 顶部工具栏：返回 + 书名。页码信息只在底栏出现，避免重复 */
@Composable
private fun ReaderTopBar(
    visible: Boolean,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.65f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回书架",
                    tint = Color.White,
                )
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // fill = false：weight 只作为最大宽度约束（长书名仍能截断），
                // 短书名不被拉伸成占满整行
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 16.dp),
            )
        }
    }
}

/** 底部工具栏：胶片缩略图导航 + 跳页进度滑杆 */
// Slider 的 thumb/track 自定义插槽在 M3 里仍标记为实验性 API
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderBottomBar(
    visible: Boolean,
    pagerState: PagerState,
    pageCount: Int,
    thumbnails: Map<Int, ImageBitmap>,
    onNeedThumbnail: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val accent = readerAccentColor()

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.65f))
                .navigationBarsPadding()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 拖动中的临时值；null = 未在拖动，滑杆跟随真实页码。
            // 松手才真正跳页：拖动中实时翻页会狂触发图片加载
            var draggingValue by remember { mutableStateOf<Float?>(null) }
            val shownPage = draggingValue?.roundToInt() ?: pagerState.currentPage

            // 胶片条与滑杆共享 shownPage：拖滑杆时胶片实时跟随滚动，
            // 形成"滑杆粗跳 + 胶片看准了再点"的两级定位
            FilmstripRow(
                pageCount = pageCount,
                thumbnails = thumbnails,
                onNeedThumbnail = onNeedThumbnail,
                currentPage = shownPage,
                // 拖动中胶片要瞬时贴住手指，不播追赶动画
                isDragging = draggingValue != null,
                onPageSelected = { page ->
                    // 同滑杆：远距离跳页用瞬时 scrollToPage，避免逐页滑过去
                    // 把途经的页全加载一遍
                    scope.launch { pagerState.scrollToPage(page) }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 页码在滑杆两端的单行布局：当前页 | 粗轨道滑杆 | 总页数。
            // 比"页码单独一行 + 滑杆"省一行高度，也是控制态下唯一的页码来源
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = "${shownPage + 1}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    // 锁最小宽度：页码 9→10 位数变化时文字变宽，
                    // 不锁的话滑杆轨道会跟着抖一下
                    modifier = Modifier.widthIn(min = 36.dp),
                )
                Slider(
                    value = draggingValue ?: pagerState.currentPage.toFloat(),
                    onValueChange = { draggingValue = it },
                    onValueChangeFinished = {
                        draggingValue?.let { v ->
                            scope.launch {
                                // 远距离跳页用瞬时 scrollToPage：animateScrollToPage
                                // 会逐页滑过去，把途经的页全加载一遍
                                pagerState.scrollToPage(v.roundToInt())
                                draggingValue = null
                            }
                        }
                    },
                    valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat(),
                    // 自绘粗轨道 + 竖条手柄，不依赖 M3 各版本默认滑杆样式的差异；
                    // 进度填充与胶片高亮同一强调色，视觉语言统一
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(width = 5.dp, height = 28.dp)
                                .clip(RoundedCornerShape(2.5.dp))
                                .background(Color.White),
                        )
                    },
                    track = {
                        val fraction = if (pageCount > 1) {
                            (draggingValue ?: pagerState.currentPage.toFloat()) / (pageCount - 1)
                        } else {
                            0f
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(14.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Color.White.copy(alpha = 0.25f)),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(accent),
                            )
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                Text(
                    text = "$pageCount",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 36.dp),
                )
            }
        }
    }
}

/**
 * 胶片式缩略图导航条：横向一行迷你缩略图，当前页高亮并保持居中。
 * 横滑只是浏览，点击缩略图才跳页。
 */
@Composable
private fun FilmstripRow(
    pageCount: Int,
    thumbnails: Map<Int, ImageBitmap>,
    onNeedThumbnail: (Int) -> Unit,
    currentPage: Int,
    isDragging: Boolean,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // 当前页变化时让对应缩略图保持居中。
    // 用户手动横滑浏览时 currentPage 不变，不会触发回滚，互不打架
    var hasCentered by remember { mutableStateOf(false) }
    LaunchedEffect(currentPage, isDragging) {
        // 首帧布局完成前 viewport 宽度为 0，算不出居中偏移，等它就绪
        snapshotFlow { listState.layoutInfo.viewportEndOffset }.first { it > 0 }
        val info = listState.layoutInfo
        val viewportWidth = info.viewportEndOffset - info.viewportStartOffset
        val itemWidth = info.visibleItemsInfo.firstOrNull()?.size ?: 0
        // 负偏移 = 让条目起点落在视口中部偏左半个条目宽，即视觉居中
        val centerOffset = -(viewportWidth - itemWidth) / 2

        // 跳转策略（与正文翻页的 scrollToPage 智慧对齐）：
        // - 首次定位/拖滑杆中：瞬时贴住。跟手场景播动画 = 动画刚起步就被
        //   下一次页码变化掐死重启，表现为抖动
        // - 目标就在屏幕内的近距离移动（如普通翻页）：动画滚动，平滑流动
        // - 目标在屏幕外（远跳）："剪辑式"跳转——先瞬移到目标同方向
        //   一屏之外（用户感知不到），再动画滑完最后一屏。观感是一段
        //   干脆的滑动到位，实际途经的格子只有十来个且已预热，
        //   不会像全程 animateScrollToItem 那样逐格爬过几十个格子
        val visibleItems = info.visibleItemsInfo
        val targetVisible = visibleItems.any { it.index == currentPage }
        when {
            !hasCentered || isDragging -> {
                listState.scrollToItem(currentPage, centerOffset)
                hasCentered = true
            }

            targetVisible -> listState.animateScrollToItem(currentPage, centerOffset)

            else -> {
                val visibleCount = visibleItems.size.coerceAtLeast(1)
                // 目标在当前视野的右侧则从左侧一屏外滑入，反之亦然，
                // 保证动画方向与跳转方向一致
                val jumpForward = currentPage > (visibleItems.firstOrNull()?.index ?: 0)
                val approach =
                    if (jumpForward) currentPage - visibleCount else currentPage + visibleCount
                listState.scrollToItem(approach.coerceIn(0, pageCount - 1), centerOffset)
                listState.animateScrollToItem(currentPage, centerOffset)
            }
        }
    }

    LazyRow(
        state = listState,
        // 顶部留白给选中格的放大腾空间：缩放锚点在底边，80dp 高的格子
        // 放大 15% 全部向上生长（约 12dp）；可滚动容器会裁剪越界内容，
        // 不留白会被切平。底部不生长，只留 2dp 呼吸空隙
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        items(count = pageCount, key = { it }) { page ->
            FilmstripThumb(
                page = page,
                thumbnail = thumbnails[page],
                onNeedThumbnail = onNeedThumbnail,
                selected = page == currentPage,
                onClick = { onPageSelected(page) },
            )
        }
    }
}

@Composable
private fun FilmstripThumb(
    page: Int,
    thumbnail: ImageBitmap?,
    onNeedThumbnail: (Int) -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 缓存未命中时向 ViewModel 要一次（预热通常已备好，这里兜底）；
    // 重复请求由 ViewModel 去重，这里只管报告"我可见了"
    if (thumbnail == null) {
        LaunchedEffect(page) { onNeedThumbnail(page) }
    }

    // 非当前页的暗化量带动画：翻页时高亮在胶片上"流动"过去，而不是生硬跳格
    val dimAlpha by animateFloatAsState(
        targetValue = if (selected) 0f else 0.45f,
        label = "thumb-dim",
    )

    // 选中格放大 15%：左右各探出约 4.2dp，刚好落在 8dp 间距内不压邻居
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.15f else 1f,
        label = "thumb-scale",
    )

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(4.dp)
        val accent = readerAccentColor()
        Box(
            modifier = Modifier
                // graphicsLayer 放链首：缩放作用于后续的裁剪/背景/描边整体，
                // 且只发生在绘制阶段——布局尺寸不变，邻居与居中算式都不受影响。
                // 锚点钉在底边中点：放大只向上和左右生长，底边不动，
                // 不会压到紧贴下方的页码数字（Dock 式向上弹起）
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .size(width = 56.dp, height = 80.dp)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.08f))
                .then(
                    // 描边用主题强调色（经亮度兜底，见 readerAccentColor）：
                    // 白描边在白底漫画页上会隐形
                    if (selected) Modifier.border(1.dp, accent, shape) else Modifier
                )
                .clickable(onClick = onClick),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = "第 ${page + 1} 页缩略图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 暗化层盖在图片上方；当前页 alpha 为 0 等于不存在
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimAlpha)),
            )
        }
        Text(
            text = "${page + 1}",
            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * 阅读页强调色（胶片高亮描边、滑杆进度填充）：跟随主题种子，
 * 但保证在黑底上够亮。
 *
 * 不能直接用 primary：浅色模式下它是深色调（tone 40，为白底设计），
 * 压在阅读页的黑色工具栏上对比度不够。inversePrimary 正是 M3 为
 * "反色表面"准备的亮色调 primary（tone 80）。取两者中更亮的一个，
 * 深浅模式下都能落到适合黑底的那档。
 */
@Composable
private fun readerAccentColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.primary.luminance() >= scheme.inversePrimary.luminance()) {
        scheme.primary
    } else {
        scheme.inversePrimary
    }
}

@Composable
private fun ComicPage(
    book: ComicBook,
    page: Int,
    cacheKeyPrefix: String,
    thumbnail: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val bytes by produceState<ByteArray?>(initialValue = null, book, page) {
        value = book.loadPageBytes(page)
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 垫底层：远跳到未加载页时，原图要经历 zip 解压 + 解码（几十到
        // 几百毫秒），期间这层保证屏幕有内容、消除黑屏闪烁。
        // 关键是重度模糊：直接放大的低清图"看得出是糊图"，很难看；
        // 模糊成只剩色调和明暗的色彩氛围，读作有意的过渡效果。
        // blur 依赖 RenderEffect（API 31+），更低版本宁可黑屏也不展示糊图
        val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (thumbnail != null && canBlur) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(24.dp),
            )
        }

        val pageBytes = bytes
        if (pageBytes == null) {
            // 没有模糊垫底（预热未到/系统不支持）才显示转圈
            if (thumbnail == null || !canBlur) {
                DelayedSpinner(showDelay = 200.milliseconds)
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(ByteBuffer.wrap(pageBytes))
                    .memoryCacheKey("$cacheKeyPrefix#$page")
                    // 原图淡入盖过模糊垫底层，"由雾化变清晰"过渡柔和；
                    // 内存缓存命中时 Coil 自动跳过淡入，翻回已读页无延迟感
                    .crossfade(150)
                    .build(),
                contentDescription = "第 ${page + 1} 页",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 延迟显示的转圈：加载在 delayMillis 内完成就全程不显示。
 * "出现即消失的转圈"是闪烁感的主要来源——宁可短暂黑屏也不闪转圈，
 * 这是 delayed spinner 的标准模式。
 */
@Composable
private fun DelayedSpinner(showDelay: Duration, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(showDelay)
        visible = true
    }
    if (visible) {
        CircularProgressIndicator(modifier = modifier, color = Color.White)
    }
}

@Composable
private fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 400ms 内打开完成（小文件/缓存命中）就只是一段平滑的黑色过场
        DelayedSpinner(showDelay = 400.milliseconds)
    }
}

@Composable
private fun ErrorView(
    message: String,
    onBack: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 阅读页统一黑底，文字要显式给亮色
        Text(text = message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onBack) {
            Text("返回书架")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onRemove) {
            Text("从书架移除")
        }
    }
}
