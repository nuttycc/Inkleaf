// 并行测速选择最快下载源。
package com.exio.inkleaf.data.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal class OcrSourceSelector(
    private val client: OkHttpClient,
) {
    @Volatile
    private var cached: OcrModelSource? = null

    /**
     * 并行对所有候选源发 Range GET（前 64KB），测实际吞吐，选最快的。
     * 全部超时则返回 null。结果缓存在进程生命周期内。
     */
    suspend fun selectBestSource(): OcrModelSource? {
        cached?.let { return it }
        val best = withContext(Dispatchers.IO) {
            coroutineScope {
                // 用 det 模型做测速目标（中等大小，有代表性）
                val probeRepo = "PP-OCRv6_small_det_onnx"
                val probeFile = "inference.onnx"
                val probeBytes = 64 * 1024L

                OCR_MODEL_SOURCES.map { source ->
                    async {
                        val url = source.resolveUrl(probeRepo, probeFile)
                        val result = measureSource(url, probeBytes)
                        if (result != null) source to result else null
                    }
                }.awaitAll()
                    .filterNotNull()
                    .maxByOrNull { (_, bytesPerSec) -> bytesPerSec }
                    ?.first
            }
        }
        cached = best
        return best
    }

    fun invalidate() {
        cached = null
    }

    /**
     * 对单个源发 Range GET，返回吞吐（bytes/sec），超时或失败返回 null。
     */
    private fun measureSource(url: String, bytes: Long): Long? {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${bytes - 1}")
            .build()
        val start = System.nanoTime()
        return try {
            // 单独用短超时 client 做探测，不影响下载 client
            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) return null
                val body = response.body ?: return null
                val sink = okio.Buffer()
                val read = body.source().use { source ->
                    var total = 0L
                    while (total < bytes) {
                        val n = source.read(sink, 8192)
                        if (n == -1L) break
                        sink.clear()
                        total += n
                    }
                    total
                }
                if (read == 0L) return null
                val elapsedNs = System.nanoTime() - start
                val elapsedSec = elapsedNs / 1_000_000_000.0
                (read / elapsedSec).toLong()
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val probeClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
