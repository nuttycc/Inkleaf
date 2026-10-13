package com.exio.inkleaf.update

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

data class ApkDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
)

/**
 * Downloads an APK to the app's cache directory with progress reporting.
 *
 * The downloaded file is placed in a dedicated subdirectory so the FileProvider can serve it
 * without exposing unrelated cache contents.
 */
class AppUpdateApkDownloader(
    private val context: Context,
    private val callFactory: Call.Factory,
) {
    suspend fun download(
        entry: AppUpdateEntry,
        onProgress: (ApkDownloadProgress) -> Unit = {},
    ): File =
        withContext(Dispatchers.IO) {
            val url = entry.apkUrl.trim()
            val directory = File(context.cacheDir, "app-updates")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Unable to create update cache directory")
            }

            val fileName = "Inkleaf-${entry.version}.apk"
            val target = File(directory, fileName)
            if (target.isFile && isExistingFileValid(target, entry)) {
                onProgress(ApkDownloadProgress(target.length(), target.length()))
                return@withContext target
            }

            val request = Request.Builder().url(url).build()
            val response = callFactory.newCall(request).execute()
            response.use { result ->
                if (!result.isSuccessful) {
                    throw IOException("APK 下载失败: HTTP ${result.code}")
                }
                val contentLength = result.body.contentLength().takeIf { it >= 0L }
                val totalBytes = contentLength ?: entry.sizeBytes

                val partial = File(directory, "$fileName.partial")
                FileOutputStream(partial).use { output ->
                    result.body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            output.write(buffer, 0, count)
                            onProgress(ApkDownloadProgress(downloaded, totalBytes))
                        }
                    }
                }

                if (!isExistingFileValid(partial, entry)) {
                    partial.delete()
                    throw IOException("APK 校验失败")
                }

                if (target.exists() && !target.delete()) {
                    partial.delete()
                    throw IOException("Unable to replace existing update APK")
                }
                if (!partial.renameTo(target)) {
                    partial.delete()
                    throw IOException("Unable to finalize update APK")
                }
                target
            }
        }

    private fun isExistingFileValid(file: File, entry: AppUpdateEntry): Boolean {
        if (entry.sizeBytes != null && file.length() != entry.sizeBytes) return false
        if (entry.sha256 != null) {
            val actual = sha256(file)
            if (!entry.sha256.equals(actual, ignoreCase = true)) return false
        }
        return true
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
}
