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
    fun selectedTextUsesPageReadingOrderWithoutLineBreaks() {
        val regions = listOf(
            rectangleRegion(1, 0f, 0f, 0.2f, 0.2f, "先"),
            rectangleRegion(2, 0.3f, 0.3f, 0.5f, 0.5f, "后"),
        )

        assertEquals("先后", selectedOcrText(regions, setOf(2, 1)))
    }

    @Test
    fun selectedVerticalTextReadsTopToBottomThenRightToLeft() {
        val regions = listOf(
            rectangleRegion(
                id = 1,
                left = 0.7f,
                top = 0.1f,
                right = 0.8f,
                bottom = 0.2f,
                text = "右",
            ),
            rectangleRegion(
                id = 2,
                left = 0.7f,
                top = 0.25f,
                right = 0.8f,
                bottom = 0.35f,
                text = "列",
            ),
            rectangleRegion(
                id = 3,
                left = 0.4f,
                top = 0.1f,
                right = 0.5f,
                bottom = 0.2f,
                text = "左",
            ),
        )

        assertEquals("右列左", selectedOcrText(regions, setOf(3, 2, 1)))
    }

    @Test
    fun expandedHitTestChoosesCharacterNearestTouchPoint() {
        val left = rectangleRegion(1, 0.1f, 0.4f, 0.2f, 0.6f)
        val right = rectangleRegion(2, 0.3f, 0.4f, 0.4f, 0.6f)

        assertEquals(
            2,
            hitTestOcrRegion(
                regions = listOf(left, right),
                point = OcrPoint(0.29f, 0.5f),
                expansionX = 0.12f,
                expansionY = 0.12f,
            )?.id,
        )
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
    fun dragSelectionOnlyAddsCharacters() {
        val state = OcrSelectionSession()
            .enter(page = 1)
            .toggle(regionId = 7)
            .add(regionId = 7)
            .add(regionId = 2)

        assertEquals(listOf(7, 2), state.selectedIds.toList())
    }

    @Test
    fun clearingAfterCopyKeepsOcrOverlayActive() {
        val state = OcrSelectionSession()
            .enter(page = 1)
            .toggle(regionId = 7)
            .clearSelection()

        assertEquals(1, state.activePage)
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test
    fun exitingDropsPendingSelection() {
        val state = OcrSelectionSession()
            .enter(page = 1)
            .toggle(regionId = 7)
            .exit()

        assertEquals(OcrSelectionSession(), state)
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
    fun overlappingTileLineFragmentsKeepUniqueCharactersAndRemoveDuplicates() {
        val firstTile = OcrTileBounds(0, 0, 500, 500)
        val secondTile = OcrTileBounds(300, 0, 500, 500)
        val first = pixelRectangle(300f, 100f, 500f, 180f, 0.9f, firstTile).copy(
            characters = listOf(
                pixelCharacter("A", 320f, 100f, 370f, 180f),
                pixelCharacter("B", 400f, 100f, 450f, 180f),
            ),
        )
        val second = pixelRectangle(400f, 100f, 600f, 180f, 0.8f, secondTile).copy(
            characters = listOf(
                pixelCharacter("B", 402f, 102f, 452f, 182f),
                pixelCharacter("C", 520f, 100f, 570f, 180f),
            ),
        )

        val mergedCharacters = mergeOverlappingOcrRegions(listOf(first, second))
            .flatMap(PixelOcrRegion::characters)

        assertEquals("ABC", mergedCharacters.joinToString("") { it.text })
    }

    @Test
    fun horizontalReadingOrderKeepsJitteredRowLeftToRight() {
        val tile = OcrTileBounds(0, 0, 1000, 1000)
        val right = pixelRectangle(400f, 100f, 600f, 160f, 0.9f, tile)
        val left = pixelRectangle(100f, 104f, 300f, 164f, 0.9f, tile)

        assertEquals(
            listOf(left, right),
            sortPixelOcrRegionsInReadingOrder(listOf(right, left)),
        )
    }

    @Test
    fun verticalReadingOrderKeepsColumnsRightToLeft() {
        val tile = OcrTileBounds(0, 0, 1000, 1000)
        val left = pixelRectangle(
            100f, 50f, 160f, 350f, 0.9f, tile, isVertical = true,
        )
        val right = pixelRectangle(
            400f, 120f, 460f, 420f, 0.9f, tile, isVertical = true,
        )

        assertEquals(
            listOf(right, left),
            sortPixelOcrRegionsInReadingOrder(listOf(left, right)),
        )
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
        isVertical: Boolean = false,
    ) = PixelOcrRegion(
        confidence = confidence,
        points = listOf(
            OcrPoint(left, top),
            OcrPoint(right, top),
            OcrPoint(right, bottom),
            OcrPoint(left, bottom),
        ),
        sourceTile = tile,
        characters = listOf(pixelCharacter("text", left, top, right, bottom)),
        isVertical = isVertical,
    )

    private fun pixelCharacter(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = PixelOcrCharacter(
        text = text,
        confidence = 0.9f,
        points = listOf(
            OcrPoint(left, top),
            OcrPoint(right, top),
            OcrPoint(right, bottom),
            OcrPoint(left, bottom),
        ),
    )
}
