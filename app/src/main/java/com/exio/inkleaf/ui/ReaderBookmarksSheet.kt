package com.exio.inkleaf.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.R

@Composable
internal fun ColumnScope.ReaderBookmarksPanelContent(
    bookmarks: List<ReaderBookmarkItem>,
    thumbnails: Map<Int, ImageBitmap>,
    onNeedThumbnail: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    removalsInFlight: Set<String>,
    onRemove: ((ReaderBookmarkItem) -> Unit)?,
) {
    var pendingStaleSelection by remember {
        mutableStateOf<ReaderBookmarkItem?>(null)
    }
    val orderedBookmarks = bookmarks.sortedBy { it.globalPage }

    ReaderAttachedPanelHeader(
        title =
            if (orderedBookmarks.isEmpty()) {
                ReaderPanel.Bookmarks.title()
            } else {
                "${ReaderPanel.Bookmarks.title()} · ${orderedBookmarks.size}"
            }
    )
    ReaderAttachedPanelDivider()
    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        if (orderedBookmarks.isEmpty()) {
            ReaderBookmarksEmptyState()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = orderedBookmarks,
                    key = { it.key },
                ) { item ->
                    val globalPage = item.globalPage
                    ReaderBookmarkRow(
                        bookmark = item,
                        thumbnail = thumbnails[globalPage],
                        removalInFlight = item.key in removalsInFlight,
                        onNeedThumbnail = onNeedThumbnail,
                        onClick = {
                            if (item.stale) {
                                pendingStaleSelection = item
                            } else {
                                onSelect(globalPage)
                            }
                        },
                        onRemove = onRemove?.let { remove -> { remove(item) } },
                    )
                }
            }
        }
    }

    pendingStaleSelection?.let { bookmark ->
        val globalPage = bookmark.globalPage
        AlertDialog(
            onDismissRequest = { pendingStaleSelection = null },
            title = { Text("源内容已变化") },
            text = {
                Text("漫画内容或页面顺序在添加书签后发生了变化。" + "当前只能打开推测的全书第 ${globalPage + 1} 页，仍要打开吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingStaleSelection = null
                        onSelect(globalPage)
                    }
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
    bookmark: ReaderBookmarkItem,
    thumbnail: ImageBitmap?,
    removalInFlight: Boolean,
    onNeedThumbnail: (Int) -> Unit,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    if (!bookmark.stale && thumbnail == null) {
        LaunchedEffect(bookmark.globalPage) { onNeedThumbnail(bookmark.globalPage) }
    }

    Surface(
        onClick = onClick,
        enabled = !removalInFlight,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            // portrait thumbnail container (~3:4)
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.width(48.dp)
                        .height(66.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                if (!bookmark.stale && thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = "全书第 ${bookmark.globalPage + 1} 页缩略图",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_bookmark_border),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (bookmark.chapterTitle.isNotBlank()) {
                    Text(
                        text = bookmark.chapterTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text(
                    text =
                        "全书第 ${bookmark.globalPage + 1} 页 · " + "章节第 ${bookmark.pageIndex + 1} 页",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (bookmark.stale) {
                    Text(
                        text = "源内容已变化，当前页码为近似位置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
            }

            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    enabled = !removalInFlight,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bookmark),
                        contentDescription = "移除书签",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderBookmarksEmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
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
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "点按顶栏书签按钮即可添加。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
