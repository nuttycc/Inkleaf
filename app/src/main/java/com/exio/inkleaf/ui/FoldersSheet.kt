package com.exio.inkleaf.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exio.inkleaf.R
import com.exio.inkleaf.data.db.FolderWithCount
import com.exio.inkleaf.data.db.LibraryFolderType

/** 目录列表区的三态：作为 Crossfade 的 key，列表内容增删不触发整区动画 */
private enum class FoldersPhase {
    LOADING,
    EMPTY,
    CONTENT,
}

/**
 * 漫画库目录管理（ModalBottomSheet 内容）：列表、添加、移除（级联删除该目录的书）。
 *
 * 目录选择器（OpenDocumentTree）的 launcher 不在这里——它跳的是外部全屏 Activity，期间本进程可能被杀；结果只会投递给重新注册的 launcher，所以
 * launcher 必须挂在始终参与组合的宿主（SettingsScreen）上，而不是这个 随 sheet 开关条件组合的内容里。
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

    // 宿主 Scaffold 的 Snackbar 会被 sheet 的 window 压住，所以在 sheet 内部展示
    SnackbarMessageEffect(
        message = viewModel.message,
        hostState = snackbarHostState,
        onConsumed = viewModel::consumeMessage,
    )

    Box(modifier = modifier.navigationBarsPadding()) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    // sheet 高度随内容变（空状态 ↔ 列表、列表增删行）：
                    // 平滑过渡而不是跳变
                    .animateContentSize()
                    .padding(bottom = 12.dp)
        ) {
            StandardSheetTitle("漫画库目录")

            val list = folders
            val phase =
                when {
                    list == null -> FoldersPhase.LOADING
                    list.isEmpty() -> FoldersPhase.EMPTY
                    else -> FoldersPhase.CONTENT
                }
            Crossfade(
                targetState = phase,
                animationSpec = tween(200),
                label = "foldersPhase",
                // weight(fill = false)：列表短则按内容收缩，长则只占剩余高度
                // 并自行滚动，保证底部"添加目录"永远可见、不被顶出 sheet
                modifier = Modifier.weight(1f, fill = false),
            ) { current ->
                when (current) {
                    // Room 首批数据未到：不渲染空状态文案，避免闪现
                    FoldersPhase.LOADING -> Box(modifier = Modifier.fillMaxWidth())
                    FoldersPhase.EMPTY ->
                        Text(
                            text = "还没有库目录，添加一个漫画文件夹开始扫描",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )

                    FoldersPhase.CONTENT ->
                        LazyColumn {
                            // 渐变期间旧分支仍在组合，list 可能已变空——orEmpty 兜底
                            items(list.orEmpty(), key = { it.folder.id }) { item ->
                                ListItem(
                                    headlineContent = { Text(item.folder.displayName) },
                                    leadingContent = {
                                        Icon(
                                            painterResource(R.drawable.ic_folder),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    supportingContent = {
                                        Column {
                                            // 计数随扫描入库实时变化（刚添加的目录会从 0
                                            // 涨到 N）：淡入淡出把"跳变"软化成"更新"
                                            AnimatedContent(
                                                targetState = item.comicCount,
                                                transitionSpec = {
                                                    fadeIn(tween(150)) togetherWith
                                                        fadeOut(tween(150))
                                                },
                                                label = "folderCount",
                                            ) { count ->
                                                val typeLabel =
                                                    when (item.folder.type) {
                                                        LibraryFolderType.SERIES -> "PDF 章节目录"
                                                        LibraryFolderType.LIBRARY -> "漫画库"
                                                    }
                                                Text("$typeLabel · $count 本漫画")
                                            }
                                            item.folder.scanIssue?.let { issue ->
                                                Text(
                                                    text = issue,
                                                    color = MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        IconButton(onClick = { pendingDelete = item }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "移除目录")
                                        }
                                    },
                                    colors =
                                        ListItemDefaults.colors(containerColor = Color.Transparent),
                                    // 新行插入/删除时其余行平滑让位，而不是瞬移
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                }
            }

            InkleafActionListItem(
                headline = "添加目录",
                onClick = onAddFolder,
                leadingContent = {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    pendingDelete?.let { item ->
        ConfirmDialog(
            title = "移除库目录",
            text =
                "移除「${item.folder.displayName}」？\n\n" +
                    "该目录扫描到的 ${item.comicCount} 本漫画及其阅读进度将一并移除。" +
                    "原文件不会被删除，手动添加的漫画不受影响。",
            confirmLabel = "移除",
            onConfirm = {
                viewModel.removeFolder(item.folder)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
