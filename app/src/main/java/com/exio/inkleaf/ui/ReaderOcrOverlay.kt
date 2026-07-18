// Reader-only OCR chrome: page-space regions scale with artwork while controls stay screen-sized.
package com.exio.inkleaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.data.ocr.OcrPageResult
import com.exio.inkleaf.data.ocr.calculateOcrImageLayout
import com.exio.inkleaf.data.ocr.hitTestOcrRegion

private const val LOW_OCR_CONFIDENCE = 0.55f

@Composable
internal fun ReaderOcrPageOverlay(
    result: OcrPageResult,
    selectedIds: Set<Int>,
    zoomScale: Float,
    accent: Color,
    onRegionTapped: (Int) -> Unit,
    onEmptyTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .semantics { contentDescription = "可选择的识别文字区域" }
            .pointerInput(result) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    val layout = calculateOcrImageLayout(
                        viewport = size,
                        imageWidth = result.imageWidth,
                        imageHeight = result.imageHeight,
                    )
                    if (!layout.rect.contains(up.position)) return@awaitEachGesture
                    val normalized = layout.viewportToNormalized(up.position)
                    val expansionPx = with(density) { 8.dp.toPx() } / zoomScale
                    val region = hitTestOcrRegion(
                        regions = result.regions,
                        point = normalized,
                        expansionX = expansionPx / layout.rect.width,
                        expansionY = expansionPx / layout.rect.height,
                    )
                    if (region != null) onRegionTapped(region.id) else onEmptyTapped()
                }
            }
            .drawWithCache {
                val layout = calculateOcrImageLayout(
                    viewport = IntSize(size.width.toInt(), size.height.toInt()),
                    imageWidth = result.imageWidth,
                    imageHeight = result.imageHeight,
                )
                val regionPaths = result.regions.map { region ->
                    region to Path().apply {
                        region.points.forEachIndexed { index, point ->
                            val mapped = layout.pageToViewport(point)
                            if (index == 0) moveTo(mapped.x, mapped.y) else lineTo(mapped.x, mapped.y)
                        }
                        close()
                    }
                }
                onDrawBehind {
                    val normalStroke = Stroke(width = with(density) { 1.dp.toPx() } / zoomScale)
                    val selectedStroke = Stroke(width = with(density) { 2.dp.toPx() } / zoomScale)
                    val lowConfidenceStroke = Stroke(
                        width = normalStroke.width,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(8f / zoomScale, 8f / zoomScale),
                        ),
                    )
                    regionPaths.forEach { (region, path) ->
                        val selected = region.id in selectedIds
                        val lowConfidence = region.confidence < LOW_OCR_CONFIDENCE
                        val color = when {
                            selected -> accent
                            lowConfidence -> Color.White.copy(alpha = 0.24f)
                            else -> accent.copy(alpha = 0.32f)
                        }
                        if (selected) {
                            drawPath(path, color = accent.copy(alpha = 0.14f), style = Fill)
                        }
                        drawPath(
                            path = path,
                            color = color,
                            style = when {
                                selected -> selectedStroke
                                lowConfidence -> lowConfidenceStroke
                                else -> normalStroke
                            },
                        )
                    }
                }
            },
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
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (selectedCount == 0) {
                    "点击文字区域进行选择"
                } else {
                    selectedText.replace('\n', ' ')
                },
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
