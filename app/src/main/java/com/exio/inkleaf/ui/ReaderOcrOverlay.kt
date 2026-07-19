// Reader-only OCR chrome: page-space regions scale with artwork while controls stay screen-sized.
package com.exio.inkleaf.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.data.ocr.OcrPageResult
import com.exio.inkleaf.data.ocr.OcrImageLayout
import com.exio.inkleaf.data.ocr.OcrPoint
import com.exio.inkleaf.data.ocr.OcrRegion
import com.exio.inkleaf.data.ocr.calculateOcrImageLayout
import com.exio.inkleaf.data.ocr.calculateOcrSpotlightOutset
import com.exio.inkleaf.data.ocr.expandOcrViewportQuad
import com.exio.inkleaf.data.ocr.hitTestOcrRegion
import com.exio.inkleaf.data.ocr.spotlightPolygons

@Composable
internal fun ReaderOcrFocusLayer(
    result: OcrPageResult,
    painter: Painter,
    modifier: Modifier = Modifier,
) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .clearAndSetSemantics { }
            .drawWithCache {
                val layout = calculateOcrImageLayout(
                    viewport = IntSize(size.width.toInt(), size.height.toInt()),
                    imageWidth = result.imageWidth,
                    imageHeight = result.imageHeight,
                )
                val pagePath = Path().apply {
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                }
                val spotlightPath = Path().apply {
                    fillType = PathFillType.NonZero
                    val minimumOutsetPx = with(density) { 2.dp.toPx() }
                    val maximumOutsetPx = with(density) { 6.dp.toPx() }
                    result.spotlightPolygons().forEach { points ->
                        addPath(
                            points.toSpotlightViewportPath(
                                layout = layout,
                                minimumOutsetPx = minimumOutsetPx,
                                maximumOutsetPx = maximumOutsetPx,
                            )
                        )
                    }
                }
                val backgroundMask = Path.combine(
                    PathOperation.Difference,
                    pagePath,
                    spotlightPath,
                )
                onDrawWithContent {
                    val contentDrawScope = this
                    if (canBlur) {
                        clipPath(backgroundMask) { contentDrawScope.drawContent() }
                    }
                    drawPath(backgroundMask, Color.Black.copy(alpha = 0.48f))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (canBlur) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
internal fun ReaderOcrPageOverlay(
    result: OcrPageResult,
    selectedIds: Set<Int>,
    accent: Color,
    onRegionTapped: (Int) -> Unit,
    onRegionAdded: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val currentOnRegionTapped by rememberUpdatedState(onRegionTapped)
    val currentOnRegionAdded by rememberUpdatedState(onRegionAdded)
    val currentSelectedIds = rememberUpdatedState(selectedIds)
    var magnifierCenter by remember(result) { mutableStateOf(Offset.Unspecified) }
    val drawingModifier = remember(result, accent, density) {
        Modifier.drawWithCache {
            val layout = calculateOcrImageLayout(
                viewport = IntSize(size.width.toInt(), size.height.toInt()),
                imageWidth = result.imageWidth,
                imageHeight = result.imageHeight,
            )
            val visualOutsetPx = with(density) { 1.dp.toPx() }
            val normalStroke = Stroke(width = with(density) { 1.dp.toPx() })
            val selectedStroke = Stroke(width = with(density) { 2.dp.toPx() })
            val regionPaths = result.regions.map { region ->
                region to region.points.toViewportPath(layout, visualOutsetPx)
            }
            onDrawBehind {
                regionPaths.forEach { (region, path) ->
                    val selected = region.id in currentSelectedIds.value
                    val color = if (selected) accent else Color.White.copy(alpha = 0.52f)
                    if (selected) {
                        drawPath(path, color = accent.copy(alpha = 0.18f), style = Fill)
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = if (selected) selectedStroke else normalStroke,
                    )
                }
            }
        }
    }
    Box(
        modifier = modifier
            .magnifier(sourceCenter = { magnifierCenter })
            .pointerInput(result) {
                detectTapGestures { position ->
                    hitTestCharacter(
                        result = result,
                        viewport = size,
                        position = position,
                        expansionPx = with(density) { 24.dp.toPx() },
                    )?.let { region -> currentOnRegionTapped(region.id) }
                }
            }
            .pointerInput(result) {
                var lastAddedId: Int? = null
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        magnifierCenter = position
                        lastAddedId = hitTestCharacter(
                            result = result,
                            viewport = size,
                            position = position,
                            expansionPx = with(density) { 24.dp.toPx() },
                        )?.id?.also(currentOnRegionAdded)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        magnifierCenter = change.position
                        val regionId = hitTestCharacter(
                            result = result,
                            viewport = size,
                            position = change.position,
                            expansionPx = with(density) { 24.dp.toPx() },
                        )?.id
                        if (regionId != null && regionId != lastAddedId) {
                            currentOnRegionAdded(regionId)
                            lastAddedId = regionId
                        }
                    },
                    onDragEnd = {
                        magnifierCenter = Offset.Unspecified
                        lastAddedId = null
                    },
                    onDragCancel = {
                        magnifierCenter = Offset.Unspecified
                        lastAddedId = null
                    },
                )
            }
            .then(drawingModifier),
    ) {
        OcrRegionSemantics(
            result = result,
            selectedIds = selectedIds,
            onRegionTapped = currentOnRegionTapped,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun OcrRegionSemantics(
    result: OcrPageResult,
    selectedIds: Set<Int>,
    onRegionTapped: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val minimumTargetPx = with(density) { 48.dp.roundToPx() }
    Layout(
        modifier = modifier,
        content = {
            result.regions.forEach { region ->
                Box(
                    modifier = Modifier.semantics {
                        selected = region.id in selectedIds
                        contentDescription = region.text
                        onClick(label = if (region.id in selectedIds) "取消选择" else "选择文字") {
                            onRegionTapped(region.id)
                            true
                        }
                    },
                )
            }
        },
    ) { measurables, constraints ->
        val imageLayout = calculateOcrImageLayout(
            viewport = IntSize(constraints.maxWidth, constraints.maxHeight),
            imageWidth = result.imageWidth,
            imageHeight = result.imageHeight,
        )
        val placeables = measurables.mapIndexed { index, measurable ->
            val region = result.regions[index]
            val points = region.points.map(imageLayout::pageToViewport)
            val width = (points.maxOf(Offset::x) - points.minOf(Offset::x))
                .toInt()
                .coerceAtLeast(minimumTargetPx)
            val height = (points.maxOf(Offset::y) - points.minOf(Offset::y))
                .toInt()
                .coerceAtLeast(minimumTargetPx)
            measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, height))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val points = result.regions[index].points.map(imageLayout::pageToViewport)
                val centerX = (points.minOf(Offset::x) + points.maxOf(Offset::x)) / 2f
                val centerY = (points.minOf(Offset::y) + points.maxOf(Offset::y)) / 2f
                placeable.place(
                    x = (centerX - placeable.width / 2f).toInt()
                        .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0)),
                    y = (centerY - placeable.height / 2f).toInt()
                        .coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0)),
                )
            }
        }
    }
}

private fun List<OcrPoint>.toViewportPath(
    layout: OcrImageLayout,
    outsetPx: Float = 0f,
): Path = map(layout::pageToViewport).toClosedPath(outsetPx)

private fun List<OcrPoint>.toSpotlightViewportPath(
    layout: OcrImageLayout,
    minimumOutsetPx: Float,
    maximumOutsetPx: Float,
): Path {
    val mappedPoints = map(layout::pageToViewport)
    val shortestEdgePx = mappedPoints.indices.minOf { index ->
        val next = mappedPoints[(index + 1) % mappedPoints.size]
        (next - mappedPoints[index]).getDistance()
    }
    val outsetPx = calculateOcrSpotlightOutset(
        shortEdgePx = shortestEdgePx,
        minimumPx = minimumOutsetPx,
        maximumPx = maximumOutsetPx,
    )
    return mappedPoints.toClosedPath(outsetPx)
}

private fun List<Offset>.toClosedPath(outsetPx: Float = 0f): Path {
    val pathPoints = if (outsetPx > 0f) expandOcrViewportQuad(this, outsetPx) else this
    return Path().apply {
        pathPoints.forEachIndexed { index, point ->
            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
        close()
    }
}

private fun hitTestCharacter(
    result: OcrPageResult,
    viewport: IntSize,
    position: Offset,
    expansionPx: Float,
): OcrRegion? {
    val layout = calculateOcrImageLayout(
        viewport = viewport,
        imageWidth = result.imageWidth,
        imageHeight = result.imageHeight,
    )
    if (
        position.x !in (layout.rect.left - expansionPx)..(layout.rect.right + expansionPx) ||
        position.y !in (layout.rect.top - expansionPx)..(layout.rect.bottom + expansionPx)
    ) {
        return null
    }
    return hitTestOcrRegion(
        regions = result.regions,
        point = layout.viewportToNormalized(position),
        expansionX = expansionPx / layout.rect.width,
        expansionY = expansionPx / layout.rect.height,
    )
}

@Composable
internal fun ReaderOcrProcessingStatus(
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.padding(4.dp),
            color = Color.White,
            strokeWidth = 2.dp,
        )
        Text("正在识别当前页文字…", color = Color.White)
    }
}

@Composable
internal fun ReaderOcrSelectionBar(
    selectedText: String,
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onShowText: () -> Unit,
    onCopy: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        var pointerStillPressed: Boolean
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change -> change.consume() }
                            pointerStillPressed = event.changes.any { change -> change.pressed }
                        } while (pointerStillPressed)
                    }
                }
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (selectedCount == 0) {
                    "点击字符选择，长按拖动可连续选择"
                } else {
                    "已选 $selectedCount 字 · $selectedText"
                },
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clearAndSetSemantics {
                        contentDescription = if (selectedCount == 0) {
                            "未选择字符"
                        } else {
                            "已选 $selectedCount 字"
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSelectAll, enabled = totalCount > 0) {
                    Text(if (selectedCount == totalCount && totalCount > 0) "取消全选" else "全选")
                }
                if (selectedCount > 0) {
                    TextButton(onClick = onShowText) { Text("查看") }
                    TextButton(onClick = onCopy) { Text("复制") }
                }
                TextButton(onClick = onExit) { Text("退出") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderOcrTextSheet(
    text: String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberExpandOnlySheetState(),
    ) {
        SheetColumn(scrollable = true) {
            StandardSheetTitle("识别文字")
            SelectionContainer {
                Text(
                    text = text,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
