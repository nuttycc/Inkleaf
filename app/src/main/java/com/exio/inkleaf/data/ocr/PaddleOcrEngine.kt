// Owns the process-local PaddleOCR session and serializes native inference and release operations.
package com.exio.inkleaf.data.ocr

import android.content.Context
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        mutex.withLock {
        val ocr = engine ?: create(context).also { engine = it }
            val width = source.width.toFloat()
            val height = source.height.toFloat()
            val pixelRegions = mutableListOf<PixelOcrRegion>()
            var totalTimeMs = 0L

            val tiles = calculateOcrTiles(source.width, source.height)
            tiles.forEach { tileBounds ->
                val tile = source.load(tileBounds)
                try {
                    val tileResult = ocr.recognize(tile)
                    totalTimeMs += tileResult.totalTimeMs
                    pixelRegions += tileResult.results.map { item ->
                        PixelOcrRegion(
                            text = item.text,
                            confidence = item.confidence,
                            points = item.box.points.map { point ->
                                OcrPoint(
                                    x = point.x + tileBounds.left,
                                    y = point.y + tileBounds.top,
                                )
                            },
                            sourceTile = tileBounds,
                        )
                    }
                } finally {
                    tile.recycle()
                }
            }

            val mergedRegions = mergeOverlappingOcrRegions(pixelRegions)
        return@withLock OcrPageResult(
            regions = mergedRegions.mapIndexed { index, item ->
                OcrRegion(
                    id = index,
                    text = item.text,
                    confidence = item.confidence,
                    points = item.points.map { point ->
                        OcrPoint(
                            x = (point.x / width).coerceIn(0f, 1f),
                            y = (point.y / height).coerceIn(0f, 1f),
                        )
                    },
                )
            },
            totalTimeMs = totalTimeMs,
            imageWidth = source.width,
            imageHeight = source.height,
            tileCount = tiles.size,
            rawRegionCount = pixelRegions.size,
        )
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
            config = PaddleOCRConfig(recBatchSize = 1),
            engineConfig = EngineConfig(numThreads = 4),
            detModelAssetPath = model.detAssetPath,
            recModelAssetPath = model.recAssetPath,
            recConfigAssetPath = model.recConfigAssetPath,
        )
    }
}
