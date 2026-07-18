package com.exio.inkleaf.data.ocr

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
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
}
