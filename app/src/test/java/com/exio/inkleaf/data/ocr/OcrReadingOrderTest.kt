package com.exio.inkleaf.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrReadingOrderTest {
    private val tile = OcrTileBounds(0, 0, 1000, 1200)

    @Test
    fun verticalComicReadsUpperPanelRowBeforeLowerRightPanel() {
        val upperRight = verticalLine("上右", 700f, 100f, 760f, 400f)
        val upperLeft = verticalLine("上左", 250f, 120f, 310f, 420f)
        val lowerRight = verticalLine("下右", 800f, 720f, 860f, 980f)

        assertEquals(
            listOf(upperRight, upperLeft, lowerRight),
            sortPixelOcrRegionsInReadingOrder(listOf(lowerRight, upperLeft, upperRight)),
        )
    }

    @Test
    fun detectedPanelsAreOrderedBeforeTheirContainedText() {
        val upperRight = verticalLine("上右", 700f, 100f, 760f, 400f)
        val upperLeft = verticalLine("上左", 250f, 120f, 310f, 420f)
        val lowerRight = verticalLine("下右", 800f, 720f, 860f, 980f)
        val panels =
            listOf(
                PixelOcrPanel(10, 520f, 20f, 980f, 520f),
                PixelOcrPanel(11, 20f, 20f, 500f, 520f),
                PixelOcrPanel(12, 20f, 560f, 980f, 1150f),
            )

        val layout =
            orderPixelOcrLayout(
                regions = listOf(lowerRight, upperLeft, upperRight),
                panels = panels,
            )

        assertEquals(listOf(10, 11, 12), layout.panels.map { it.panel.id })
        assertEquals(
            listOf(upperRight, upperLeft, lowerRight),
            layout.lines.map(OrderedPixelOcrLine::region),
        )
        assertEquals(listOf(10, 11, 12), layout.lines.map(OrderedPixelOcrLine::panelId))
    }

    @Test
    fun horizontalComicReadsPanelRowLeftToRight() {
        val upperLeft = horizontalLine("上左", 80f, 100f, 380f, 160f)
        val upperRight = horizontalLine("上右", 600f, 120f, 900f, 180f)
        val lowerLeft = horizontalLine("下左", 100f, 700f, 400f, 760f)

        assertEquals(
            listOf(upperLeft, upperRight, lowerLeft),
            sortPixelOcrRegionsInReadingOrder(listOf(lowerLeft, upperRight, upperLeft)),
        )
    }

    private fun verticalLine(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): PixelOcrRegion = line(text, left, top, right, bottom, isVertical = true)

    private fun horizontalLine(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): PixelOcrRegion = line(text, left, top, right, bottom, isVertical = false)

    private fun line(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        isVertical: Boolean,
    ): PixelOcrRegion =
        PixelOcrRegion(
            confidence = 0.9f,
            points = rectangle(left, top, right, bottom),
            sourceTile = tile,
            characters =
                listOf(
                    PixelOcrCharacter(
                        text = text,
                        confidence = 0.9f,
                        points = rectangle(left, top, right, bottom),
                    )
                ),
            isVertical = isVertical,
        )

    private fun rectangle(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): List<OcrPoint> =
        listOf(
            OcrPoint(left, top),
            OcrPoint(right, top),
            OcrPoint(right, bottom),
            OcrPoint(left, bottom),
        )
}
