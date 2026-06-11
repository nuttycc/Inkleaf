package com.exio.comicreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exio.comicreader.data.db.FolderWithCount

/** 漫画库目录管理：列表、添加（OpenDocumentTree）、移除（级联删除该目录的书） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoldersViewModel = viewModel(),
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<FolderWithCount?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // OpenDocumentTree：系统目录选择器，授权的是整棵子树
    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }

    // 一次性消息 → Snackbar（展示完清空，防止旋转屏幕后重复弹出）
    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("漫画库目录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { treePicker.launch(null) }) {
                Icon(Icons.Filled.Add, contentDescription = "添加目录")
            }
        },
    ) { innerPadding ->
        if (folders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "还没有库目录\n点右下角 + 选择一个漫画文件夹",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(folders, key = { it.folder.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.folder.displayName) },
                        supportingContent = { Text("${item.comicCount} 本漫画") },
                        trailingContent = {
                            IconButton(onClick = { pendingDelete = item }) {
                                Icon(Icons.Filled.Delete, contentDescription = "移除目录")
                            }
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移除库目录") },
            text = {
                Text(
                    "移除「${item.folder.displayName}」？\n\n" +
                            "该目录扫描到的 ${item.comicCount} 本漫画及其阅读进度将一并移除。" +
                            "原文件不会被删除，手动添加的漫画不受影响。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeFolder(item.folder)
                    pendingDelete = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}
