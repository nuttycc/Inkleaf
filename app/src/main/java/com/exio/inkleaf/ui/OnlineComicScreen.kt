package com.exio.inkleaf.ui

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import coil.compose.AsyncImage
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.R
import com.exio.inkleaf.plugin.ChapterSummary
import com.exio.inkleaf.plugin.ComicDetail
import com.exio.inkleaf.plugin.OnlineAvailability
import com.exio.inkleaf.plugin.OnlineUserReference
import com.exio.inkleaf.plugin.PluginChapterRequest
import com.exio.inkleaf.plugin.PluginDetailRequest
import com.exio.inkleaf.plugin.PluginRpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineComicScreen(
    pluginId: String,
    sourceId: String,
    opaqueContextJson: String?,
    onBack: () -> Unit,
    onOpenChapter: (ChapterSummary, JsonElement?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as InkleafApplication
    val coroutineScope = rememberCoroutineScope()
    val opaqueContext =
        remember(opaqueContextJson) {
            opaqueContextJson?.let {
                runCatching {
                        com.exio.inkleaf.plugin.PluginContentCodec.json.parseToJsonElement(it)
                    }
                    .getOrNull()
            }
        }
    var detail by remember { mutableStateOf<ComicDetail?>(null) }
    var chapters by remember { mutableStateOf<List<ChapterSummary>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var isBookmarked by remember { mutableStateOf(false) }
    var sourceName by remember { mutableStateOf(pluginId) }
    val bookmarkMutationMutex = remember { Mutex() }
    var bookmarkMutationVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(pluginId, sourceId, opaqueContext, reload) {
        loading = true
        errorMessage = null
        detail = null
        chapters = emptyList()
        val (loadedSourceName, initiallyBookmarked) =
            withContext(Dispatchers.IO) {
                val installed = application.pluginManager.installed()
                val name =
                    installed.firstOrNull { it.state.pluginId == pluginId }?.manifest?.name
                        ?: pluginId
                val initialRecord = application.onlineContentRepository.get(pluginId, sourceId)
                name to
                    (initialRecord?.references?.contains(OnlineUserReference.BOOKMARK) == true)
            }
        sourceName = loadedSourceName
        isBookmarked = initiallyBookmarked
        try {
            val loadedDetail =
                application.pluginCatalog.detail(
                    pluginId,
                    PluginDetailRequest(sourceId, opaqueContext),
                )
            detail = loadedDetail
            try {
                val storedBookmarkState =
                    withContext(Dispatchers.IO) {
                        val record =
                            application.onlineContentRepository.recordDetail(pluginId, loadedDetail)
                        record.references.contains(OnlineUserReference.BOOKMARK)
                    }
                isBookmarked = storedBookmarkState
            } catch (storageError: CancellationException) {
                throw storageError
            } catch (_: Exception) {
                // Online content remains usable when its optional metadata snapshot cannot be
                // written.
            }
            val loadedChapters =
                application.pluginCatalog.chapters(
                    pluginId,
                    PluginChapterRequest(sourceId, loadedDetail.opaqueContext ?: opaqueContext),
                )
            chapters = loadedChapters.chapters
            try {
                withContext(Dispatchers.IO) {
                    application.onlineContentRepository.recordChapters(pluginId, loadedChapters)
                }
            } catch (storageError: CancellationException) {
                throw storageError
            } catch (_: Exception) {
                // Keep the successfully loaded chapter list visible when snapshot persistence
                // fails.
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = error.message ?: "加载漫画详情失败"
            val snapshot =
                try {
                    withContext(Dispatchers.IO) {
                        application.onlineContentRepository.setAvailability(
                            pluginId,
                            sourceId,
                            error.toOnlineAvailability(),
                        )
                        application.onlineContentRepository.get(pluginId, sourceId)
                    }
                } catch (storageError: CancellationException) {
                    throw storageError
                } catch (_: Exception) {
                    null
                }
            detail = snapshot?.detail
            chapters = snapshot?.chapters.orEmpty()
            if (snapshot != null) {
                isBookmarked = snapshot.references.contains(OnlineUserReference.BOOKMARK)
            }
        } finally {
            loading = false
        }
    }

    val toggleBookmark: () -> Unit = {
        val previousState = isBookmarked
        val nextState = !previousState
        isBookmarked = nextState
        bookmarkMutationVersion += 1
        val mutationVersion = bookmarkMutationVersion
        coroutineScope.launch {
            try {
                bookmarkMutationMutex.withLock {
                    if (mutationVersion != bookmarkMutationVersion) return@withLock
                    withContext(Dispatchers.IO) {
                        application.onlineContentRepository.setReference(
                            pluginId = pluginId,
                            sourceId = sourceId,
                            reference = OnlineUserReference.BOOKMARK,
                            present = nextState,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                if (mutationVersion == bookmarkMutationVersion) {
                    isBookmarked = previousState
                    errorMessage = error.message?.let { "追漫状态保存失败：$it" } ?: "追漫状态保存失败"
                }
            }
        }
    }

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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading && detail == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            detail?.let { comic ->
                // Large Hero Cover Header
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
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
                                        Modifier.fillMaxWidth()
                                            .height(280.dp)
                                            .clip(
                                                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                                            ),
                                )
                            }
                                ?: Box(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .height(200.dp)
                                            .clip(
                                                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                                            ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_file),
                                        contentDescription = null,
                                        tint =
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.5f
                                            ),
                                        modifier = Modifier.size(48.dp),
                                    )
                                }

                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = comic.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        comic.subtitle
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { author ->
                                                Text(
                                                    text = author,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor =
                                                MaterialTheme.colorScheme.onPrimaryContainer,
                                            shape = RoundedCornerShape(6.dp),
                                        ) {
                                            Text(
                                                text = sourceName,
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
                                    }

                                    // Quick Bookmark/Save Action
                                    if (isBookmarked) {
                                        FilledTonalButton(
                                            onClick = toggleBookmark,
                                            contentPadding =
                                                PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_bookmark),
                                                contentDescription = "已追漫",
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("已追漫")
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = toggleBookmark,
                                            contentPadding =
                                                PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                        ) {
                                            Icon(
                                                painter =
                                                    painterResource(R.drawable.ic_bookmark_border),
                                                contentDescription = "追漫",
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("追漫")
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
                                                        Modifier.size(AssistChipDefaults.IconSize),
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

                // Description
                comic.description
                    ?.takeIf { it.isNotBlank() }
                    ?.let { desc ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
            }

            // M3 Error Card
            errorMessage?.let { message ->
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
                                onClick = { reload++ },
                                colors =
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ),
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
                                chapter.opaqueContext ?: detail?.opaqueContext ?: opaqueContext,
                            )
                        },
                        enabled = !loading && chapter.available,
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
            }
        }
    }
}

internal fun Throwable.toOnlineAvailability(): OnlineAvailability {
    val code = (this as? PluginRpcException)?.error?.code
    return when (code) {
        com.exio.inkleaf.plugin.PluginErrorCode.AUTH_REQUIRED -> OnlineAvailability.AUTH_REQUIRED
        com.exio.inkleaf.plugin.PluginErrorCode.PLUGIN_DISABLED ->
            OnlineAvailability.PLUGIN_DISABLED
        com.exio.inkleaf.plugin.PluginErrorCode.NOT_FOUND -> OnlineAvailability.CONTENT_MISSING
        else -> OnlineAvailability.TEMPORARY_ERROR
    }
}
