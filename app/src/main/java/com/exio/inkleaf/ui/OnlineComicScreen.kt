package com.exio.inkleaf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.plugin.ChapterSummary
import com.exio.inkleaf.plugin.ComicDetail
import com.exio.inkleaf.plugin.OnlineAvailability
import com.exio.inkleaf.plugin.PluginChapterRequest
import com.exio.inkleaf.plugin.PluginDetailRequest
import com.exio.inkleaf.plugin.PluginRpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

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
    val opaqueContext = remember(opaqueContextJson) {
        opaqueContextJson?.let { runCatching { com.exio.inkleaf.plugin.PluginContentCodec.json.parseToJsonElement(it) }.getOrNull() }
    }
    var detail by remember { mutableStateOf<ComicDetail?>(null) }
    var chapters by remember { mutableStateOf<List<ChapterSummary>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(pluginId, sourceId, opaqueContext, reload) {
        loading = true
        errorMessage = null
        chapters = emptyList()
        try {
            val loadedDetail = application.pluginCatalog.detail(
                pluginId,
                PluginDetailRequest(sourceId, opaqueContext),
            )
            detail = loadedDetail
            try {
                withContext(Dispatchers.IO) {
                    application.onlineContentRepository.recordDetail(pluginId, loadedDetail)
                }
            } catch (storageError: CancellationException) {
                throw storageError
            } catch (_: Exception) {
                // Online content remains usable when its optional metadata snapshot cannot be written.
            }
            val loadedChapters = application.pluginCatalog.chapters(
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
                // Keep the successfully loaded chapter list visible when snapshot persistence fails.
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = error.message ?: "加载漫画详情失败"
            val snapshot = try {
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
        } finally {
            loading = false
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) {
                item { CircularProgressIndicator() }
            }
            detail?.let { comic ->
                comic.cover?.let { cover ->
                    item {
                        val request = remember(cover) {
                            ImageRequest.Builder(application)
                                .data(cover.url)
                                .apply {
                                    cover.headers.forEach { (name, value) -> setHeader(name, value) }
                                    cover.referer?.let { setHeader("Referer", it) }
                                }
                                .crossfade(120)
                                .build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = comic.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(240.dp),
                        )
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(comic.title, style = MaterialTheme.typography.headlineSmall)
                        comic.subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        comic.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            errorMessage?.let { message ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { reload++ }) { Text("重试") }
                    }
                }
            }
            if (chapters.isNotEmpty()) {
                item { Text("章节", style = MaterialTheme.typography.titleMedium) }
                items(chapters, key = { it.chapterId }) { chapter ->
                    TextButton(
                        onClick = {
                            onOpenChapter(chapter, chapter.opaqueContext ?: detail?.opaqueContext ?: opaqueContext)
                        },
                        enabled = !loading && chapter.available && errorMessage == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(chapter.title, modifier = Modifier.fillMaxWidth())
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
        com.exio.inkleaf.plugin.PluginErrorCode.PLUGIN_DISABLED -> OnlineAvailability.PLUGIN_DISABLED
        com.exio.inkleaf.plugin.PluginErrorCode.NOT_FOUND -> OnlineAvailability.CONTENT_MISSING
        else -> OnlineAvailability.TEMPORARY_ERROR
    }
}
