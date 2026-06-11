package com.exio.comicreader.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.math.roundToInt

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
            ComicPage(book = book, page = page, cacheKeyPrefix = cacheKeyPrefix)
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
            pageText = "${pagerState.currentPage + 1} / ${book.pageCount}",
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        ReaderBottomBar(
            visible = showControls,
            pagerState = pagerState,
            pageCount = book.pageCount,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 顶部工具栏：返回 + 书名 + 页码 */
@Composable
private fun ReaderTopBar(
    visible: Boolean,
    title: String,
    pageText: String,
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
                modifier = Modifier.weight(1f),
            )
            Text(
                text = pageText,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/** 底部工具栏：跳页进度滑杆 */
@Composable
private fun ReaderBottomBar(
    visible: Boolean,
    pagerState: PagerState,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 拖动中的临时值；null = 未在拖动，滑杆跟随真实页码。
            // 松手才真正跳页：拖动中实时翻页会狂触发图片加载
            var draggingValue by remember { mutableStateOf<Float?>(null) }
            val shownPage = draggingValue?.roundToInt() ?: pagerState.currentPage

            Text(
                text = "${shownPage + 1} / $pageCount",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
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
            )
        }
    }
}

@Composable
private fun ComicPage(
    book: ComicBook,
    page: Int,
    cacheKeyPrefix: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val bytes by produceState<ByteArray?>(initialValue = null, book, page) {
        value = book.loadPageBytes(page)
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val pageBytes = bytes
        if (pageBytes == null) {
            DelayedSpinner(delayMillis = 200)
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(ByteBuffer.wrap(pageBytes))
                    .memoryCacheKey("$cacheKeyPrefix#$page")
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
private fun DelayedSpinner(delayMillis: Long, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMillis)
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
        DelayedSpinner(delayMillis = 400)
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
