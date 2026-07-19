// Detects strong comic panel borders on a small page preview; OCR geometry handles borderless pages.
package com.exio.inkleaf.data.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val PANEL_PREVIEW_LONG_EDGE = 1024
private const val MIN_PANEL_AREA_RATIO = 0.04
private const val MAX_PANEL_AREA_RATIO = 0.88
private const val MIN_PANEL_WIDTH_RATIO = 0.14
private const val MIN_PANEL_HEIGHT_RATIO = 0.08
private const val PANEL_APPROXIMATION_RATIO = 0.02

internal class ComicPanelPreview(
    private val pageWidth: Int,
    private val pageHeight: Int,
) : AutoCloseable {
    private val scale = min(1f, PANEL_PREVIEW_LONG_EDGE.toFloat() / max(pageWidth, pageHeight))
    private val bitmap = Bitmap.createBitmap(
        (pageWidth * scale).roundToInt().coerceAtLeast(1),
        (pageHeight * scale).roundToInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    ).apply { eraseColor(Color.WHITE) }
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun addTile(tile: Bitmap, bounds: OcrTileBounds) {
        canvas.drawBitmap(
            tile,
            Rect(0, 0, tile.width, tile.height),
            RectF(
                bounds.left * bitmap.width.toFloat() / pageWidth,
                bounds.top * bitmap.height.toFloat() / pageHeight,
                (bounds.left + bounds.width) * bitmap.width.toFloat() / pageWidth,
                (bounds.top + bounds.height) * bitmap.height.toFloat() / pageHeight,
            ),
            paint,
        )
    }

    fun detectPanels(): List<PixelOcrPanel> = detectComicPanels(bitmap, pageWidth, pageHeight)

    override fun close() {
        bitmap.recycle()
    }
}

internal fun detectComicPanels(
    preview: Bitmap,
    pageWidth: Int,
    pageHeight: Int,
): List<PixelOcrPanel> {
    if (preview.width <= 1 || preview.height <= 1) return emptyList()

    val rgba = Mat()
    val gray = Mat()
    val edges = Mat()
    val connectedEdges = Mat()
    val hierarchy = Mat()
    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
    val contours = mutableListOf<MatOfPoint>()
    return try {
        Utils.bitmapToMat(preview, rgba)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)
        Imgproc.Canny(gray, edges, 60.0, 160.0)
        Imgproc.morphologyEx(edges, connectedEdges, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.findContours(
            connectedEdges,
            contours,
            hierarchy,
            Imgproc.RETR_LIST,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )

        val previewArea = preview.width.toDouble() * preview.height
        val scaleX = pageWidth.toFloat() / preview.width
        val scaleY = pageHeight.toFloat() / preview.height
        val candidates = contours.mapNotNull { contour ->
            val contour2f = MatOfPoint2f(*contour.toArray())
            val approximation = MatOfPoint2f()
            try {
                val perimeter = Imgproc.arcLength(contour2f, true)
                Imgproc.approxPolyDP(
                    contour2f,
                    approximation,
                    perimeter * PANEL_APPROXIMATION_RATIO,
                    true,
                )
                if (approximation.total() != 4L) return@mapNotNull null

                val rect = Imgproc.boundingRect(contour)
                val areaRatio = rect.area() / previewArea
                if (areaRatio !in MIN_PANEL_AREA_RATIO..MAX_PANEL_AREA_RATIO) {
                    return@mapNotNull null
                }
                if (
                    rect.width < preview.width * MIN_PANEL_WIDTH_RATIO ||
                    rect.height < preview.height * MIN_PANEL_HEIGHT_RATIO
                ) {
                    return@mapNotNull null
                }
                val rectangularity = Imgproc.contourArea(contour) / rect.area().coerceAtLeast(1.0)
                if (rectangularity < 0.55) return@mapNotNull null

                PixelOcrPanel(
                    id = 0,
                    left = rect.x * scaleX,
                    top = rect.y * scaleY,
                    right = (rect.x + rect.width) * scaleX,
                    bottom = (rect.y + rect.height) * scaleY,
                )
            } finally {
                contour2f.release()
                approximation.release()
            }
        }
        deduplicatePanels(candidates).mapIndexed { id, panel -> panel.copy(id = id) }
    } finally {
        contours.forEach { contour -> contour.release() }
        kernel.release()
        hierarchy.release()
        connectedEdges.release()
        edges.release()
        gray.release()
        rgba.release()
    }
}

private fun deduplicatePanels(candidates: List<PixelOcrPanel>): List<PixelOcrPanel> {
    val accepted = mutableListOf<PixelOcrPanel>()
    candidates.sortedByDescending { panel -> panel.area }.forEach { candidate ->
        if (accepted.none { existing -> intersectionOverUnion(existing, candidate) >= 0.82f }) {
            accepted += candidate
        }
    }
    return accepted
}

private val PixelOcrPanel.area: Float
    get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)

private fun intersectionOverUnion(first: PixelOcrPanel, second: PixelOcrPanel): Float {
    val intersectionWidth = max(0f, min(first.right, second.right) - max(first.left, second.left))
    val intersectionHeight = max(0f, min(first.bottom, second.bottom) - max(first.top, second.top))
    val intersection = intersectionWidth * intersectionHeight
    return intersection / (first.area + second.area - intersection).coerceAtLeast(1f)
}
