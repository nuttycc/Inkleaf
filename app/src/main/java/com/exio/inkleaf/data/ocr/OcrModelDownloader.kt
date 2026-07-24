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
     * 单个文件下载过程中按 256KB 粒度上报进度，避免大文件期间进度条长时间不动。
     */
    fun download(
        source: OcrModelSource,
        modelDir: File,
    ): Flow<OcrDownloadProgress> = flow {
        var downloadedTotal = 0L

        for (spec in OCR_MODEL_FILES) {
            val targetFile = File(modelDir, spec.relativePath)
            targetFile.parentFile?.mkdirs()

            // 已存在且大小匹配，再做 SHA-256 校验，通过则跳过；校验失败视作损坏重下。
            if (targetFile.exists() && targetFile.length() == spec.sizeBytes) {
                if (verifySha256(targetFile, spec.sha256)) {
                    downloadedTotal += spec.sizeBytes
                    emit(OcrDownloadProgress(downloadedTotal, OCR_MODEL_TOTAL_BYTES, spec.relativePath))
                    continue
                }
                // 大小对但内容损坏，删掉重下
                targetFile.delete()
            }

            val partialFile = File(modelDir, "${spec.relativePath}.partial")
            partialFile.parentFile?.mkdirs()

            var lastError: Exception? = null
            var success = false

            // 最多尝试 2 次（首次 + 重试 1 次）
            for (attempt in 0..1) {
                try {
                    downloadFile(source, spec, partialFile) { bytesSoFar ->
                        emit(
                            OcrDownloadProgress(
                                downloadedBytes = downloadedTotal + bytesSoFar,
                                totalBytes = OCR_MODEL_TOTAL_BYTES,
                                currentFileName = spec.relativePath,
                            )
                        )
                    }
                    if (!verifySha256(partialFile, spec.sha256)) {
                        partialFile.delete()
                        throw OcrDownloadException(
                            "校验失败: ${spec.relativePath} (期望 ${spec.sha256.take(12)}…)",
                        )
                    }
                    if (targetFile.exists()) targetFile.delete()
                    if (!partialFile.renameTo(targetFile)) {
                        throw OcrDownloadException(
                            "无法重命名 ${partialFile.name} -> ${targetFile.name}",
                        )
                    }
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

    /**
     * 下载单个文件到 [partialFile]（支持 Range 续传）。
     * [onProgress] 在写入过程中以约 256KB 粒度回调，参数为本轮已写入字节数（含续传起始偏移）。
     */
    private suspend fun downloadFile(
        source: OcrModelSource,
        spec: OcrModelFileSpec,
        partialFile: File,
        onProgress: suspend (bytesSoFar: Long) -> Unit,
    ) {
        val url = source.resolveUrl(spec.repo, spec.fileName)
        val existingBytes = if (partialFile.exists()) partialFile.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val append: Boolean
            when {
                response.code == 206 -> {
                    // 续传成功，追加写入
                    append = true
                }
                response.isSuccessful -> {
                    // 服务端不支持 Range 或返回完整文件，从头写
                    if (existingBytes > 0) partialFile.delete()
                    append = false
                }
                else -> throw OcrDownloadException(
                    "HTTP ${response.code}: ${spec.relativePath}",
                )
            }

            val body = response.body
                ?: throw OcrDownloadException("空响应体: ${spec.relativePath}")

            var bytesSoFar = if (append) existingBytes else 0L
            val progressInterval = 256 * 1024L
            var bytesSinceLastEmit = 0L

            FileOutputStream(partialFile, append).use { fos ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        fos.write(buffer, 0, read)
                        bytesSoFar += read
                        bytesSinceLastEmit += read
                        if (bytesSinceLastEmit >= progressInterval) {
                            onProgress(bytesSoFar)
                            bytesSinceLastEmit = 0
                        }
                    }
                }
            }
            // 写完后补一次最终进度，保证调用方能拿到收尾状态
            onProgress(bytesSoFar)
        }
    }

    /** 校验文件 SHA-256 是否匹配；返回 false 表示不匹配（不抛异常）。 */
    private fun verifySha256(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }
}
