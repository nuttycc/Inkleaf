package com.exio.comicreader.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.exio.comicreader.R
import com.exio.comicreader.data.db.FavoritePageEntity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenComicPage: (Long, Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = viewModel(),
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    val message = viewModel.message
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("图片收藏") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            snackbarHost = {},
        ) { innerPadding ->
            val list = favorites
            when {
                list == null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )

                list.isEmpty() -> EmptyFavorites(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = GridDefaults.AdaptiveMinCellWidth),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    itemsIndexed(list, key = { _, favorite -> favorite.id }) { index, favorite ->
                        FavoriteCard(
                            favorite = favorite,
                            onClick = { viewerIndex = index },
                        )
                    }
                }
            }
        }

        val list = favorites.orEmpty()
        LaunchedEffect(list.isEmpty(), viewerIndex) {
            if (viewerIndex != null && list.isEmpty()) viewerIndex = null
        }
        viewerIndex?.let { index ->
            if (list.isNotEmpty()) {
                key(list.getOrNull(index)?.id ?: index) {
                    FavoriteViewer(
                        favorites = list,
                        initialIndex = index.coerceIn(0, list.lastIndex),
                        onClose = { viewerIndex = null },
                        onDelete = { favorite ->
                            viewModel.removeFavorite(favorite) { viewerIndex = null }
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
                    )
                }
            }
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
private fun EmptyFavorites(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "还没有收藏的图片",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FavoriteCard(
    favorite: FavoritePageEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val imageFile = remember(favorite) {
                favorite.thumbnailPath
                    ?.let(::File)
                    ?.takeIf { it.exists() }
                    ?: File(favorite.imagePath).takeIf { it.exists() }
            }
            if (imageFile != null) {
                AsyncImage(
                    model = imageFile,
                    contentDescription = favorite.sourceTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = favorite.sourceTitle.take(1),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = favorite.sourceTitle,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "第 ${favorite.pageIndex + 1} 页",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FavoriteViewer(
    favorites: List<FavoritePageEntity>,
    initialIndex: Int,
    onClose: () -> Unit,
    onDelete: (FavoritePageEntity) -> Unit,
    onJumpToSource: (FavoritePageEntity) -> Unit,
    onShare: (FavoritePageEntity) -> Unit,
    onExport: (FavoritePageEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    var pendingDelete by remember { mutableStateOf<FavoritePageEntity?>(null) }
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { favorites.size },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
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

        val favorite = favorites.getOrNull(pagerState.currentPage)
        ViewerTopBar(
            favorite = favorite,
            pageText = "${pagerState.currentPage + 1} / ${favorites.size}",
            onClose = onClose,
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
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("取消收藏") },
            text = { Text("移除《${favorite.sourceTitle}》第 ${favorite.pageIndex + 1} 页？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(favorite)
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ViewerTopBar(
    favorite: FavoritePageEntity?,
    pageText: String,
    onClose: () -> Unit,
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
        IconButton(onClick = onClose) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "关闭",
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
