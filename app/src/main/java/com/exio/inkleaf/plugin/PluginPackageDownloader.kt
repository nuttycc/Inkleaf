package com.exio.inkleaf.plugin

import java.io.File
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class PluginDownloadSource(
    val url: String,
    val expectedSha256: String? = null,
    val expectedSizeBytes: Long? = null,
)

data class PluginDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val resumed: Boolean,
)

/** Downloads local-install-compatible packages with a bounded partial-file resume path. */
class PluginPackageDownloader(
    client: OkHttpClient = OkHttpClient(),
    private val cacheDirectory: File,
) {
    private val client =
        client
            .newBuilder()
            .dns(PluginNetworkPolicy.publicDns)
            .proxy(Proxy.NO_PROXY)
            .followSslRedirects(false)
            .build()

    suspend fun download(
        source: PluginDownloadSource,
        onProgress: (PluginDownloadProgress) -> Unit = {},
    ): File =
        withContext(Dispatchers.IO) {
            val url = source.url.trim()
            val parsedUrl = url.toHttpUrlOrNull()
            require(parsedUrl?.isHttps == true) { "Only HTTPS plugin URLs are supported" }
            if (url.length > 8192) throw IOException("Plugin URL is too long")
            if (!cacheDirectory.mkdirs() && !cacheDirectory.isDirectory)
                throw IOException("Unable to create plugin download cache")
            val key = sha256String(url)
            val partial = cacheDirectory.resolve("$key.zip.partial")
            val completed = cacheDirectory.resolve("$key.zip")
            val completedSizeMatches =
                source.expectedSizeBytes == null || completed.length() == source.expectedSizeBytes
            val completedHashMatches =
                !completed.isFile ||
                    source.expectedSha256 == null ||
                    source.expectedSha256.equals(sha256(completed), ignoreCase = true)
            if (completed.isFile && completedSizeMatches && completedHashMatches) {
                onProgress(
                    PluginDownloadProgress(completed.length(), completed.length(), resumed = false)
                )
                return@withContext completed
            }

            val existingBytes = partial.takeIf { it.isFile }?.length() ?: 0L
            val requestBuilder = Request.Builder().url(parsedUrl)
            if (existingBytes > 0L) requestBuilder.header("Range", "bytes=$existingBytes-")
            val response = client.newCall(requestBuilder.build()).execute()
            response.use { result ->
                if (!result.isSuccessful && result.code != 206)
                    throw IOException("Plugin download failed with HTTP ${result.code}")
                val resumed = existingBytes > 0L && result.code == 206
                val startBytes = if (resumed) existingBytes else 0L
                if (!resumed && partial.exists() && !partial.delete())
                    throw IOException("Unable to restart partial plugin download")
                val contentLength = result.body?.contentLength()?.takeIf { it >= 0L }
                val total = contentLength?.let { it + startBytes } ?: source.expectedSizeBytes
                if (total != null && total > PluginStorageLimits.MAX_PACKAGE_BYTES) {
                    throw IOException("Plugin download exceeds the package size limit")
                }
                result.body?.byteStream()?.use { input ->
                    java.io.FileOutputStream(partial, resumed).buffered().use { output ->
                        copyBounded(input, output, startBytes, total, resumed, onProgress)
                    }
                } ?: throw IOException("Plugin download returned an empty body")
            }
            if (partial.length() > PluginStorageLimits.MAX_PACKAGE_BYTES) {
                throw IOException("Plugin download exceeds the package size limit")
            }
            source.expectedSizeBytes?.let { expected ->
                if (partial.length() != expected)
                    throw IOException("Plugin download size does not match metadata")
            }
            source.expectedSha256?.let { expected ->
                if (!expected.equals(sha256(partial), ignoreCase = true))
                    throw IOException("Plugin SHA-256 does not match metadata")
            }
            if (completed.exists() && !completed.delete())
                throw IOException("Unable to replace completed plugin download")
            if (!partial.renameTo(completed))
                throw IOException("Unable to finalize plugin download")
            completed
        }

    private fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        startBytes: Long,
        totalBytes: Long?,
        resumed: Boolean,
        onProgress: (PluginDownloadProgress) -> Unit,
    ) {
        output.use { sink ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var downloaded = startBytes
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                downloaded += count
                if (downloaded > PluginStorageLimits.MAX_PACKAGE_BYTES) {
                    throw IOException("Plugin download exceeds the package size limit")
                }
                sink.write(buffer, 0, count)
                onProgress(PluginDownloadProgress(downloaded, totalBytes, resumed))
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256String(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
