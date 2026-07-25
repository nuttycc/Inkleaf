package com.exio.inkleaf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.data.ComicVolume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ChapterPanelHeightFraction = 0.72f

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
    var panelVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { panelVisible = true }

    LaunchedEffect(loadedChapters) {
        if (loadedChapters != null) onChaptersLoaded()
    }

    LaunchedEffect(loadedChapters, currentChapterIndex) {
        val loaded = loadedChapters ?: return@LaunchedEffect
        if (loaded.isEmpty()) return@LaunchedEffect
        val targetIndex = (currentChapterIndex - 1)
            .coerceAtLeast(0)
            .coerceAtMost(loaded.lastIndex)
        listState.scrollToItem(targetIndex)
    }

    BackHandler(onBack = onDismiss)

    ReaderSheetTheme(accent = accent) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val paneLabel = "章节${loadedChapters?.let { " · ${it.size}" } ?: ""}"

            AnimatedVisibility(
                visible = panelVisible,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(200)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                )
            }

            AnimatedVisibility(
                visible = panelVisible,
                enter = slideInVertically(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy,
                    ),
                ) { it } + fadeIn(animationSpec = spring()),
                exit = slideOutVertically(
                    animationSpec = tween(200),
                ) { it } + fadeOut(animationSpec = tween(200)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(ChapterPanelHeightFraction)
                    .navigationBarsPadding(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { paneTitle = paneLabel },
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, top = 4.dp, end = 12.dp, bottom = 10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = "目录",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                loadedChapters?.let { chapters ->
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                    ) {
                                        Text(
                                            text = "${chapters.size}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "关闭",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        )

                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            if (loadedChapters == null) {
                                item { ChapterPanelLoading() }
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
    }
}

@Composable
private fun ChapterPanelLoading() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
        )
    }
}

@Composable
private fun ReaderChaptersEmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
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
            fontWeight = FontWeight.SemiBold,
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

    val containerColor = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
    } else {
        Color.Transparent
    }

    val contentColor = if (isCurrent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else if (!chapter.isReadable) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = { onSelect(chapter.startPage) },
        enabled = chapter.isReadable,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .semantics {
                status?.let { stateDescription = it }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .width(4.dp)
                        .height(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = chapter.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = if (isCurrent) {
                        MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                )

                Text(
                    text = if (chapter.isReadable) {
                        "第 ${chapter.index + 1} 章"
                    } else {
                        "第 ${chapter.index + 1} 章 · 无法打开"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (chapter.isReadable) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        text = "${chapter.pageCount} 页",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
