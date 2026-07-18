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

data class OcrPageResult(
    val regions: List<OcrRegion>,
    val totalTimeMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
)
