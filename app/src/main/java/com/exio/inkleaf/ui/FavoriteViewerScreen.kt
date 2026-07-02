package com.exio.inkleaf.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.exio.inkleaf.R
import com.exio.inkleaf.data.db.FavoritePageEntity
import java.io.File

@Composable
fun FavoriteViewerScreen(
    favoriteId: Long,
    onBack: () -> Unit,
    onOpenComicPage: (Long, Int) -> Unit,
    onExitWithMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = viewModel(),
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var initialIndex by rememberSaveable(favoriteId) { mutableStateOf<Int?>(null) }
    var exitRequested by rememberSaveable(favoriteId) { mutableStateOf(false) }

    SnackbarMessageEffect(
        message = viewModel.message,
        hostState = snackbarHostState,
        onConsumed = viewModel::consumeMessage,
    )

    LaunchedEffect(favorites, favoriteId, initialIndex, exitRequested) {
        val list = favorites ?: return@LaunchedEffect
        if (exitRequested) return@LaunchedEffect

        if (initialIndex == null) {
            val index = list.indexOfFirst { it.id == favoriteId }
            if (index < 0) {
                exitRequested = true
                onExitWithMessage("收藏图片不存在")
            } else {
                initialIndex = index
            }
        } else if (list.isEmpty()) {
            exitRequested = true
            onExitWithMessage("收藏图片不存在")
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val list = favorites.orEmpty()
        val startIndex = initialIndex
        if (startIndex != null && list.isNotEmpty()) {
            FavoriteViewer(
                favorites = list,
                initialIndex = startIndex.coerceIn(0, list.lastIndex),
                onBack = onBack,
                onDelete = { favorite ->
                    viewModel.removeFavorite(
                        favorite = favorite,
                        showSuccessMessage = false,
                    ) {
                        exitRequested = true
                        onExitWithMessage("已取消收藏")
                    }
                },
                onJumpToSource = { favorite ->
                    viewModel.openSource(favorite, onOpenComicPage)
                },
                onShare = { favorite ->
                    val intent = viewModel.shareIntent(favorite)
                    if (intent == null) {
                        viewModel.showMessage("收藏图片不存在")
                    } else {
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            viewModel.showMessage("没有可用的分享应用")
                        }
                    }
                },
                onExport = viewModel::exportFavorite,
                modifier = Modifier.fillMaxSize(),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        )
    }
}

@Composable
private fun FavoriteViewer(
    favorites: List<FavoritePageEntity>,
    initialIndex: Int,
    onBack: () -> Unit,
    onDelete: (FavoritePageEntity) -> Unit,
    onJumpToSource: (FavoritePageEntity) -> Unit,
    onShare: (FavoritePageEntity) -> Unit,
    onExport: (FavoritePageEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<FavoritePageEntity?>(null) }
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { favorites.size },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val favorite = favorites[page]
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = File(favorite.imagePath),
                    contentDescription = "${favorite.sourceTitle} 第 ${favorite.pageIndex + 1} 页",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val currentIndex = pagerState.currentPage.coerceIn(0, favorites.lastIndex)
        val favorite = favorites.getOrNull(currentIndex)
        ViewerTopBar(
            favorite = favorite,
            pageText = "${currentIndex + 1} / ${favorites.size}",
            onBack = onBack,
            onDelete = { favorite?.let { pendingDelete = it } },
            onJumpToSource = { favorite?.let(onJumpToSource) },
            onShare = { favorite?.let(onShare) },
            onExport = { favorite?.let(onExport) },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (favorite != null) {
            Text(
                text = "${favorite.sourceTitle} · 第 ${favorite.pageIndex + 1} 页",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    pendingDelete?.let { favorite ->
        ConfirmDialog(
            title = "取消收藏",
            text = "移除《${favorite.sourceTitle}》第 ${favorite.pageIndex + 1} 页？",
            confirmLabel = "移除",
            onConfirm = {
                pendingDelete = null
                onDelete(favorite)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun ViewerTopBar(
    favorite: FavoritePageEntity?,
    pageText: String,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onJumpToSource: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.65f))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
            )
        }
        Text(
            text = pageText,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onJumpToSource, enabled = favorite != null) {
            Icon(
                painter = painterResource(R.drawable.ic_open_in_new),
                contentDescription = "跳回原书",
                tint = Color.White,
            )
        }
        IconButton(onClick = onShare, enabled = favorite != null) {
            Icon(
                painter = painterResource(R.drawable.ic_share),
                contentDescription = "分享",
                tint = Color.White,
            )
        }
        IconButton(onClick = onExport, enabled = favorite != null) {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = "导出到相册",
                tint = Color.White,
            )
        }
        IconButton(onClick = onDelete, enabled = favorite != null) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = "取消收藏",
                tint = Color.White,
            )
        }
    }
}
