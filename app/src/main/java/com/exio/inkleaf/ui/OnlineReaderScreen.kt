package com.exio.inkleaf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.plugin.OnlineAvailability
import com.exio.inkleaf.plugin.PageDescriptor
import com.exio.inkleaf.plugin.PluginPagesRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
@Composable
fun OnlineReaderScreen(
    pluginId: String,
    sourceId: String,
    chapterId: String,
    chapterRevision: String?,
    opaqueContextJson: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as InkleafApplication
    val opaqueContext = remember(opaqueContextJson) {
        opaqueContextJson?.let { runCatching { com.exio.inkleaf.plugin.PluginContentCodec.json.parseToJsonElement(it) }.getOrNull() }
    }
    var pages by remember { mutableStateOf<List<PageDescriptor>>(emptyList()) }
    var resolvedRevision by remember(pluginId, sourceId, chapterId, opaqueContextJson) {
        mutableStateOf(chapterRevision)
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(pluginId, sourceId, chapterId, opaqueContext, reload) {
        loading = true
        errorMessage = null
        try {
            val response = application.pluginCatalog.pages(
                pluginId,
                PluginPagesRequest(sourceId, chapterId, resolvedRevision, opaqueContext),
            )
            pages = response.pages
            resolvedRevision = response.revision ?: chapterRevision
            try {
                withContext(Dispatchers.IO) {
                    application.onlineContentRepository.setAvailability(
                        pluginId,
                        sourceId,
                        OnlineAvailability.AVAILABLE,
                    )
                }
            } catch (storageError: CancellationException) {
                throw storageError
            } catch (_: Exception) {
                // Page delivery remains usable when the optional metadata snapshot is unavailable.
            }
            val saved = try {
                withContext(Dispatchers.IO) {
                    application.onlineContentRepository.get(pluginId, sourceId)?.position
                }
            } catch (storageError: CancellationException) {
                throw storageError
            } catch (_: Exception) {
                null
            }
            val restoredIndex = saved
                ?.takeIf { it.chapterId == chapterId }
                ?.let { position ->
                    position.pageId
                        ?.let { pageId -> pages.indexOfFirst { it.pageId == pageId }.takeIf { it >= 0 } }
                        ?: position.pageIndex.takeIf {
                            position.chapterRevision == resolvedRevision && it in pages.indices
                        }
                }
            if (restoredIndex != null) {
                listState.scrollToItem(restoredIndex)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = error.message ?: "加载页面失败"
            try {
                withContext(Dispatchers.IO) {
                    application.onlineContentRepository.setAvailability(
                        pluginId,
                        sourceId,
                        error.toOnlineAvailability(),
                    )
                }
            } catch (storageError: CancellationException) {
                throw storageError
            } catch (_: Exception) {
                // Preserve the original page-loading error when the metadata file is unavailable.
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(pages, resolvedRevision) {
        if (pages.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .debounce(200L)
            .distinctUntilChanged()
            .collect { index ->
                val page = pages.getOrNull(index) ?: return@collect
                try {
                    withContext(Dispatchers.IO) {
                        application.onlineContentRepository.recordPosition(
                            pluginId,
                            sourceId,
                            chapterId,
                            page.pageId,
                            index,
                            resolvedRevision,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Progress persistence is best effort and must not stop image scrolling.
                }
            }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(chapterId) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            errorMessage != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(requireNotNull(errorMessage), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { reload++ }) { Text("重试") }
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                itemsIndexed(pages, key = { index, page -> page.pageId ?: "$index:${page.url}" }) { _, page ->
                    val request = remember(page) {
                        ImageRequest.Builder(context)
                            .data(page.url)
                            .apply {
                                page.headers.forEach { (name, value) -> setHeader(name, value) }
                                page.referer?.let { setHeader("Referer", it) }
                            }
                            .crossfade(120)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = page.pageId,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                    )
                }
            }
        }
    }
}
