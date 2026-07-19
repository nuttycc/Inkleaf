// Keeps detector input scale stable by splitting source-quality pages and merging overlap results.
package com.exio.inkleaf.data.ocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.hypot

internal const val OCR_TILE_SIZE = 1536
internal const val OCR_TILE_OVERLAP = 192

internal data class OcrTileBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal data class PixelOcrRegion(
    val text: String,
    val confidence: Float,
    val points: List<OcrPoint>,
    val sourceTile: OcrTileBounds,
)

internal fun calculateOcrTiles(
    pageWidth: Int,
    pageHeight: Int,
    tileSize: Int = OCR_TILE_SIZE,
    overlap: Int = OCR_TILE_OVERLAP,
): List<OcrTileBounds> {
    require(pageWidth > 0 && pageHeight > 0)
    require(tileSize > 0)
    require(overlap in 0 until tileSize)

    val xs = tileStarts(pageWidth, tileSize, overlap)
    val ys = tileStarts(pageHeight, tileSize, overlap)
    return ys.flatMap { top ->
        xs.map { left ->
            OcrTileBounds(
                left = left,
                top = top,
                width = min(tileSize, pageWidth - left),
                height = min(tileSize, pageHeight - top),
            )
        }
    }
}

internal fun mergeOverlappingOcrRegions(regions: List<PixelOcrRegion>): List<PixelOcrRegion> {
    val accepted = mutableListOf<PixelOcrRegion>()
    regions.sortedWith(
        compareByDescending<PixelOcrRegion> { distanceFromTileEdge(it) }
            .thenByDescending { it.confidence }
    ).forEach { candidate ->
        if (accepted.none { existing -> samePhysicalRegion(existing, candidate) }) {
            accepted += candidate
        }
    }
    return accepted.sortedWith(compareBy({ boundsOf(it).top }, { boundsOf(it).left }))
}

private fun tileStarts(length: Int, tileSize: Int, overlap: Int): List<Int> {
    if (length <= tileSize) return listOf(0)
    val step = tileSize - overlap
    val starts = mutableListOf<Int>()
    var start = 0
    while (true) {
        starts += start
        if (start + tileSize >= length) break
        start = min(start + step, length - tileSize)
    }
    return starts.distinct()
}

private fun samePhysicalRegion(first: PixelOcrRegion, second: PixelOcrRegion): Boolean {
    if (first.sourceTile == second.sourceTile || !tilesOverlap(
            first.sourceTile,
            second.sourceTile
        )
    ) {
        return false
    }
    val a = boundsOf(first)
    val b = boundsOf(second)
    val intersectionWidth = max(0f, min(a.right, b.right) - max(a.left, b.left))
    val intersectionHeight = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
    val intersection = intersectionWidth * intersectionHeight
    if (intersection <= 0f) return false

    val smallerArea = min(a.area, b.area)
    val union = a.area + b.area - intersection
    val overlapOfSmaller = intersection / smallerArea.coerceAtLeast(1f)
    val iou = intersection / union.coerceAtLeast(1f)
    val centerDistance = hypot(
        ((a.left + a.right) - (b.left + b.right)) / 2f,
        ((a.top + a.bottom) - (b.top + b.bottom)) / 2f,
    )
    val maxDimension = max(
        max(a.right - a.left, a.bottom - a.top),
        max(b.right - b.left, b.bottom - b.top),
    ).coerceAtLeast(1f)
    return centerDistance <= maxDimension * 0.3f && (overlapOfSmaller >= 0.72f || iou >= 0.45f)
}

private fun tilesOverlap(first: OcrTileBounds, second: OcrTileBounds): Boolean =
    first.left < second.left + second.width && second.left < first.left + first.width &&
            first.top < second.top + second.height && second.top < first.top + first.height

private fun distanceFromTileEdge(region: PixelOcrRegion): Float {
    val bounds = boundsOf(region)
    val tile = region.sourceTile
    return minOf(
        bounds.left - tile.left,
        bounds.top - tile.top,
        tile.left + tile.width - bounds.right,
        tile.top + tile.height - bounds.bottom,
    )
}

private data class RegionBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val area: Float get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
}

private fun boundsOf(region: PixelOcrRegion): RegionBounds = RegionBounds(
    left = region.points.minOf { it.x },
    top = region.points.minOf { it.y },
    right = region.points.maxOf { it.x },
    bottom = region.points.maxOf { it.y },
)
