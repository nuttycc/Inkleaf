package com.exio.inkleaf.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import com.exio.inkleaf.R
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpenSession: (comicId: Long, page: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = viewModel(),
) {
    val items = viewModel.timeline.collectAsLazyPagingItems()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    var clearInProgress by remember { mutableStateOf(false) }
    var sourceChanged by remember {
        mutableStateOf<HistoryEvent.ConfirmSourceChanged?>(null)
    }
    var pendingDelete by remember {
        mutableStateOf<HistoryEvent.SessionDeleted?>(null)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HistoryEvent.SessionDeleted -> {
                    pendingDelete = event
                    val result = snackbarHostState.showSnackbar(
                        message = "已删除阅读记录",
                        actionLabel = "撤销",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.restoreSession(event.snapshot)
                    }
                    if (pendingDelete?.snapshot?.id == event.snapshot.id) {
                        pendingDelete = null
                    }
                }
                is HistoryEvent.Message -> snackbarHostState.showSnackbar(event.text)
                is HistoryEvent.NavigateToReader -> onOpenSession(event.comicId, event.page)
                is HistoryEvent.ConfirmSourceChanged -> sourceChanged = event
            }
        }
    }

    val refresh = items.loadState.refresh
    val hasItems = items.itemCount > 0
    val showEmpty = refresh is LoadState.NotLoading && !hasItems
    val showInitialSkeleton = refresh is LoadState.Loading && !hasItems

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("历史") },
                actions = {
                    if (hasItems) {
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("清空历史") },
                                    onClick = {
                                        menuOpen = false
                                        showClearConfirm = true
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            showInitialSkeleton -> HistorySkeletonList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            refresh is LoadState.Error && !hasItems -> HistoryError(
                message = "无法加载阅读历史",
                onRetry = { items.retry() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            showEmpty -> HistoryEmpty(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            else -> HistoryTimelineList(
                items = items,
                resolvingSessionId = viewModel.resolvingSessionId,
                onOpen = viewModel::continueReading,
                onDelete = { viewModel.deleteSession(it.id) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = {
                if (!clearInProgress) showClearConfirm = false
            },
            title = { Text("清空阅读历史？") },
            text = {
                Text(
                    "所有阅读会话记录都会被删除，且无法撤销。" +
                        "书架、阅读进度和已保存内容不会受到影响。",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !clearInProgress,
                    onClick = {
                        if (clearInProgress) return@TextButton
                        clearInProgress = true
                        // Cancel any pending single-delete undo.
                        pendingDelete = null
                        viewModel.clearHistory()
                        showClearConfirm = false
                        clearInProgress = false
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(
                    enabled = !clearInProgress,
                    onClick = { showClearConfirm = false },
                ) { Text("取消") }
            },
        )
    }

    sourceChanged?.let { pending ->
        val body = buildString {
            append("漫画内容在此次阅读后发生了变化。可以从当前最接近的页面继续，但位置可能不完全一致。")
            pending.locationLabel?.let { append("\n将打开：$it") }
        }
        AlertDialog(
            onDismissRequest = { sourceChanged = null },
            title = { Text("漫画内容已变化") },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        sourceChanged = null
                        onOpenSession(pending.comicId, pending.approximatePage)
                    },
                ) { Text("仍然继续") }
            },
            dismissButton = {
                TextButton(onClick = { sourceChanged = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HistoryTimelineList(
    items: LazyPagingItems<HistoryListItem>,
    resolvingSessionId: String?,
    onOpen: (HistorySessionUi) -> Unit,
    onDelete: (HistorySessionUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(
            count = items.itemCount,
            key = items.itemKey { it.stableKey },
        ) { index ->
            when (val item = items[index]) {
                is HistoryListItem.DateHeader -> HistoryDateHeader(item.label)
                is HistoryListItem.Session -> {
                    HistorySessionRow(
                        session = item.row,
                        resolving = resolvingSessionId == item.row.id,
                        onOpen = { onOpen(item.row) },
                        onDelete = { onDelete(item.row) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 84.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
                null -> HistorySessionSkeletonRow()
            }
        }
        when (items.loadState.append) {
            is LoadState.Loading -> item(key = "append-skeleton") {
                HistorySessionSkeletonRow()
            }
            is LoadState.Error -> item(key = "append-error") {
                HistoryError(
                    message = "无法加载更多记录",
                    onRetry = { items.retry() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun HistoryDateHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun HistorySessionRow(
    session: HistorySessionUi,
    resolving: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val unavailable = !session.contentAvailable
    val rowDescription = buildString {
        append(session.title)
        append("，")
        append(session.endLocationLabel)
        if (unavailable) append("，内容不可用")
    }
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (unavailable) {
                    Modifier.semantics { contentDescription = rowDescription }
                } else {
                    Modifier
                        .clickable(enabled = !resolving, onClick = onOpen)
                        .semantics { contentDescription = rowDescription }
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HistoryCover(
            coverPath = session.coverPath,
            unavailable = unavailable,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (unavailable) 0.8f else 1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = session.endLocationLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${session.timeRangeLabel} · ${session.durationLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (unavailable) {
                Text(
                    text = "内容不可用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (resolving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "会话操作")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("删除记录") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryCover(
    coverPath: String?,
    unavailable: Boolean,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .width(56.dp)
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .alpha(if (unavailable) 0.55f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        if (!coverPath.isNullOrBlank() && File(coverPath).isFile) {
            AsyncImage(
                model = File(coverPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_image),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryEmpty(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_history),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("还没有阅读历史", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "打开书架中的漫画开始阅读，阅读记录会显示在这里。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun HistorySkeletonList(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = 8.dp)) {
        repeat(3) { HistorySessionSkeletonRow() }
    }
}

@Composable
private fun HistorySessionSkeletonRow() {
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(surface),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(surface),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(surface),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(surface),
            )
        }
    }
}
