package com.exio.inkleaf.plugin

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** Fetches remote plugin repository indexes over HTTPS and parses them. */
class PluginRepoRepository(
    private val callFactory: Call.Factory,
    private val maxIndexBytes: Int = DEFAULT_MAX_INDEX_BYTES,
) {
    suspend fun fetchIndex(url: String): PluginRepoIndex =
        withContext(Dispatchers.IO) {
            val trimmed = url.trim()
            val parsedUrl =
                trimmed.toHttpUrlOrNull()
                    ?: throw IOException("插件仓库 URL 无效")
            if (parsedUrl.isHttps != true) throw IOException("插件仓库仅支持 HTTPS URL")
            val request = Request.Builder().url(parsedUrl).build()
            val response =
                try {
                    callFactory.newCall(request).execute()
                } catch (error: IOException) {
                    throw IOException("无法连接插件仓库: ${error.message}", error)
                }
            response.use { result ->
                if (!result.isSuccessful) {
                    throw IOException("插件仓库返回 HTTP ${result.code}")
                }
                val body = result.body
                val bytes =
                    body.byteStream().use { input ->
                        val buffer = input.readBytes()
                        buffer
                    }
                if (bytes.size > maxIndexBytes) {
                    throw IOException("插件仓库索引超出大小限制")
                }
                try {
                    PluginRepoIndexParser.parse(String(bytes, Charsets.UTF_8))
                } catch (error: PluginRepoIndexFormatException) {
                    throw IOException(error.message, error)
                }
            }
        }

    private companion object {
        const val DEFAULT_MAX_INDEX_BYTES = 2 * 1024 * 1024
    }
}
