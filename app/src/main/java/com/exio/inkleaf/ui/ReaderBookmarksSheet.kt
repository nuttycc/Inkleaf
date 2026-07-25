package com.exio.inkleaf.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.exio.inkleaf.data.db.BookmarkEntity

@Composable
internal fun ColumnScope.ReaderBookmarksPanelContent(
    bookmarks: List<ResolvedReaderBookmark>,
    staleBookmarkIds: Set<Long>,
    thumbnails: Map<Int, ImageBitmap>,
    onNeedThumbnail: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    removalsInFlight: Set<Long>,
    onRemove: (BookmarkEntity) -> Unit,
) {
    var pendingStaleSelection by remember {
        mutableStateOf<Pair<Int, BookmarkEntity>?>(null)
    }
    val orderedBookmarks = bookmarks.sortedBy { it.globalPage }

    ReaderAttachedPanelHeader(
        title = if (orderedBookmarks.isEmpty()) {
            ReaderPanel.Bookmarks.title()
        } else {
            "${ReaderPanel.Bookmarks.title()} · ${orderedBookmarks.size}"
        },
    )
    ReaderAttachedPanelDivider()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
    ) {
        if (orderedBookmarks.isEmpty()) {
            ReaderBookmarksEmptyState()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = orderedBookmarks,
                    key = { it.bookmark.id },
                ) { item ->
                    val globalPage = item.globalPage
                    val bookmark = item.bookmark
                    val stale = bookmark.id in staleBookmarkIds
                    ReaderBookmarkCard(
                        bookmark = bookmark,
                        globalPage = globalPage,
                        thumbnail = thumbnails[globalPage],
                        stale = stale,
                        removalInFlight = bookmark.id in removalsInFlight,
                        onNeedThumbnail = onNeedThumbnail,
                        onClick = {
                            if (stale) {
                                pendingStaleSelection = globalPage to bookmark
                            } else {
                                onSelect(globalPage)
                            }
                        },
                        onRemove = { onRemove(bookmark) },
                    )
                }
            }
        }
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
private fun ReaderBookmarkCard(
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

    Surface(
        onClick = onClick,
        enabled = !removalInFlight,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.25f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
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
                        modifier = Modifier.size(28.dp),
                    )
                }

                // Top-right delete bookmark icon
                IconButton(
                    onClick = onRemove,
                    enabled = !removalInFlight,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(28.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(14.dp),
                        ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bookmark),
                        contentDescription = "移除书签",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Page badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp),
                ) {
                    Text(
                        text = "P.${globalPage + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = bookmark.chapterTitle.ifBlank {
                    "第 ${bookmark.chapterIndex + 1} 章"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            if (stale) {
                Text(
                    text = "源内容已变化",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
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

