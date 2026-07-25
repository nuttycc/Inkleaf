package com.exio.inkleaf.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.R
import com.exio.inkleaf.data.db.BookmarkEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ColumnScope.ReaderBookmarksPanelContent(
    bookmarks: List<ResolvedReaderBookmark>,
    staleBookmarkIds: Set<Long>,
    thumbnails: Map<Int, ImageBitmap>,
    onNeedThumbnail: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    onRemove: suspend (BookmarkEntity) -> Unit,
    onRestore: suspend (BookmarkEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val removalsInFlight = remember { mutableStateMapOf<Long, Boolean>() }
    var pendingStaleSelection by remember {
        mutableStateOf<Pair<Int, BookmarkEntity>?>(null)
    }
    val orderedBookmarks = bookmarks.sortedBy { it.globalPage }

    ReaderPanelHeader(
        title = if (orderedBookmarks.isEmpty()) {
            "本书书签"
        } else {
            "本书书签 · ${orderedBookmarks.size}"
        },
        onDismiss = {
            snackbarHostState.currentSnackbarData?.dismiss()
            onDismiss()
        },
    )
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 88.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (orderedBookmarks.isEmpty()) {
                item { ReaderBookmarksEmptyState() }
            } else {
                items(
                    items = orderedBookmarks,
                    key = { it.bookmark.id },
                ) { item ->
                    val globalPage = item.globalPage
                    val bookmark = item.bookmark
                    val stale = bookmark.id in staleBookmarkIds
                    ReaderBookmarkRow(
                        bookmark = bookmark,
                        globalPage = globalPage,
                        thumbnail = thumbnails[globalPage],
                        stale = stale,
                        removalInFlight = removalsInFlight[bookmark.id] == true,
                        onNeedThumbnail = onNeedThumbnail,
                        onClick = {
                            if (stale) {
                                pendingStaleSelection = globalPage to bookmark
                            } else {
                                onSelect(globalPage)
                            }
                        },
                        onRemove = {
                            if (!removalsInFlight.containsKey(bookmark.id)) {
                                removalsInFlight[bookmark.id] = true
                                scope.launch {
                                    try {
                                        onRemove(bookmark)
                                        val result = snackbarHostState.showSnackbar(
                                            message = "已移除书签",
                                            actionLabel = "撤销",
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            try {
                                                onRestore(bookmark)
                                            } catch (error: Exception) {
                                                if (error is CancellationException) throw error
                                                snackbarHostState.showSnackbar("恢复书签失败")
                                            }
                                        }
                                    } catch (error: Exception) {
                                        if (error is CancellationException) throw error
                                        snackbarHostState.showSnackbar("移除书签失败")
                                    } finally {
                                        removalsInFlight.remove(bookmark.id)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    pendingStaleSelection?.let { (globalPage, _) ->
        AlertDialog(
                onDismissRequest = { pendingStaleSelection = null },
                title = { Text("源内容已变化") },
                text = {
                    Text(
                        "漫画内容或页面顺序在添加书签后发生了变化。" +
                                "当前只能打开推测的全书第 ${globalPage + 1} 页，仍要打开吗？"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingStaleSelection = null
                            onSelect(globalPage)
                        },
                    ) {
                        Text("仍然打开")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingStaleSelection = null }) {
                        Text("取消")
                    }
                },
        )
    }
}

@Composable
private fun ReaderBookmarkRow(
    bookmark: BookmarkEntity,
    globalPage: Int,
    thumbnail: ImageBitmap?,
    stale: Boolean,
    removalInFlight: Boolean,
    onNeedThumbnail: (Int) -> Unit,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    if (!stale && thumbnail == null) {
        LaunchedEffect(globalPage) { onNeedThumbnail(globalPage) }
    }

    ListItem(
        headlineContent = {
            Text(
                text = bookmark.chapterTitle.ifBlank {
                    "第 ${bookmark.chapterIndex + 1} 章"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text("章节第 ${bookmark.pageIndex + 1} 页 · 全书第 ${globalPage + 1} 页")
                if (stale) {
                    Text(
                        text = "源内容已变化，当前页码为近似位置",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 48.dp, height = 68.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (!stale && thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = "全书第 ${globalPage + 1} 页缩略图",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_bookmark_border),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            IconButton(
                onClick = onRemove,
                enabled = !removalInFlight,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bookmark),
                    contentDescription = "移除全书第 ${globalPage + 1} 页书签",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = Modifier.clickable(
            enabled = !removalInFlight,
            onClick = onClick,
        ),
    )
}

@Composable
private fun ReaderBookmarksEmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 32.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_bookmark_border),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = "还没有书签",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "阅读时点按顶栏的书签按钮，即可在这里快速返回。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
