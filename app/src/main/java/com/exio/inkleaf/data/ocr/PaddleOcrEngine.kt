// Owns the process-local PaddleOCR session and serializes native inference and release operations.
package com.exio.inkleaf.data.ocr

import android.content.Context
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRTextOrientation
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object PaddleOcrEngine {
    private data class BundledModel(
        val detAssetPath: String,
        val recAssetPath: String,
        val recConfigAssetPath: String,
    )

    private val model = BundledModel(
        detAssetPath = "ocr/ppocrv6_small/det/inference.onnx",
        recAssetPath = "ocr/ppocrv6_small/rec/inference.onnx",
        recConfigAssetPath = "ocr/ppocrv6_small/rec/inference.yml",
    )

    private val mutex = Mutex()
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: PaddleOCR? = null

    internal suspend fun recognize(context: Context, source: OcrPageSource): OcrPageResult =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val ocr = engine ?: create(context).also { engine = it }
                val width = source.width.toFloat()
                val height = source.height.toFloat()
                val pixelRegions = mutableListOf<PixelOcrRegion>()
                val panelPreview = ComicPanelPreview(source.width, source.height)
                var totalTimeMs = 0L

                try {
                    val tiles = calculateOcrTiles(source.width, source.height)
                    tiles.forEach { tileBounds ->
                        val tile = source.load(tileBounds)
                        try {
                            panelPreview.addTile(tile, tileBounds)
                            val tileResult = ocr.recognize(tile)
                            totalTimeMs += tileResult.totalTimeMs
                            pixelRegions += tileResult.results.map { item ->
                                PixelOcrRegion(
                                    confidence = item.confidence,
                                    points = item.box.points.map { point ->
                                        OcrPoint(
                                            x = point.x + tileBounds.left,
                                            y = point.y + tileBounds.top,
                                        )
                                    },
                                    sourceTile = tileBounds,
                                    characters = item.characters.map { character ->
                                        PixelOcrCharacter(
                                            text = character.text,
                                            confidence = character.confidence,
                                            points = character.box.points.map { point ->
                                                OcrPoint(
                                                    x = point.x + tileBounds.left,
                                                    y = point.y + tileBounds.top,
                                                )
                                            },
                                        )
                                    },
                                    isVertical = item.orientation == OCRTextOrientation.VERTICAL,
                                )
                            }
                        } finally {
                            tile.recycle()
                        }
                    }

                    val mergedRegions = mergeOverlappingOcrRegions(pixelRegions)
                    val detectedPanels = runCatching { panelPreview.detectPanels() }
                        .getOrDefault(emptyList())
                    val layout = orderPixelOcrLayout(
                        regions = mergedRegions,
                        panels = detectedPanels,
                    )
                    var characterReadingOrder = 0
                    return@withLock OcrPageResult(
                        regions = layout.lines.flatMap { line ->
                            line.region.characters.map { character ->
                                val readingOrder = characterReadingOrder++
                                OcrRegion(
                                    id = readingOrder,
                                    text = character.text,
                                    confidence = character.confidence,
                                    points = character.points.normalize(width, height),
                                    panelId = line.panelId,
                                    lineId = line.readingOrder,
                                    readingOrder = readingOrder,
                                )
                            }
                        },
                        totalTimeMs = totalTimeMs,
                        imageWidth = source.width,
                        imageHeight = source.height,
                        panels = layout.panels.map { orderedPanel ->
                            OcrPanel(
                                id = orderedPanel.panel.id,
                                points = orderedPanel.panel.points.normalize(width, height),
                                readingOrder = orderedPanel.readingOrder,
                            )
                        },
                        lines = layout.lines.map { line ->
                            OcrTextLine(
                                points = line.region.points.normalize(width, height),
                                id = line.readingOrder,
                                panelId = line.panelId,
                                readingOrder = line.readingOrder,
                            )
                        },
                        tileCount = tiles.size,
                        rawRegionCount = pixelRegions.size,
                    )
                } finally {
                    panelPreview.close()
                }
            }
        }

    fun releaseWhenIdle() {
        releaseScope.launch {
            mutex.withLock {
                engine?.release()
                engine = null
            }
        }
    }

    private suspend fun create(context: Context): PaddleOCR {
        check(OpenCVUtils.init()) { "OpenCV initialization failed" }
        return PaddleOCR.create(
            context = context.applicationContext,
            config = PaddleOCRConfig(
                charScoreThresh = OCR_CHARACTER_SCORE_THRESHOLD,
                recBatchSize = 1,
            ),
            engineConfig = EngineConfig(numThreads = 4),
            detModelAssetPath = model.detAssetPath,
            recModelAssetPath = model.recAssetPath,
            recConfigAssetPath = model.recConfigAssetPath,
        )
    }

    private fun List<OcrPoint>.normalize(
        width: Float,
        height: Float,
    ): List<OcrPoint> = map { point ->
        OcrPoint(
            x = (point.x / width).coerceIn(0f, 1f),
            y = (point.y / height).coerceIn(0f, 1f),
        )
    }

    private val PixelOcrPanel.points: List<OcrPoint>
        get() = listOf(
            OcrPoint(left, top),
            OcrPoint(right, top),
            OcrPoint(right, bottom),
            OcrPoint(left, bottom),
        )
}

internal const val OCR_CHARACTER_SCORE_THRESHOLD = 0.55f
