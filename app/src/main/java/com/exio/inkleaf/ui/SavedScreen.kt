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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
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

internal data class OnlineSavedBookmarkGroup(
    val key: String,
    val title: String,
    val latestAddedAt: Long,
    val bookmarks: List<OnlineSavedBookmarkUi>,
)

internal fun groupAndSortBookmarks(bookmarks: List<BookmarkWithComic>): List<SavedBookmarkGroup> =
    bookmarks
        .groupBy { it.bookmark.comicId }
        .map { (comicId, comicBookmarks) ->
            val first = comicBookmarks.first()
            SavedBookmarkGroup(
                comicId = comicId,
                comicTitle = first.comicTitle,
                latestAddedAt = comicBookmarks.maxOf { it.bookmark.addedAt },
                bookmarks =
                    comicBookmarks.sortedWith(
                        compareBy<BookmarkWithComic> { it.bookmark.chapterIndex }
                            .thenBy { it.bookmark.pageIndex }
                            .thenBy { it.bookmark.id }
                    ),
            )
        }
        .sortedWith(
            compareByDescending<SavedBookmarkGroup> { it.latestAddedAt }
                .thenByDescending { group -> group.bookmarks.maxOf { it.bookmark.id } }
        )

internal fun groupAndSortOnlineBookmarks(
    bookmarks: List<OnlineSavedBookmarkUi>
): List<OnlineSavedBookmarkGroup> =
    bookmarks
        .groupBy { "${it.target.pluginId}:${it.target.sourceId}" }
        .map { (key, sourceBookmarks) ->
            OnlineSavedBookmarkGroup(
                key = key,
                title = sourceBookmarks.first().title,
                latestAddedAt = sourceBookmarks.maxOf { it.addedAtMs },
                bookmarks =
                    sourceBookmarks.sortedWith(
                        compareBy<OnlineSavedBookmarkUi> { it.target.chapterId }
                            .thenBy { it.pageIndex }
                            .thenBy { it.key }
                    ),
            )
        }
        .sortedByDescending { it.latestAddedAt }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    onOpenBookmark: (comicId: Long, globalPage: Int) -> Unit,
    onOpenOnlinePage: (OnlineReaderTarget) -> Unit,
    onOpenFavorite: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewerMessage: String? = null,
    onViewerMessageConsumed: () -> Unit = {},
    savedViewModel: SavedViewModel = viewModel(),
    favoritesViewModel: FavoritesViewModel = viewModel(),
) {
    val bookmarks by savedViewModel.bookmarks.collectAsStateWithLifecycle()
    val favorites by favoritesViewModel.favorites.collectAsStateWithLifecycle()
    val onlineBookmarks = savedViewModel.onlineBookmarks
    val onlineFavorites = savedViewModel.onlineFavorites
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(BOOKMARKS_TAB_INDEX) }
    var sourceChanged by remember { mutableStateOf<BookmarkResolution.SourceChanged?>(null) }

    LifecycleResumeEffect(savedViewModel) {
        savedViewModel.refreshOnlineRecords()
        onPauseOrDispose {}
    }

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
                    val result =
                        snackbarHostState.showSnackbar(
                            message = "已移除书签",
                            actionLabel = "撤销",
                            duration = SnackbarDuration.Long,
                        )
                    if (result == SnackbarResult.ActionPerformed) {
                        savedViewModel.restore(event.bookmark)
                    }
                }

                is SavedEvent.OnlineBookmarkRemoved -> {
                    val result =
                        snackbarHostState.showSnackbar(
                            message = "已移除书签",
                            actionLabel = "撤销",
                            duration = SnackbarDuration.Long,
                        )
                    if (result == SnackbarResult.ActionPerformed) {
                        savedViewModel.restoreOnlineBookmark(event.bookmark)
                    }
                }

                is SavedEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("已保存") },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            scrolledContainerColor = MaterialTheme.colorScheme.background,
                        ),
                )
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
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
            BOOKMARKS_TAB_INDEX ->
                BookmarksContent(
                    bookmarks = bookmarks,
                    onlineBookmarks = onlineBookmarks,
                    thumbnailStates = savedViewModel.thumbnailStates,
                    onLoadThumbnail = savedViewModel::loadThumbnail,
                    onRemove = savedViewModel::remove,
                    onOpen = { bookmark ->
                        savedViewModel.resolve(bookmark) { resolution ->
                            when (resolution) {
                                is BookmarkResolution.Ready ->
                                    onOpenBookmark(
                                        resolution.comicId,
                                        resolution.globalPage,
                                    )

                                is BookmarkResolution.SourceChanged -> sourceChanged = resolution

                                is BookmarkResolution.Unavailable -> {
                                    // Route the transient failure through the ViewModel event
                                    // stream.
                                    savedViewModel.showMessage(resolution.message)
                                }
                            }
                        }
                    },
                    onOpenOnline = { item ->
                        if (item.availability.canOpenReader()) {
                            onOpenOnlinePage(item.target)
                        } else {
                            savedViewModel.showMessage(item.availability.displayLabel())
                        }
                    },
                    onRemoveOnline = savedViewModel::removeOnlineBookmark,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )

            else ->
                FavoritesContent(
                    favorites = favorites,
                    onlineFavorites = onlineFavorites,
                    onOpenFavorite = onOpenFavorite,
                    onOpenOnlineFavorite = { item ->
                        if (item.availability.canOpenReader()) {
                            onOpenOnlinePage(item.target)
                        } else {
                            savedViewModel.showMessage(item.availability.displayLabel())
                        }
                    },
                    onRemoveOnlineFavorite = savedViewModel::removeOnlineFavorite,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
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
                    }
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
    onlineBookmarks: List<OnlineSavedBookmarkUi>?,
    thumbnailStates: Map<Long, BookmarkThumbnailState>,
    onLoadThumbnail: (BookmarkEntity) -> Unit,
    onRemove: (BookmarkEntity) -> Unit,
    onOpen: (BookmarkEntity) -> Unit,
    onOpenOnline: (OnlineSavedBookmarkUi) -> Unit,
    onRemoveOnline: (OnlineSavedBookmarkUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        bookmarks == null && onlineBookmarks == null -> Box(modifier = modifier)

        bookmarks.orEmpty().isEmpty() && onlineBookmarks.orEmpty().isEmpty() ->
            EmptyBookmarks(modifier = modifier)

        else -> {
            val groups = remember(bookmarks) { groupAndSortBookmarks(bookmarks.orEmpty()) }
            val onlineGroups =
                remember(onlineBookmarks) {
                    groupAndSortOnlineBookmarks(onlineBookmarks.orEmpty())
                }
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
                onlineGroups.forEach { group ->
                    item(key = "online-header-${group.key}") {
                        OnlineBookmarkGroupHeader(group)
                    }
                    items(group.bookmarks, key = { it.key }) { item ->
                        OnlineBookmarkRow(
                            item = item,
                            onClick = { onOpenOnline(item) },
                            onRemove = { onRemoveOnline(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineBookmarkGroupHeader(group: OnlineSavedBookmarkGroup) {
    val unavailable = group.bookmarks.none { it.availability.canOpenReader() }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (unavailable) "来源不可用" else "${group.bookmarks.size} 个书签",
            style = MaterialTheme.typography.labelMedium,
            color =
                if (unavailable) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OnlineBookmarkRow(
    item: OnlineSavedBookmarkUi,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val unavailable = !item.availability.canOpenReader()
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OnlineBookmarkThumbnail(
            item = item,
            modifier =
                Modifier.width(56.dp).aspectRatio(2f / 3f).alpha(if (unavailable) 0.55f else 1f),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f).alpha(if (unavailable) 0.7f else 1f),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${item.chapterTitle} · 第 ${item.pageIndex + 1} 页",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unavailable) {
                Text(
                    text = item.availability.displayLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Box(modifier = Modifier.align(Alignment.Bottom)) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "书签操作",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.offset(x = 8.dp, y = 2.dp)
                        .clip(CircleShape)
                        .clickable { showMenu = true }
                        .padding(4.dp)
                        .size(16.dp),
            )
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("跳转到此页") },
                    enabled = !unavailable,
                    onClick = {
                        showMenu = false
                        onClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text("取消书签") },
                    onClick = {
                        showMenu = false
                        onRemove()
                    },
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 84.dp))
}

@Composable
private fun OnlineBookmarkThumbnail(
    item: OnlineSavedBookmarkUi,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val request =
        remember(item.cover) {
            item.cover?.let { cover ->
                ImageRequest.Builder(context)
                    .data(cover.url)
                    .apply {
                        cover.headers.forEach { (name, value) -> setHeader(name, value) }
                        cover.referer?.let { setHeader("Referer", it) }
                    }
                    .build()
            }
        }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier.clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = item.title.take(1),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BookmarkGroupHeader(group: SavedBookmarkGroup) {
    val missing = group.bookmarks.first().isMissing
    Row(
        modifier =
            Modifier.fillMaxWidth()
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
            color =
                if (missing) {
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
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BookmarkThumbnail(
            item = item,
            state = thumbnailState,
            modifier =
                Modifier.width(56.dp).aspectRatio(2f / 3f).alpha(if (item.isMissing) 0.55f else 1f),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f).alpha(if (item.isMissing) 0.55f else 1f),
        ) {
            Text(
                text = item.comicTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = bookmarkLocationLabel(item.bookmark),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(modifier = Modifier.align(Alignment.Bottom)) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "书签操作",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.offset(x = 8.dp, y = 2.dp)
                        .clip(CircleShape)
                        .clickable { showMenu = true }
                        .padding(4.dp)
                        .size(16.dp),
            )
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("跳转到此页") },
                    onClick = {
                        showMenu = false
                        onClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text("取消书签") },
                    onClick = {
                        showMenu = false
                        onRemove()
                    },
                )
            }
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
        modifier =
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is BookmarkThumbnailState.Ready ->
                Image(
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
    val cover =
        remember(item.coverPath) {
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
        Icon(
            painter = painterResource(R.drawable.ic_bookmark_border),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "还没有书签",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "阅读时点顶栏书签图标，即可添加当前页书签。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
