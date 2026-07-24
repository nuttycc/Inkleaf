package com.exio.inkleaf.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
    for (index in 0 until chapterCount) {
        titles[index] = volume.chapterTitle(index)
        readable[index] = volume.isChapterReadable(index)
        pageCounts[index] = volume.chapterPageCount(index)
    }
    for (index in 0 until chapterCount) {
        startPages[index] = volume.chapterStartPage(index)
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
        listState.scrollToItem((currentChapterIndex + 1).coerceIn(0, loaded.size))
    }

    ReaderSheetTheme(accent = accent) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberExpandOnlySheetState(),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 560.dp)
                    .navigationBarsPadding(),
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        StandardSheetTitle(
                            "章节${loadedChapters?.let { " · ${it.size}" } ?: ""}",
                        )
                    }
                    if (loadedChapters == null) {
                        item {
                            Text(
                                text = "正在读取章节…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
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
