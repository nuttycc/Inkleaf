// Keeps detector input scale stable by splitting source-quality pages and merging overlap results.
package com.exio.inkleaf.data.ocr

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal const val OCR_TILE_SIZE = 1536
internal const val OCR_TILE_OVERLAP = 192

internal data class OcrTileBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal data class PixelOcrRegion(
    val confidence: Float,
    val points: List<OcrPoint>,
    val sourceTile: OcrTileBounds,
    val characters: List<PixelOcrCharacter> = emptyList(),
    val isVertical: Boolean = false,
)

internal data class PixelOcrCharacter(
    val text: String,
    val confidence: Float,
    val points: List<OcrPoint>,
)

internal data class PixelOcrPanel(
    val id: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal data class OrderedPixelOcrPanel(
    val panel: PixelOcrPanel,
    val readingOrder: Int,
)

internal data class OrderedPixelOcrLine(
    val region: PixelOcrRegion,
    val panelId: Int?,
    val readingOrder: Int,
)

internal data class OrderedPixelOcrLayout(
    val panels: List<OrderedPixelOcrPanel>,
    val lines: List<OrderedPixelOcrLine>,
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
    regions
        .sortedWith(
            compareByDescending<PixelOcrRegion> { distanceFromTileEdge(it) }
                .thenByDescending { it.characters.size }
                .thenByDescending { it.confidence }
        )
        .forEach { candidate ->
            if (accepted.none { existing -> candidateDuplicatesExisting(existing, candidate) }) {
                accepted += candidate
            }
        }
    return sortPixelOcrRegionsInReadingOrder(deduplicateCharacters(accepted))
}

private fun candidateDuplicatesExisting(
    existing: PixelOcrRegion,
    candidate: PixelOcrRegion,
): Boolean =
    samePhysicalRegion(existing, candidate) &&
        candidate.characters.isNotEmpty() &&
        candidate.characters.all { candidateCharacter ->
            existing.characters.any { existingCharacter ->
                samePhysicalCharacter(existingCharacter, candidateCharacter)
            }
        }

private fun deduplicateCharacters(regions: List<PixelOcrRegion>): List<PixelOcrRegion> {
    val acceptedCharacters = mutableListOf<Pair<OcrTileBounds, PixelOcrCharacter>>()
    return regions.mapNotNull { region ->
        val uniqueCharacters =
            region.characters.filter { candidate ->
                acceptedCharacters.none { (sourceTile, existing) ->
                    sourceTile != region.sourceTile &&
                        tilesOverlap(sourceTile, region.sourceTile) &&
                        samePhysicalCharacter(existing, candidate)
                }
            }
        uniqueCharacters.forEach { character ->
            acceptedCharacters += region.sourceTile to character
        }
        region.copy(characters = uniqueCharacters).takeIf { uniqueCharacters.isNotEmpty() }
    }
}

private fun samePhysicalCharacter(
    first: PixelOcrCharacter,
    second: PixelOcrCharacter,
): Boolean {
    val a = boundsOf(first)
    val b = boundsOf(second)
    val intersectionWidth = max(0f, min(a.right, b.right) - max(a.left, b.left))
    val intersectionHeight = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
    val intersection = intersectionWidth * intersectionHeight
    if (intersection <= 0f) return false

    val overlapOfSmaller = intersection / min(a.area, b.area).coerceAtLeast(1f)
    val centerDistance = hypot(a.centerX - b.centerX, a.centerY - b.centerY)
    val maxDimension = max(max(a.width, a.height), max(b.width, b.height)).coerceAtLeast(1f)
    return overlapOfSmaller >= 0.45f && centerDistance <= maxDimension * 0.4f
}

internal fun sortPixelOcrRegionsInReadingOrder(
    regions: List<PixelOcrRegion>
): List<PixelOcrRegion> = orderPixelOcrLayout(regions).lines.map(OrderedPixelOcrLine::region)

internal fun orderPixelOcrLayout(
    regions: List<PixelOcrRegion>,
    panels: List<PixelOcrPanel> = emptyList(),
): OrderedPixelOcrLayout {
    if (regions.isEmpty()) return OrderedPixelOcrLayout(emptyList(), emptyList())

    val verticalCharacterCount =
        regions.filter(PixelOcrRegion::isVertical).sumOf { region ->
            region.characters.size.coerceAtLeast(1)
        }
    val horizontalCharacterCount =
        regions.filterNot(PixelOcrRegion::isVertical).sumOf { region ->
            region.characters.size.coerceAtLeast(1)
        }
    val isVerticalPage = verticalCharacterCount > horizontalCharacterCount
    val minimumLineGap =
        regions
            .map { region -> boundsOf(region).shortEdge }
            .sorted()
            .let { edges -> edges[edges.size / 2] * 0.75f }
            .coerceAtLeast(1f)

    val panelAssignments =
        regions
            .mapNotNull { region ->
                bestPanelFor(boundsOf(region), panels)?.let { panel -> panel to region }
            }
            .groupBy(
                keySelector = { (panel, _) -> panel },
                valueTransform = { (_, region) -> region },
            )
    val detectedPanelGroups = panels.mapNotNull { panel ->
        panelAssignments[panel]
            ?.takeIf { lines -> lines.isNotEmpty() }
            ?.let { lines ->
                LayoutGroup(panel = panel, regions = lines, bounds = boundsOf(panel))
            }
    }
    val detectedCoverage = detectedPanelGroups.flatMap(LayoutGroup::regions).distinct().size
    val panelGroups =
        detectedPanelGroups
            .takeIf { groups ->
                groups.size >= 2 && detectedCoverage * 2 >= regions.size
            }
            .orEmpty()
    val assignedRegions = panelGroups.flatMap(LayoutGroup::regions).toSet()
    val unassignedGroups =
        regions.filterNot(assignedRegions::contains).map { region ->
            LayoutGroup(panel = null, regions = listOf(region), bounds = boundsOf(region))
        }
    val groups = panelGroups + unassignedGroups
    val orderedGroups =
        orderLayoutEntries(
            entries = groups.map { group -> LayoutEntry(group.bounds, group) },
            isVerticalPage = isVerticalPage,
            minimumGap = if (panelGroups.isEmpty()) minimumLineGap else 1f,
        )
    val orderedPanels =
        orderedGroups.mapNotNull(LayoutGroup::panel).distinctBy(PixelOcrPanel::id).mapIndexed {
            readingOrder,
            panel ->
            OrderedPixelOcrPanel(panel = panel, readingOrder = readingOrder)
        }
    val orderedLines =
        orderedGroups
            .flatMap { group ->
                orderLayoutEntries(
                        entries =
                            group.regions.map { region -> LayoutEntry(boundsOf(region), region) },
                        isVerticalPage = isVerticalPage,
                        minimumGap = minimumLineGap,
                    )
                    .map { region -> group.panel?.id to region }
            }
            .mapIndexed { readingOrder, (panelId, region) ->
                OrderedPixelOcrLine(
                    region = region,
                    panelId = panelId,
                    readingOrder = readingOrder,
                )
            }
    return OrderedPixelOcrLayout(
        panels = orderedPanels,
        lines = orderedLines,
    )
}

private data class LayoutGroup(
    val panel: PixelOcrPanel?,
    val regions: List<PixelOcrRegion>,
    val bounds: RegionBounds,
)

private data class LayoutEntry<T>(
    val bounds: RegionBounds,
    val value: T,
)

private fun <T> orderLayoutEntries(
    entries: List<LayoutEntry<T>>,
    isVerticalPage: Boolean,
    minimumGap: Float,
): List<T> {
    if (entries.size <= 1) return entries.map { entry -> entry.value }

    splitAtLargestGap(
            entries = entries,
            minimumGap = minimumGap,
            start = { entry -> entry.bounds.top },
            end = { entry -> entry.bounds.bottom },
        )
        ?.let { (top, bottom) ->
            return orderLayoutEntries(top, isVerticalPage, minimumGap) +
                orderLayoutEntries(bottom, isVerticalPage, minimumGap)
        }

    splitAtLargestGap(
            entries = entries,
            minimumGap = minimumGap,
            start = { entry -> entry.bounds.left },
            end = { entry -> entry.bounds.right },
        )
        ?.let { (left, right) ->
            return if (isVerticalPage) {
                orderLayoutEntries(right, isVerticalPage, minimumGap) +
                    orderLayoutEntries(left, isVerticalPage, minimumGap)
            } else {
                orderLayoutEntries(left, isVerticalPage, minimumGap) +
                    orderLayoutEntries(right, isVerticalPage, minimumGap)
            }
        }

    val comparator =
        if (isVerticalPage) {
            compareByDescending<LayoutEntry<T>> { entry -> entry.bounds.centerX }
                .thenBy { entry -> entry.bounds.top }
        } else {
            compareBy<LayoutEntry<T>> { entry -> entry.bounds.top }
                .thenBy { entry -> entry.bounds.left }
        }
    return entries.sortedWith(comparator).map { entry -> entry.value }
}

private fun <T> splitAtLargestGap(
    entries: List<LayoutEntry<T>>,
    minimumGap: Float,
    start: (LayoutEntry<T>) -> Float,
    end: (LayoutEntry<T>) -> Float,
): Pair<List<LayoutEntry<T>>, List<LayoutEntry<T>>>? {
    val sorted = entries.sortedBy(start)
    var furthestEnd = end(sorted.first())
    var bestIndex = -1
    var bestGap = minimumGap
    for (index in 1 until sorted.size) {
        val gap = start(sorted[index]) - furthestEnd
        if (gap >= bestGap) {
            bestGap = gap
            bestIndex = index
        }
        furthestEnd = max(furthestEnd, end(sorted[index]))
    }
    if (bestIndex < 0) return null
    return sorted.subList(0, bestIndex) to sorted.subList(bestIndex, sorted.size)
}

private fun bestPanelFor(
    region: RegionBounds,
    panels: List<PixelOcrPanel>,
): PixelOcrPanel? {
    val containing = panels.filter { panel ->
        boundsOf(panel).contains(region.centerX, region.centerY)
    }
    if (containing.isNotEmpty()) return containing.minByOrNull { panel -> boundsOf(panel).area }

    return panels
        .map { panel ->
            panel to boundsOf(panel).intersectionArea(region) / region.area.coerceAtLeast(1f)
        }
        .filter { (_, overlap) -> overlap >= 0.5f }
        .maxByOrNull { (_, overlap) -> overlap }
        ?.first
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
    if (
        first.sourceTile == second.sourceTile ||
            !tilesOverlap(
                first.sourceTile,
                second.sourceTile,
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
    val centerDistance =
        hypot(
            ((a.left + a.right) - (b.left + b.right)) / 2f,
            ((a.top + a.bottom) - (b.top + b.bottom)) / 2f,
        )
    val maxDimension =
        max(
                max(a.right - a.left, a.bottom - a.top),
                max(b.right - b.left, b.bottom - b.top),
            )
            .coerceAtLeast(1f)
    return centerDistance <= maxDimension * 0.3f && (overlapOfSmaller >= 0.72f || iou >= 0.45f)
}

private fun tilesOverlap(first: OcrTileBounds, second: OcrTileBounds): Boolean =
    first.left < second.left + second.width &&
        second.left < first.left + first.width &&
        first.top < second.top + second.height &&
        second.top < first.top + first.height

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
    val width: Float
        get() = (right - left).coerceAtLeast(0f)

    val height: Float
        get() = (bottom - top).coerceAtLeast(0f)

    val area: Float
        get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)

    val shortEdge: Float
        get() = min(width, height)

    val centerX: Float
        get() = (left + right) / 2f

    val centerY: Float
        get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun intersectionArea(other: RegionBounds): Float =
        max(0f, min(right, other.right) - max(left, other.left)) *
            max(0f, min(bottom, other.bottom) - max(top, other.top))
}

private fun boundsOf(region: PixelOcrRegion): RegionBounds =
    RegionBounds(
        left = region.points.minOf { it.x },
        top = region.points.minOf { it.y },
        right = region.points.maxOf { it.x },
        bottom = region.points.maxOf { it.y },
    )

private fun boundsOf(character: PixelOcrCharacter): RegionBounds =
    RegionBounds(
        left = character.points.minOf { it.x },
        top = character.points.minOf { it.y },
        right = character.points.maxOf { it.x },
        bottom = character.points.maxOf { it.y },
    )

private fun boundsOf(panel: PixelOcrPanel): RegionBounds =
    RegionBounds(
        left = panel.left,
        top = panel.top,
        right = panel.right,
        bottom = panel.bottom,
    )
