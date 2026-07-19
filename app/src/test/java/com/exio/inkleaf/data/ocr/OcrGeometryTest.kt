package com.exio.inkleaf.data.ocr

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrGeometryTest {
    @Test
    fun fitLayoutCentersPortraitPageInsideWideViewport() {
        val layout = calculateOcrImageLayout(
            viewport = IntSize(1200, 800),
            imageWidth = 600,
            imageHeight = 800,
        )

        assertEquals(300f, layout.rect.left, 0.01f)
        assertEquals(0f, layout.rect.top, 0.01f)
        assertEquals(900f, layout.rect.right, 0.01f)
        assertEquals(800f, layout.rect.bottom, 0.01f)
        assertEquals(Offset(600f, 400f), layout.pageToViewport(OcrPoint(0.5f, 0.5f)))
    }

    @Test
    fun polygonHitTestHandlesRotatedRegion() {
        val region = OcrRegion(
            id = 1,
            text = "text",
            confidence = 0.9f,
            points = listOf(
                OcrPoint(0.2f, 0.1f),
                OcrPoint(0.8f, 0.2f),
                OcrPoint(0.7f, 0.6f),
                OcrPoint(0.1f, 0.5f),
            ),
        )

        assertTrue(region.contains(OcrPoint(0.45f, 0.35f)))
        assertFalse(region.contains(OcrPoint(0.95f, 0.95f)))
    }

    @Test
    fun hitTestPrefersSmallestOverlappingRegion() {
        val large = rectangleRegion(1, 0.1f, 0.1f, 0.9f, 0.9f)
        val small = rectangleRegion(2, 0.4f, 0.4f, 0.6f, 0.6f)

        assertEquals(
            2,
            hitTestOcrRegion(listOf(large, small), OcrPoint(0.5f, 0.5f))?.id,
        )
    }

    @Test
    fun selectedTextUsesClickOrder() {
        val regions = listOf(
            rectangleRegion(1, 0f, 0f, 0.2f, 0.2f, "first"),
            rectangleRegion(2, 0.3f, 0.3f, 0.5f, 0.5f, "second"),
        )

        assertEquals("second\nfirst", selectedOcrText(regions, listOf(2, 1)))
    }

    @Test
    fun pageChangeClearsSelectionAndDetailState() {
        val state = OcrSelectionSession()
            .enter(page = 3)
            .toggle(regionId = 9)
            .showText("selected")

        assertEquals(OcrSelectionSession(), state.onPageChanged(page = 4))
    }

    @Test
    fun selectionSessionPreservesTapOrder() {
        val state = OcrSelectionSession()
            .enter(page = 1)
            .toggle(regionId = 7)
            .toggle(regionId = 2)

        assertEquals(listOf(7, 2), state.selectedIds.toList())
    }

    @Test
    fun tilingCoversPageAndKeepsOverlap() {
        val tiles = calculateOcrTiles(pageWidth = 3000, pageHeight = 2000)

        assertTrue(tiles.size > 1)
        assertEquals(0, tiles.first().left)
        assertEquals(0, tiles.first().top)
        assertTrue(tiles.any { it.left + it.width == 3000 })
        assertTrue(tiles.any { it.top + it.height == 2000 })
    }

    @Test
    fun overlappingTileResultsAreMerged() {
        val firstTile = OcrTileBounds(0, 0, 500, 500)
        val secondTile = OcrTileBounds(300, 0, 500, 500)
        val first = pixelRectangle(350f, 100f, 480f, 180f, confidence = 0.8f, tile = firstTile)
        val duplicate = pixelRectangle(352f, 103f, 482f, 182f, confidence = 0.9f, tile = secondTile)
        val separate = pixelRectangle(600f, 400f, 760f, 480f, confidence = 0.7f, tile = secondTile)

        val merged = mergeOverlappingOcrRegions(listOf(first, duplicate, separate))

        assertEquals(2, merged.size)
        assertTrue(merged.contains(duplicate))
        assertTrue(merged.contains(separate))
    }

    @Test
    fun overlappingResultsFromSameTileAreNotMerged() {
        val tile = OcrTileBounds(0, 0, 500, 500)
        val first = pixelRectangle(100f, 100f, 300f, 180f, 0.8f, tile)
        val second = pixelRectangle(105f, 103f, 302f, 182f, 0.9f, tile)

        assertEquals(2, mergeOverlappingOcrRegions(listOf(first, second)).size)
    }

    @Test
    fun exifRotationMapsOrientedTileBackToEncodedPixels() {
        val tile = OcrTileBounds(left = 10, top = 20, width = 30, height = 40)

        assertEquals(
            OcrPixelRect(left = 20, top = 60, right = 60, bottom = 90),
            mapOrientedRectToSource(tile, ExifInterface.ORIENTATION_ROTATE_90, 200, 100),
        )
        assertEquals(
            OcrPixelRect(left = 140, top = 10, right = 180, bottom = 40),
            mapOrientedRectToSource(tile, ExifInterface.ORIENTATION_ROTATE_270, 200, 100),
        )
    }

    private fun rectangleRegion(
        id: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        text: String = "text",
    ) = OcrRegion(
        id = id,
        text = text,
        confidence = 0.9f,
        points = listOf(
            OcrPoint(left, top),
            OcrPoint(right, top),
            OcrPoint(right, bottom),
            OcrPoint(left, bottom),
        ),
    )

    private fun pixelRectangle(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        confidence: Float,
        tile: OcrTileBounds,
    ) = PixelOcrRegion(
        text = "text",
        confidence = confidence,
        points = listOf(
            OcrPoint(left, top),
            OcrPoint(right, top),
            OcrPoint(right, bottom),
            OcrPoint(left, bottom),
        ),
        sourceTile = tile,
    )
}
