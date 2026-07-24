package com.exio.inkleaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.data.ComicVolume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ReaderChapterItem(
    val index: Int,
    val title: String,
    val pageCount: Int,
    val startPage: Int,
    val isReadable: Boolean,
)

internal fun shouldShowChapterMenu(chapterCount: Int): Boolean = chapterCount >= 2

private fun readerChapterItem(
    index: Int,
    title: String,
    pageCount: Int,
    startPage: Int,
    isReadable: Boolean,
): ReaderChapterItem {
    val normalizedPageCount = pageCount.coerceAtLeast(0)
    return ReaderChapterItem(
        index = index,
        title = title.ifBlank { "第 ${index + 1} 章" },
        pageCount = normalizedPageCount,
        startPage = startPage,
        isReadable = normalizedPageCount > 0 && isReadable,
    )
}

internal fun buildReaderChapterItems(
    chapterCount: Int,
    titleOf: (Int) -> String,
    pageCountOf: (Int) -> Int,
    startPageOf: (Int) -> Int,
    readableOf: ((index: Int, pageCount: Int) -> Boolean)? = null,
): List<ReaderChapterItem> = List(chapterCount) { index ->
    val pageCount = pageCountOf(index).coerceAtLeast(0)
    readerChapterItem(
        index = index,
        title = titleOf(index),
        pageCount = pageCount,
        startPage = startPageOf(index),
        isReadable = readableOf?.invoke(index, pageCount) ?: true,
    )
}

internal suspend fun loadReaderChapterItems(
    volume: ComicVolume,
): List<ReaderChapterItem> = withContext(Dispatchers.IO) {
    val chapterCount = volume.chapterCount
    // Read mutable volume metadata on IO, then pass an immutable snapshot to the pure mapper.
    val titles = Array(chapterCount) { "" }
    val pageCounts = IntArray(chapterCount)
    val startPages = IntArray(chapterCount)
    val readable = BooleanArray(chapterCount)
    val metadata = volume.probeChapterMetadata()
    var nextStartPage = 0
    for (index in 0 until chapterCount) {
        val chapterMetadata = metadata.getOrNull(index)
        titles[index] = volume.chapterTitle(index)
        pageCounts[index] = chapterMetadata?.pageCount?.coerceAtLeast(0) ?: 0
        readable[index] = chapterMetadata?.isReadable == true
        startPages[index] = nextStartPage
        nextStartPage += pageCounts[index]
    }
    buildReaderChapterItems(
        chapterCount = chapterCount,
        titleOf = { index -> titles[index] },
        pageCountOf = { index -> pageCounts[index] },
        startPageOf = { index -> startPages[index] },
        readableOf = { index, _ -> readable[index] },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderChaptersSheet(
    volume: ComicVolume,
    currentChapterIndex: Int,
    accent: Color,
    onChaptersLoaded: () -> Unit,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val chapters by produceState<List<ReaderChapterItem>?>(
        initialValue = null,
        key1 = volume,
    ) {
        value = loadReaderChapterItems(volume)
    }
    val loadedChapters = chapters
    val listState = rememberLazyListState()

    LaunchedEffect(loadedChapters) {
        if (loadedChapters != null) onChaptersLoaded()
    }

    LaunchedEffect(loadedChapters, currentChapterIndex) {
        val loaded = loadedChapters ?: return@LaunchedEffect
        if (loaded.isEmpty()) return@LaunchedEffect
        // 让前一章露出来一点，保留"从哪来"的上下文；首章则直接置顶
        val targetIndex = (currentChapterIndex - 1)
            .coerceAtLeast(0)
            .coerceAtMost(loaded.lastIndex)
        listState.scrollToItem(targetIndex)
    }

    ReaderSheetTheme(accent = accent) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberExpandOnlySheetState(),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .navigationBarsPadding(),
            ) {
                // 固定标题栏：不透明背景 + 底部分割线，与滚动列表视觉切分；
                // 右侧提供显式关闭按钮，不依赖下滑手势
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    StandardSheetTitle(
                        "章节${loadedChapters?.let { " · ${it.size}" } ?: ""}",
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                        )
                    }
                }
                HorizontalDivider()
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    if (loadedChapters == null) {
                        item { ChapterSheetLoading() }
                    } else if (loadedChapters.isEmpty()) {
                        item { ReaderChaptersEmptyState() }
                    } else {
                        items(
                            items = loadedChapters,
                            key = { it.index },
                        ) { chapter ->
                            ReaderChapterRow(
                                chapter = chapter,
                                isCurrent = chapter.index == currentChapterIndex,
                                onSelect = onSelect,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterSheetLoading() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ReaderChaptersEmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 32.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = "没有章节",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "这本漫画似乎没有任何可显示的章节。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun ReaderChapterRow(
    chapter: ReaderChapterItem,
    isCurrent: Boolean = false,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = when {
        isCurrent && !chapter.isReadable -> "当前章节，无法打开"
        isCurrent -> "当前章节"
        !chapter.isReadable -> "无法打开"
        else -> null
    }

    ListItem(
        selected = isCurrent,
        onClick = { onSelect(chapter.startPage) },
        enabled = chapter.isReadable,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                status?.let { stateDescription = it }
            },
        // ListItem 的 selected 参数不绘制容器背景，需显式高亮当前章节
        colors = ListItemDefaults.colors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                Color.Transparent
            },
        ),
        trailingContent = if (isCurrent) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (chapter.isReadable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        } else {
            null
        },
        supportingContent = {
            Text(
                text = if (chapter.isReadable) {
                    "第 ${chapter.index + 1} 章 · ${chapter.pageCount} 页"
                } else {
                    "第 ${chapter.index + 1} 章 · 无法打开"
                },
            )
        },
    ) {
        Text(
            text = chapter.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
