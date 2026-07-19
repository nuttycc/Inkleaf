// Geometry helpers keep OCR hit testing and ordered text selection deterministic and testable.
package com.exio.inkleaf.data.ocr

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import kotlin.math.pow

data class OcrImageLayout(
    val rect: Rect,
) {
    fun pageToViewport(point: OcrPoint): Offset = Offset(
        x = rect.left + point.x * rect.width,
        y = rect.top + point.y * rect.height,
    )

    fun viewportToNormalized(point: Offset): OcrPoint = OcrPoint(
        x = ((point.x - rect.left) / rect.width).coerceIn(0f, 1f),
        y = ((point.y - rect.top) / rect.height).coerceIn(0f, 1f),
    )
}

fun calculateOcrImageLayout(
    viewport: IntSize,
    imageWidth: Int,
    imageHeight: Int,
): OcrImageLayout {
    require(viewport.width > 0 && viewport.height > 0)
    require(imageWidth > 0 && imageHeight > 0)
    val scale = min(
        viewport.width.toFloat() / imageWidth,
        viewport.height.toFloat() / imageHeight,
    )
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (viewport.width - width) / 2f
    val top = (viewport.height - height) / 2f
    return OcrImageLayout(Rect(left, top, left + width, top + height))
}

fun OcrRegion.contains(point: OcrPoint): Boolean {
    var inside = false
    var previous = points.last()
    for (current in points) {
        val crosses = (current.y > point.y) != (previous.y > point.y)
        if (crosses) {
            val edgeX = (previous.x - current.x) * (point.y - current.y) /
                    (previous.y - current.y) + current.x
            if (point.x < edgeX) inside = !inside
        }
        previous = current
    }
    return inside
}

fun hitTestOcrRegion(
    regions: List<OcrRegion>,
    point: OcrPoint,
    expansionX: Float = 0f,
    expansionY: Float = 0f,
): OcrRegion? = regions
    .asSequence()
    .filter { region ->
        region.contains(point) || region.points.let { points ->
            point.x in (points.minOf(OcrPoint::x) - expansionX)..
                    (points.maxOf(OcrPoint::x) + expansionX) &&
                    point.y in (points.minOf(OcrPoint::y) - expansionY)..
                    (points.maxOf(OcrPoint::y) + expansionY)
        }
    }
    .minWithOrNull(
        compareBy<OcrRegion> { region ->
            val centerX = region.points.sumOf { it.x.toDouble() }.toFloat() / region.points.size
            val centerY = region.points.sumOf { it.y.toDouble() }.toFloat() / region.points.size
            val scaleX = expansionX.coerceAtLeast(0.0001f)
            val scaleY = expansionY.coerceAtLeast(0.0001f)
            ((point.x - centerX) / scaleX).pow(2) +
                    ((point.y - centerY) / scaleY).pow(2)
        }.thenBy { region ->
            val width = region.points.maxOf(OcrPoint::x) - region.points.minOf(OcrPoint::x)
            val height = region.points.maxOf(OcrPoint::y) - region.points.minOf(OcrPoint::y)
            width * height
        }
    )

fun selectedOcrText(
    regions: List<OcrRegion>,
    selectedIds: Set<Int>,
): String {
    return regions
        .asSequence()
        .filter { region -> region.id in selectedIds }
        .map(OcrRegion::text)
        .filter(String::isNotBlank)
        .joinToString("")
}
