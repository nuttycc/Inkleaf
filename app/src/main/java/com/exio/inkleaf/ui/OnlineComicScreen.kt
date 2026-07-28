package com.exio.inkleaf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.R
import com.exio.inkleaf.plugin.ChapterSummary
import kotlinx.serialization.json.JsonElement

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineComicScreen(
    pluginId: String,
    sourceId: String,
    opaqueContextJson: String?,
    summaryJson: String?,
    onBack: () -> Unit,
    onOpenChapter: (ChapterSummary, JsonElement?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as InkleafApplication
    val viewModel: OnlineComicViewModel =
        viewModel(key = "online-comic-$pluginId-$sourceId") {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
            OnlineComicViewModel(
                app = app,
                pluginId = pluginId,
                sourceId = sourceId,
                opaqueContextJson = opaqueContextJson,
                summaryJson = summaryJson,
            )
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail
    val chapters = state.chapters
    var isDescriptionExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(detail?.title ?: "在线漫画") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.isInitialLoading && detail == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) { OnlineComicHeaderSkeleton() }
                    item(span = { GridItemSpan(maxLineSpan) }) { OnlineComicChapterSkeleton() }
                }

                detail?.let { comic ->
                    // Compact Horizontal Header Card
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                // Left: Compact Thumbnail Cover (90.dp x 120.dp)
                                comic.cover?.let { cover ->
                                    val request =
                                        remember(cover) {
                                            cover.toImageRequest(
                                                application,
                                                crossfadeMillis = 150,
                                            )
                                        }
                                    AsyncImage(
                                        model = request,
                                        contentDescription = comic.title,
                                        contentScale = ContentScale.Crop,
                                        modifier =
                                            Modifier
                                                .width(90.dp)
                                                .height(120.dp)
                                                .clip(RoundedCornerShape(10.dp)),
                                    )
                                }
                                    ?: Box(
                                        modifier =
                                            Modifier
                                                .width(90.dp)
                                                .height(120.dp)
                                                .clip(RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_file),
                                            contentDescription = null,
                                            tint =
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                    alpha = 0.5f
                                                ),
                                            modifier = Modifier.size(36.dp),
                                        )
                                    }

                                // Right: Title, Author, Source & Bookmark Action
                                Column(
                                    modifier = Modifier.weight(1f).height(120.dp),
                                    verticalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = comic.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )

                                        comic.subtitle
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { author ->
                                                Text(
                                                    text = author,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor =
                                                MaterialTheme.colorScheme.onPrimaryContainer,
                                            shape = RoundedCornerShape(6.dp),
                                        ) {
                                            Text(
                                                text = state.sourceName,
                                                style = MaterialTheme.typography.labelMedium,
                                                modifier =
                                                    Modifier.padding(
                                                        horizontal = 8.dp,
                                                        vertical = 4.dp,
                                                    ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }

                                        // Quick Bookmark Action
                                        if (state.isBookmarked) {
                                            FilledTonalButton(
                                                onClick = viewModel::toggleBookmark,
                                                contentPadding =
                                                    PaddingValues(
                                                        horizontal = 10.dp,
                                                        vertical = 4.dp,
                                                    ),
                                                modifier = Modifier.height(34.dp),
                                            ) {
                                                Icon(
                                                    painter =
                                                        painterResource(R.drawable.ic_bookmark),
                                                    contentDescription = "已追漫",
                                                    modifier = Modifier.size(16.dp),
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    "已追漫",
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = viewModel::toggleBookmark,
                                                contentPadding =
                                                    PaddingValues(
                                                        horizontal = 10.dp,
                                                        vertical = 4.dp,
                                                    ),
                                                modifier = Modifier.height(34.dp),
                                            ) {
                                                Icon(
                                                    painter =
                                                        painterResource(
                                                            R.drawable.ic_bookmark_border
                                                        ),
                                                    contentDescription = "追漫",
                                                    modifier = Modifier.size(16.dp),
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    "追漫",
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Metadata Tag Chips
                    val validTags = comic.tags.filter { it.isNotBlank() }
                    if (comic.status?.isNotBlank() == true || validTags.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                comic.status
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { status ->
                                        item {
                                            AssistChip(
                                                onClick = {},
                                                label = { Text(status) },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        modifier =
                                                            Modifier.size(
                                                                AssistChipDefaults.IconSize
                                                            ),
                                                    )
                                                },
                                            )
                                        }
                                    }
                                items(validTags) { tag ->
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text(tag) },
                                    )
                                }
                            }
                        }
                    }

                    // Description (Clickable expand/collapse, max 2 lines default)
                    comic.description
                        ?.takeIf { it.isNotBlank() }
                        ?.let { desc ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                isDescriptionExpanded = !isDescriptionExpanded
                                            }
                                            .padding(vertical = 2.dp),
                                )
                            }
                        }
                }

                if (state.isStale && state.errorMessage != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "显示的是上次保存的内容",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::retry) { Text("重试") }
                        }
                    }
                }

                if (!state.isStale && state.errorMessage != null) {
                    val message = requireNotNull(state.errorMessage)
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Card(
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                FilledTonalButton(
                                    onClick = viewModel::retry,
                                ) {
                                    Text("重试")
                                }
                            }
                        }
                    }
                }

                // 3-4 Column Compact Chapter Grid
                if (chapters.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "章节 (${chapters.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }

                    items(chapters, key = { chapter -> chapter.chapterId }) { chapter ->
                        OutlinedButton(
                            onClick = {
                                onOpenChapter(
                                    chapter,
                                    viewModel.chapterContext(chapter),
                                )
                            },
                            enabled = chapter.available,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                        ) {
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else if (detail != null && state.isRefreshing) {
                    item(span = { GridItemSpan(maxLineSpan) }) { OnlineComicChapterSkeleton() }
                } else if (detail != null && state.hasLoadedChapters) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "暂无章节",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                }
            }

            if (state.isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopCenter)
                )
            }
        }
    }
}

@Composable
private fun OnlineComicHeaderSkeleton() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SkeletonBlock(Modifier.width(90.dp).height(120.dp))
            Column(
                modifier = Modifier.weight(1f).height(120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(20.dp))
                SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(14.dp))
                Spacer(Modifier.weight(1f))
                SkeletonBlock(Modifier.fillMaxWidth().height(34.dp))
            }
        }
    }
}

@Composable
private fun OnlineComicChapterSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonBlock(Modifier.width(96.dp).height(22.dp))
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { SkeletonBlock(Modifier.weight(1f).height(38.dp)) }
            }
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    )
}
