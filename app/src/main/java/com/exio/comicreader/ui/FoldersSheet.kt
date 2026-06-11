package com.exio.comicreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exio.comicreader.data.db.FolderWithCount

/**
 * 漫画库目录管理（ModalBottomSheet 内容）：列表、添加、移除（级联删除该目录的书）。
 *
 * 目录选择器（OpenDocumentTree）的 launcher 不在这里——它跳的是外部全屏
 * Activity，期间本进程可能被杀；结果只会投递给重新注册的 launcher，所以
 * launcher 必须挂在始终参与组合的宿主（SettingsScreen）上，而不是这个
 * 随 sheet 开关条件组合的内容里。
 */
@Composable
fun FoldersSheetContent(
    onAddFolder: () -> Unit,
    viewModel: FoldersViewModel,
    modifier: Modifier = Modifier,
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<FolderWithCount?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 一次性消息 → Snackbar（展示完清空，防止旋转屏幕后重复弹出）。
    // 宿主 Scaffold 的 Snackbar 会被 sheet 的 window 压住，所以在 sheet 内部展示
    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Box(modifier = modifier.navigationBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = "漫画库目录",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            if (folders.isEmpty()) {
                Text(
                    text = "还没有库目录，添加一个漫画文件夹开始扫描",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            } else {
                // weight(fill = false)：列表短则按内容收缩，长则只占剩余高度
                // 并自行滚动，保证底部"添加目录"永远可见、不被顶出 sheet
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(folders, key = { it.folder.id }) { item ->
                        ListItem(
                            headlineContent = { Text(item.folder.displayName) },
                            supportingContent = { Text("${item.comicCount} 本漫画") },
                            trailingContent = {
                                IconButton(onClick = { pendingDelete = item }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "移除目录")
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }

            ListItem(
                headlineContent = { Text("添加目录") },
                leadingContent = {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = onAddFolder),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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
