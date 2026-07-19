// Geometry helpers keep OCR hit testing and ordered text selection deterministic and testable.
package com.exio.inkleaf.data.ocr

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

private const val OCR_SPOTLIGHT_OUTSET_RATIO = 0.12f
private const val OCR_SPOTLIGHT_MITER_LIMIT = 4f

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

fun calculateOcrSpotlightOutset(
    shortEdgePx: Float,
    minimumPx: Float,
    maximumPx: Float,
): Float {
    require(shortEdgePx >= 0f)
    require(minimumPx >= 0f && maximumPx >= minimumPx)
    return (shortEdgePx * OCR_SPOTLIGHT_OUTSET_RATIO).coerceIn(minimumPx, maximumPx)
}

fun expandOcrViewportQuad(
    points: List<Offset>,
    outsetPx: Float,
): List<Offset> {
    require(points.size == 4)
    require(outsetPx >= 0f)
    if (outsetPx == 0f) return points
    val signedArea = points.indices.sumOf { index ->
        val current = points[index]
        val next = points[(index + 1) % points.size]
        (current.x * next.y - next.x * current.y).toDouble()
    }.toFloat() / 2f
    if (abs(signedArea) <= 0.0001f) return points

    val edgeDirections = points.indices.map { index ->
        points[(index + 1) % points.size] - points[index]
    }
    if (edgeDirections.any { direction -> direction.getDistance() <= 0.0001f }) return points

    val offsetEdges = points.indices.map { index ->
        val start = points[index]
        val direction = edgeDirections[index]
        val length = direction.getDistance()
        val outwardNormal = if (signedArea > 0f) {
            Offset(direction.y, -direction.x) / length
        } else {
            Offset(-direction.y, direction.x) / length
        }
        OffsetEdge(
            start = start + outwardNormal * outsetPx,
            direction = direction,
            outwardNormal = outwardNormal,
        )
    }
    return points.indices.flatMap { index ->
        val previous = offsetEdges[(index - 1 + points.size) % points.size]
        val current = offsetEdges[index]
        val intersection = intersectLines(previous, current)
        if (
            intersection != null &&
            (intersection - points[index]).getDistance() <= outsetPx * OCR_SPOTLIGHT_MITER_LIMIT
        ) {
            listOf(intersection)
        } else {
            listOf(
                points[index] + previous.outwardNormal * outsetPx,
                points[index] + current.outwardNormal * outsetPx,
            )
        }
    }
}

private data class OffsetEdge(
    val start: Offset,
    val direction: Offset,
    val outwardNormal: Offset,
)

private fun intersectLines(first: OffsetEdge, second: OffsetEdge): Offset? {
    val denominator = first.direction.cross(second.direction)
    if (abs(denominator) <= 0.0001f) return null
    val distance = (second.start - first.start).cross(second.direction) / denominator
    return first.start + first.direction * distance
}

private fun Offset.cross(other: Offset): Float = x * other.y - y * other.x

fun OcrPageResult.spotlightPolygons(): List<List<OcrPoint>> =
    lines.map(OcrTextLine::points).ifEmpty { regions.map(OcrRegion::points) }

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
