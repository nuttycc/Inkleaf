package com.exio.inkleaf.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.exio.inkleaf.R
import com.exio.inkleaf.data.BookmarkResolution
import com.exio.inkleaf.data.db.BookmarkEntity
import com.exio.inkleaf.data.db.BookmarkWithComic
import java.io.File

private const val BOOKMARKS_TAB_INDEX = 0
private const val FAVORITES_TAB_INDEX = 1

internal data class SavedBookmarkGroup(
    val comicId: Long,
    val comicTitle: String,
    val latestAddedAt: Long,
    val bookmarks: List<BookmarkWithComic>,
)

internal fun groupAndSortBookmarks(
    bookmarks: List<BookmarkWithComic>,
): List<SavedBookmarkGroup> = bookmarks
    .groupBy { it.bookmark.comicId }
    .map { (comicId, comicBookmarks) ->
        val first = comicBookmarks.first()
        SavedBookmarkGroup(
            comicId = comicId,
            comicTitle = first.comicTitle,
            latestAddedAt = comicBookmarks.maxOf { it.bookmark.addedAt },
            bookmarks = comicBookmarks.sortedWith(
                compareBy<BookmarkWithComic> { it.bookmark.chapterIndex }
                    .thenBy { it.bookmark.pageIndex }
                    .thenBy { it.bookmark.id },
            ),
        )
    }
    .sortedWith(
        compareByDescending<SavedBookmarkGroup> { it.latestAddedAt }
            .thenByDescending { group -> group.bookmarks.maxOf { it.bookmark.id } },
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    onOpenBookmark: (comicId: Long, globalPage: Int) -> Unit,
    onOpenFavorite: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewerMessage: String? = null,
    onViewerMessageConsumed: () -> Unit = {},
    savedViewModel: SavedViewModel = viewModel(),
    favoritesViewModel: FavoritesViewModel = viewModel(),
) {
    val bookmarks by savedViewModel.bookmarks.collectAsStateWithLifecycle()
    val favorites by favoritesViewModel.favorites.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(BOOKMARKS_TAB_INDEX) }
    var sourceChanged by remember { mutableStateOf<BookmarkResolution.SourceChanged?>(null) }

    SnackbarMessageEffect(
        message = favoritesViewModel.message,
        hostState = snackbarHostState,
        onConsumed = favoritesViewModel::consumeMessage,
    )
    LaunchedEffect(viewerMessage) {
        viewerMessage?.let {
            onViewerMessageConsumed()
            snackbarHostState.showSnackbar(it)
        }
    }
    LaunchedEffect(savedViewModel) {
        savedViewModel.events.collect { event ->
            when (event) {
                is SavedEvent.BookmarkRemoved -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "已移除书签",
                        actionLabel = "撤销",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        savedViewModel.restore(event.bookmark)
                    }
                }

                is SavedEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    val topAppBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                MediumFlexibleTopAppBar(
                    title = { Text("已保存") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                    scrollBehavior = topAppBarScrollBehavior,
                )
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == BOOKMARKS_TAB_INDEX,
                        onClick = { selectedTab = BOOKMARKS_TAB_INDEX },
                        text = { Text("书签") },
                    )
                    Tab(
                        selected = selectedTab == FAVORITES_TAB_INDEX,
                        onClick = { selectedTab = FAVORITES_TAB_INDEX },
                        text = { Text("收藏") },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (selectedTab) {
            BOOKMARKS_TAB_INDEX -> BookmarksContent(
                bookmarks = bookmarks,
                thumbnailStates = savedViewModel.thumbnailStates,
                onLoadThumbnail = savedViewModel::loadThumbnail,
                onRemove = savedViewModel::remove,
                onOpen = { bookmark ->
                    savedViewModel.resolve(bookmark) { resolution ->
                        when (resolution) {
                            is BookmarkResolution.Ready -> onOpenBookmark(
                                resolution.comicId,
                                resolution.globalPage,
                            )

                            is BookmarkResolution.SourceChanged -> sourceChanged = resolution

                            is BookmarkResolution.Unavailable -> {
                                // Route the transient failure through the ViewModel event stream.
                                savedViewModel.showMessage(resolution.message)
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            else -> FavoritesContent(
                favorites = favorites,
                onOpenFavorite = onOpenFavorite,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }

    sourceChanged?.let { resolution ->
        AlertDialog(
            onDismissRequest = { sourceChanged = null },
            title = { Text("源内容已变化") },
            text = { Text("漫画内容在添加书签后发生了变化。可以尝试打开最接近的页面，但位置可能不完全一致。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        sourceChanged = null
                        onOpenBookmark(resolution.comicId, resolution.approximateGlobalPage)
                    },
                ) {
                    Text("仍打开")
                }
            },
            dismissButton = {
                TextButton(onClick = { sourceChanged = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun BookmarksContent(
    bookmarks: List<BookmarkWithComic>?,
    thumbnailStates: Map<Long, BookmarkThumbnailState>,
    onLoadThumbnail: (BookmarkEntity) -> Unit,
    onRemove: (BookmarkEntity) -> Unit,
    onOpen: (BookmarkEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        bookmarks == null -> Box(modifier = modifier)

        bookmarks.isEmpty() -> EmptyBookmarks(modifier = modifier)

        else -> {
            val groups = remember(bookmarks) { groupAndSortBookmarks(bookmarks) }
            LazyColumn(modifier = modifier) {
                groups.forEach { group ->
                    item(key = "header-${group.comicId}") {
                        BookmarkGroupHeader(group)
                    }
                    items(group.bookmarks, key = { it.bookmark.id }) { bookmarkWithComic ->
                        val bookmark = bookmarkWithComic.bookmark
                        LaunchedEffect(bookmark.id) {
                            onLoadThumbnail(bookmark)
                        }
                        BookmarkRow(
                            item = bookmarkWithComic,
                            thumbnailState = thumbnailStates[bookmark.id],
                            onClick = { onOpen(bookmark) },
                            onRemove = { onRemove(bookmark) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkGroupHeader(group: SavedBookmarkGroup) {
    val missing = group.bookmarks.first().isMissing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.comicTitle,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (missing) "原书不可用" else "${group.bookmarks.size} 个书签",
            style = MaterialTheme.typography.labelMedium,
            color = if (missing) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun BookmarkRow(
    item: BookmarkWithComic,
    thumbnailState: BookmarkThumbnailState?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookmarkThumbnail(
            item = item,
            state = thumbnailState,
            modifier = Modifier
                .width(56.dp)
                .aspectRatio(2f / 3f)
                .alpha(if (item.isMissing) 0.55f else 1f),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .weight(1f)
                .alpha(if (item.isMissing) 0.55f else 1f),
        ) {
            Text(
                text = item.comicTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = bookmarkLocationLabel(item.bookmark),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                painter = painterResource(R.drawable.ic_bookmark),
                contentDescription = "移除书签",
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 84.dp))
}

private fun bookmarkLocationLabel(bookmark: BookmarkEntity): String {
    val chapter = bookmark.chapterTitle.ifBlank { "第 ${bookmark.chapterIndex + 1} 章" }
    return "$chapter · 第 ${bookmark.pageIndex + 1} 页"
}

@Composable
private fun BookmarkThumbnail(
    item: BookmarkWithComic,
    state: BookmarkThumbnailState?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is BookmarkThumbnailState.Ready -> Image(
                bitmap = state.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            else -> BookmarkCoverFallback(item)
        }
    }
}

@Composable
private fun BookmarkCoverFallback(item: BookmarkWithComic) {
    val cover = remember(item.coverPath) {
        item.coverPath?.let(::File)?.takeIf(File::exists)
    }
    var coverFailed by remember(cover) { mutableStateOf(false) }
    if (cover != null && !coverFailed) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { coverFailed = true },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Text(
            text = item.comicTitle.firstOrNull()?.toString().orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyBookmarks(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "还没有书签",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "阅读漫画时，可在阅读器顶栏添加书签。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
