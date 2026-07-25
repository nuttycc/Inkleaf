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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.data.ocr.OcrImageLayout
import com.exio.inkleaf.data.ocr.OcrPageResult
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
        modifier =
            modifier
                .clearAndSetSemantics {}
                .drawWithCache {
                    val layout =
                        calculateOcrImageLayout(
                            viewport = IntSize(size.width.toInt(), size.height.toInt()),
                            imageWidth = result.imageWidth,
                            imageHeight = result.imageHeight,
                        )
                    val pagePath =
                        Path().apply {
                            addRect(
                                androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)
                            )
                        }
                    val spotlightPath =
                        Path().apply {
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
                    val backgroundMask =
                        Path.combine(
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
                modifier = Modifier.fillMaxSize().blur(6.dp),
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
    var magnifierCenter by remember(result) { mutableStateOf(Offset.Unspecified) }

    val drawingModifier = Modifier.drawWithCache {
        val layout =
            calculateOcrImageLayout(
                viewport = IntSize(size.width.toInt(), size.height.toInt()),
                imageWidth = result.imageWidth,
                imageHeight = result.imageHeight,
            )
        val visualOutsetPx = with(density) { 1.dp.toPx() }
        val normalStroke =
            Stroke(
                width = with(density) { 1.dp.toPx() },
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
        val selectedGlowStroke =
            Stroke(
                width = with(density) { 3.5.dp.toPx() },
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
        val selectedMainStroke =
            Stroke(
                width = with(density) { 1.8.dp.toPx() },
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
        val selectedInnerLightStroke =
            Stroke(
                width = with(density) { 1.2.dp.toPx() },
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )

        val neonBlueFill = Color(0xFF00E5FF).copy(alpha = 0.38f)
        val neonBlueGlowFill = Color(0xFF00B0FF).copy(alpha = 0.26f)
        val innerLightColor = Color.White.copy(alpha = 0.88f)
        val blueAccent = Color(0xFF00E5FF)

        val regionPaths =
            result.regions.map { region ->
                region to region.points.toViewportPath(layout, visualOutsetPx)
            }
        val selectedRegionPairs = regionPaths.filter { (region, _) -> region.id in selectedIds }
        val selectedPaths = selectedRegionPairs.map { (_, path) -> path }

        val mergedSelectedPath =
            selectedPaths.firstOrNull()?.let { firstPath ->
                selectedPaths.drop(1).fold(firstPath) { mergedPath, path ->
                    Path.combine(PathOperation.Union, mergedPath, path)
                }
            }

        // 按阅读顺序找到首尾选中区域，计算排版方向与手柄位置
        val sortedSelectedRegions =
            result.regions.filter { it.id in selectedIds }.sortedBy { it.readingOrder }
        val startRegion = sortedSelectedRegions.firstOrNull()
        val endRegion = sortedSelectedRegions.lastOrNull()

        val handleRadiusPx = with(density) { 7.dp.toPx() }
        val handleBorderStroke =
            Stroke(
                width = handleRadiusPx * 0.22f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
        val handleShadowColor = Color.Black.copy(alpha = 0.25f)
        val innerDotColor = Color.White

        val startHandleData = startRegion?.let { reg ->
            val points = reg.points.map(layout::pageToViewport)
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val isVertical = (maxX - minX) < (maxY - minY)
            val tipPos = if (isVertical) Offset(maxX, minY) else Offset(minX, minY)
            buildClosedExpressiveHandle(
                tipPosition = tipPos,
                isStart = true,
                isVertical = isVertical,
                handleRadiusPx = handleRadiusPx,
            )
        }

        val endHandleData = endRegion?.let { reg ->
            val points = reg.points.map(layout::pageToViewport)
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val isVertical = (maxX - minX) < (maxY - minY)
            val tipPos = if (isVertical) Offset(minX, maxY) else Offset(maxX, maxY)
            buildClosedExpressiveHandle(
                tipPosition = tipPos,
                isStart = false,
                isVertical = isVertical,
                handleRadiusPx = handleRadiusPx,
            )
        }

        onDrawBehind {
            // 1. 绘制未选中的 OCR 文字气泡边框
            regionPaths.forEach { (region, path) ->
                if (region.id !in selectedIds) {
                    drawPath(
                        path = path,
                        color = Color.White.copy(alpha = 0.45f),
                        style = normalStroke,
                    )
                }
            }

            // 2. 绘制蓝色荧光笔通透高亮 (Neon Cyan Blue)
            mergedSelectedPath?.let { path ->
                // 底层：柔和电蓝色发光
                drawPath(path, color = neonBlueGlowFill, style = Fill)
                // 核心荧光青蓝填充
                drawPath(path, color = neonBlueFill, style = Fill)
                // 3.5dp 外发光边框
                drawPath(
                    path = path,
                    color = blueAccent.copy(alpha = 0.48f),
                    style = selectedGlowStroke,
                )
                // 1.2dp 纯白亮线内衬
                drawPath(
                    path = path,
                    color = innerLightColor,
                    style = selectedInnerLightStroke,
                )
                // 荧光蓝主外框
                drawPath(
                    path = path,
                    color = blueAccent,
                    style = selectedMainStroke,
                )
            }

            // 3. 绘制荧光蓝水滴手柄
            startHandleData?.let { (path, center) ->
                drawPath(path, color = handleShadowColor, style = Fill)
                drawPath(path, color = blueAccent, style = Fill)
                drawPath(path, color = Color.White, style = handleBorderStroke)
                drawCircle(color = innerDotColor, radius = handleRadiusPx * 0.35f, center = center)
            }

            if (endHandleData != null && endHandleData.first != startHandleData?.first) {
                val (path, center) = endHandleData
                drawPath(path, color = handleShadowColor, style = Fill)
                drawPath(path, color = blueAccent, style = Fill)
                drawPath(path, color = Color.White, style = handleBorderStroke)
                drawCircle(color = innerDotColor, radius = handleRadiusPx * 0.35f, center = center)
            }
        }
    }
    Box(
        modifier =
            modifier
                .magnifier(sourceCenter = { magnifierCenter })
                .pointerInput(result) {
                    detectTapGestures { position ->
                        hitTestCharacter(
                                result = result,
                                viewport = size,
                                position = position,
                                expansionPx = with(density) { 24.dp.toPx() },
                            )
                            ?.let { region -> currentOnRegionTapped(region.id) }
                    }
                }
                .pointerInput(result) {
                    var lastAddedId: Int? = null
                    detectDragGesturesAfterLongPress(
                        onDragStart = { position ->
                            magnifierCenter = position
                            lastAddedId =
                                hitTestCharacter(
                                        result = result,
                                        viewport = size,
                                        position = position,
                                        expansionPx = with(density) { 24.dp.toPx() },
                                    )
                                    ?.id
                                    ?.also(currentOnRegionAdded)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            magnifierCenter = change.position
                            val regionId =
                                hitTestCharacter(
                                        result = result,
                                        viewport = size,
                                        position = change.position,
                                        expansionPx = with(density) { 24.dp.toPx() },
                                    )
                                    ?.id
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
                .then(drawingModifier)
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
                    modifier =
                        Modifier.semantics {
                            selected = region.id in selectedIds
                            contentDescription = region.text
                            onClick(label = if (region.id in selectedIds) "取消选择" else "选择文字") {
                                onRegionTapped(region.id)
                                true
                            }
                        }
                )
            }
        },
    ) { measurables, constraints ->
        val imageLayout =
            calculateOcrImageLayout(
                viewport = IntSize(constraints.maxWidth, constraints.maxHeight),
                imageWidth = result.imageWidth,
                imageHeight = result.imageHeight,
            )
        val placeables = measurables.mapIndexed { index, measurable ->
            val region = result.regions[index]
            val points = region.points.map(imageLayout::pageToViewport)
            val width =
                (points.maxOf(Offset::x) - points.minOf(Offset::x))
                    .toInt()
                    .coerceAtLeast(minimumTargetPx)
            val height =
                (points.maxOf(Offset::y) - points.minOf(Offset::y))
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
                    x =
                        (centerX - placeable.width / 2f)
                            .toInt()
                            .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0)),
                    y =
                        (centerY - placeable.height / 2f)
                            .toInt()
                            .coerceIn(
                                0,
                                (constraints.maxHeight - placeable.height).coerceAtLeast(0),
                            ),
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
    val shortestEdgePx =
        mappedPoints.indices.minOf { index ->
            val next = mappedPoints[(index + 1) % mappedPoints.size]
            (next - mappedPoints[index]).getDistance()
        }
    val outsetPx =
        calculateOcrSpotlightOutset(
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
    val layout =
        calculateOcrImageLayout(
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
internal fun ReaderOcrProcessingStatus(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
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
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier =
                Modifier.background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(12.dp))
                    // 手势屏障：吞掉点在栏内空白区的触摸，防止穿透到下层 OCR overlay 误触字符选择。
                    // awaitFirstDown 必须保持默认 requireUnconsumed = true：down 若已被子按钮消费，
                    // 父节点就不能进入消费循环——否则消费 MOVE 会触发 Compose clickable 的 Final pass
                    // 消费检查，把子按钮的点击整个取消掉（issue #11）。
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            var pointerStillPressed: Boolean
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change -> change.consume() }
                                pointerStillPressed = event.changes.any { change -> change.pressed }
                            } while (pointerStillPressed)
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            Text(
                text =
                    if (selectedCount == 0) {
                        "点击字符选择，长按拖动可连续选择"
                    } else {
                        "已选 $selectedCount 字 · $selectedText"
                    },
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.clearAndSetSemantics {
                            contentDescription =
                                if (selectedCount == 0) {
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
                    modifier =
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

private fun buildClosedExpressiveHandle(
    tipPosition: Offset,
    isStart: Boolean,
    isVertical: Boolean,
    handleRadiusPx: Float,
): Pair<Path, Offset> {
    val r = handleRadiusPx
    val pinHeightPx = r * 1.3f

    val bulbCenter =
        if (isStart) {
            if (isVertical) Offset(tipPosition.x + r * 0.5f, tipPosition.y - pinHeightPx)
            else Offset(tipPosition.x - r * 0.5f, tipPosition.y - pinHeightPx)
        } else {
            if (isVertical) Offset(tipPosition.x - r * 0.5f, tipPosition.y + pinHeightPx)
            else Offset(tipPosition.x + r * 0.5f, tipPosition.y + pinHeightPx)
        }

    val path =
        Path().apply {
            addOval(
                androidx.compose.ui.geometry.Rect(
                    bulbCenter.x - r,
                    bulbCenter.y - r,
                    bulbCenter.x + r,
                    bulbCenter.y + r,
                )
            )
            moveTo(bulbCenter.x - r * 0.6f, bulbCenter.y)
            lineTo(tipPosition.x, tipPosition.y)
            lineTo(bulbCenter.x + r * 0.6f, bulbCenter.y)
            close()
        }

    return path to bulbCenter
}
