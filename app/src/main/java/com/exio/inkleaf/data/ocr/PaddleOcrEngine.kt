// Owns the process-local PaddleOCR session and serializes native inference and release operations.
package com.exio.inkleaf.data.ocr

import android.content.Context
import android.graphics.Bitmap
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

    suspend fun recognize(context: Context, bitmap: Bitmap): OcrPageResult = mutex.withLock {
        val ocr = engine ?: create(context).also { engine = it }
        val result = ocr.recognize(bitmap)
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        return@withLock OcrPageResult(
            regions = result.results.mapIndexed { index, item ->
                OcrRegion(
                    id = index,
                    text = item.text,
                    confidence = item.confidence,
                    points = item.box.points.map { point ->
                        OcrPoint(
                            x = (point.x / width).coerceIn(0f, 1f),
                            y = (point.y / height).coerceIn(0f, 1f),
                        )
                    },
                )
            },
            totalTimeMs = result.totalTimeMs,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
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
