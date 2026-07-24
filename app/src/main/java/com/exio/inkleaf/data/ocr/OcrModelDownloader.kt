// 模型下载器：断点续传、进度上报、SHA256 校验。
package com.exio.inkleaf.data.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal data class OcrDownloadProgress(
    /** 已下载总字节数。 */
    val downloadedBytes: Long,
    /** 总字节数。 */
    val totalBytes: Long,
    /** 当前正在下载的文件名。 */
    val currentFileName: String,
)

internal class OcrDownloadException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal class OcrModelDownloader(
    private val client: OkHttpClient,
) {
    /**
     * 下载所有模型文件到 [modelDir]，通过 Flow 上报进度。
     * 每个文件失败自动重试 1 次（Range 续传），仍失败则抛 [OcrDownloadException]。
     */
    fun download(
        source: OcrModelSource,
        modelDir: File,
    ): Flow<OcrDownloadProgress> = flow {
        var downloadedTotal = 0L

        for (spec in OCR_MODEL_FILES) {
            val targetFile = File(modelDir, spec.relativePath)
            targetFile.parentFile?.mkdirs()

            // 已存在且大小正确则跳过
            if (targetFile.exists() && targetFile.length() == spec.sizeBytes) {
                downloadedTotal += spec.sizeBytes
                emit(OcrDownloadProgress(downloadedTotal, OCR_MODEL_TOTAL_BYTES, spec.relativePath))
                continue
            }

            val partialFile = File(modelDir, "${spec.relativePath}.partial")
            partialFile.parentFile?.mkdirs()

            var lastError: Exception? = null
            var success = false

            // 最多尝试 2 次（首次 + 重试 1 次）
            for (attempt in 0..1) {
                try {
                    downloadFile(source, spec, partialFile)
                    verifySha256(partialFile, spec.sha256)
                    // 校验通过，重命名为最终文件
                    if (targetFile.exists()) targetFile.delete()
                    partialFile.renameTo(targetFile)
                    success = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    // 重试时保留 .partial 继续续传
                }
            }

            if (!success) {
                throw OcrDownloadException(
                    "下载失败: ${spec.relativePath}",
                    lastError,
                )
            }

            downloadedTotal += spec.sizeBytes
            emit(OcrDownloadProgress(downloadedTotal, OCR_MODEL_TOTAL_BYTES, spec.relativePath))
        }

        // 全部完成，写入版本标记
        File(modelDir, ".version").writeText(OCR_MODEL_VERSION)
    }.flowOn(Dispatchers.IO)

    private fun downloadFile(
        source: OcrModelSource,
        spec: OcrModelFileSpec,
        partialFile: File,
    ) {
        val remoteRef = spec.remoteRef()
        val url = source.resolveUrl(remoteRef.repo, remoteRef.fileName)
        val existingBytes = if (partialFile.exists()) partialFile.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            when {
                response.code == 206 -> {
                    // 续传成功，追加写入
                }
                response.isSuccessful -> {
                    // 服务端不支持 Range 或返回完整文件，从头写
                    if (existingBytes > 0) partialFile.delete()
                }
                else -> throw OcrDownloadException(
                    "HTTP ${response.code}: ${spec.relativePath}",
                )
            }

            val body = response.body
                ?: throw OcrDownloadException("空响应体: ${spec.relativePath}")

            val append = response.code == 206
            FileOutputStream(partialFile, append).use { fos ->
                body.byteStream().use { input ->
                    input.copyTo(fos)
                }
            }
        }
    }

    private fun verifySha256(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw OcrDownloadException(
                "校验失败: ${file.name} (期望 ${expected.take(12)}…, 实际 ${actual.take(12)}…)",
            )
        }
    }
}
