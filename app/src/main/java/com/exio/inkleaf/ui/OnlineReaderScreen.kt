package com.exio.inkleaf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.plugin.OnlineAvailability
import com.exio.inkleaf.plugin.PageDescriptor
import com.exio.inkleaf.plugin.PluginPagesRequest
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val application = context.applicationContext as InkleafApplication
    val coroutineScope = rememberCoroutineScope()
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
    var showControls by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val currentPageIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }

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

    LaunchedEffect(pluginId, sourceId, chapterId, pages, resolvedRevision) {
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

    // Explicit Reader Black #000000 Background Container
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        showControls = !showControls
                    }
                )
            },
    ) {
        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            errorMessage != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(requireNotNull(errorMessage), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { reload++ }) { Text("重试") }
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp),
                    )
                }
            }
        }

        // Top Translucent Overlay Control Bar with 12dp Rounded Corners
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xCC121212),
                contentColor = Color.White,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable(enabled = true, onClick = {}),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = chapterId,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (pages.isNotEmpty()) {
                        Text(
                            text = "${pages.size}P",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }

        // Bottom Translucent Overlay Control Bar with 12dp Rounded Corners
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xCC121212),
                contentColor = Color.White,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable(enabled = true, onClick = {}),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (pages.size > 1) {
                        var isDraggingSlider by remember { mutableStateOf(false) }
                        var sliderPosition by remember {
                            mutableFloatStateOf((currentPageIndex + 1).toFloat())
                        }

                        LaunchedEffect(currentPageIndex) {
                            if (!isDraggingSlider) {
                                sliderPosition = (currentPageIndex + 1).toFloat()
                            }
                        }

                        Slider(
                            value = sliderPosition,
                            onValueChange = { newValue ->
                                isDraggingSlider = true
                                sliderPosition = newValue
                                val targetIndex = (newValue.roundToInt() - 1).coerceIn(0, pages.size - 1)
                                coroutineScope.launch {
                                    listState.scrollToItem(targetIndex)
                                }
                            },
                            onValueChangeFinished = {
                                isDraggingSlider = false
                                sliderPosition = (currentPageIndex + 1).toFloat()
                            },
                            valueRange = 1f..pages.size.toFloat(),
                            steps = if (pages.size > 2) pages.size - 2 else 0,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { onPrevChapter?.invoke() },
                            enabled = onPrevChapter != null,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "上一章",
                                tint = if (onPrevChapter != null) Color.White else Color.White.copy(alpha = 0.3f),
                            )
                        }

                        Text(
                            text = if (pages.isNotEmpty()) "Page ${currentPageIndex + 1} / ${pages.size}" else "Page 0 / 0",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )

                        IconButton(
                            onClick = { onNextChapter?.invoke() },
                            enabled = onNextChapter != null,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "下一章",
                                tint = if (onNextChapter != null) Color.White else Color.White.copy(alpha = 0.3f),
                            )
                        }
                    }
                }
            }
        }
    }
}

