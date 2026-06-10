package com.exio.comicreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.exio.comicreader.data.ComicBook
import java.nio.ByteBuffer

@Composable
fun ReaderScreen(comicId: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    // ViewModel 带自定义参数（comicId）时用 initializer 写法：
    // 闭包里手动构造，数据流向一目了然。APPLICATION_KEY 取出 Application
    val viewModel: ReaderViewModel = viewModel {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
        ReaderViewModel(app, comicId)
    }

    // 系统返回键不需要手动处理：Navigation 默认弹出返回栈，
    // ViewModel 随之销毁并在 onCleared 里释放资源

    when (val s = viewModel.state) {
        ReaderUiState.Loading -> LoadingView(modifier)
        is ReaderUiState.Error -> ErrorView(
            message = s.message,
            onBack = onBack,
            onRemove = { viewModel.removeFromShelf(onDone = onBack) },
            modifier = modifier,
        )
        is ReaderUiState.Ready -> ComicPager(
            book = s.book,
            startPage = s.startPage,
            cacheKeyPrefix = "comic-$comicId",
            onPageChanged = viewModel::saveProgress,
            modifier = modifier,
        )
    }
}

@Composable
private fun ComicPager(
    book: ComicBook,
    startPage: Int,
    cacheKeyPrefix: String,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // initialPage：进入时直接落在上次读到的页。因为 Ready 态才会组合本组件，
    // 这里拿到的 startPage 一定是已就绪的数据，不存在"先建 Pager 再补跳页"的竞态
    val pagerState = rememberPagerState(
        initialPage = startPage,
        pageCount = { book.pageCount },
    )

    // snapshotFlow：把 Compose 状态变成 Flow 来订阅——currentPage 一变就保存进度。
    // 翻页是低频事件且单行 UPDATE 极快，所以不做防抖；
    // 防抖反而会在"快速翻几页立刻退出"时丢掉最后的进度
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .collect { page -> onPageChanged(page) }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            // 当前页两侧各保留 1 页的组合状态，即"预加载相邻页"
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ComicPage(book = book, page = page, cacheKeyPrefix = cacheKeyPrefix)
        }

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
}

@Composable
private fun ComicPage(
    book: ComicBook,
    page: Int,
    cacheKeyPrefix: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 页面进入组合时在 IO 线程读字节；滑出预加载范围被销毁时协程自动取消
    val bytes by produceState<ByteArray?>(initialValue = null, book, page) {
        value = book.loadPageBytes(page)
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val pageBytes = bytes
        if (pageBytes == null) {
            CircularProgressIndicator()
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    // Coil 2.x 支持 ByteBuffer，按布局尺寸自动下采样解码，
                    // LRU 内存缓存自动淘汰，不会 OOM
                    .data(ByteBuffer.wrap(pageBytes))
                    // ByteBuffer 没有默认缓存 key，不设置则回翻每页都重新解码
                    .memoryCacheKey("$cacheKeyPrefix#$page")
                    .build(),
                contentDescription = "第 ${page + 1} 页",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LoadingView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text("正在加载漫画…")
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
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onBack) {
            Text("返回书架")
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 文件已失效时的清理出口：删除记录、封面并释放权限
        OutlinedButton(onClick = onRemove) {
            Text("从书架移除")
        }
    }
}
