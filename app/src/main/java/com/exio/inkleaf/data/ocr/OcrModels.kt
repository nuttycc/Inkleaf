// App-owned OCR results use normalized page coordinates so UI rendering is resolution-independent.
package com.exio.inkleaf.data.ocr

data class OcrPoint(
    val x: Float,
    val y: Float,
)

data class OcrRegion(
    val id: Int,
    val text: String,
    val confidence: Float,
    val points: List<OcrPoint>,
) {
    init {
        require(points.size == 4)
    }
}

// Detector line boxes stay separate from CTC character boxes because CTC timing is accurate enough
// for hit testing but too tight and approximate to define the visible spotlight boundary.
data class OcrTextLine(
    val points: List<OcrPoint>,
) {
    init {
        require(points.size == 4)
    }
}

data class OcrPageResult(
    val regions: List<OcrRegion>,
    val totalTimeMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val lines: List<OcrTextLine> = emptyList(),
    val tileCount: Int = 1,
    val rawRegionCount: Int = regions.size,
)
