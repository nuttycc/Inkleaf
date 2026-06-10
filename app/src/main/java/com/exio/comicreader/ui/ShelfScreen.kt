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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.exio.comicreader.data.db.ComicEntity
import java.io.File

/** 首页书架：封面网格 + 目录扫描 + FAB 单文件添加 + 长按删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    onOpenComic: (Long) -> Unit,
    onOpenFolders: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShelfViewModel = viewModel(),
) {
    val comics by viewModel.comics.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<ComicEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.addComic(uri, onReady = onOpenComic)
    }

    // 扫描错误 → Snackbar，一次性消费
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
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !scanState.isScanning,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新扫描")
                    }
                    // core 图标集里没有 Folder，用 List 表达"目录管理"
                    IconButton(onClick = onOpenFolders) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "管理漫画库目录")
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
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (scanState.isScanning) {
                // 不定进度条：扫描总量未知，只表达"正在进行"
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
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(comics, key = { it.id }) { comic ->
                        ComicCard(
                            comic = comic,
                            onClick = { onOpenComic(comic.id) },
                            onLongClick = { pendingDelete = comic },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { comic ->
        // 扫描来源的条目（文件还在时）删了下次扫描会重新出现，要提前说明
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicCard(
    comic: ComicEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 失效条目整体淡化；角标保持完全不透明所以放在淡化层之外
    val contentAlpha = if (comic.isMissing) 0.45f else 1f

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().alpha(contentAlpha),
                contentAlignment = Alignment.Center,
            ) {
                val coverFile = comic.coverPath?.let(::File)?.takeIf { it.exists() }
                if (coverFile != null) {
                    AsyncImage(
                        model = coverFile,
                        contentDescription = comic.title,
                        contentScale = ContentScale.Crop,
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
