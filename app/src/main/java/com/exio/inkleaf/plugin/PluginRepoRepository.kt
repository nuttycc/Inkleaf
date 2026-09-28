package com.exio.inkleaf.plugin

import com.exio.inkleaf.replaceFileAtomically
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** A repository index together with the wall-clock time it was fetched. */
@Serializable
data class PluginRepoCacheEntry(
    val index: PluginRepoIndex,
    val fetchedAtMs: Long,
)

/** Fetches remote plugin repository indexes over HTTPS, persisting the last good result. */
class PluginRepoRepository(
    private val callFactory: Call.Factory,
    private val cacheFile: File? = null,
    private val maxIndexBytes: Int = DEFAULT_MAX_INDEX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun fetchIndex(url: String): PluginRepoIndex {
        val index = fetchAndParse(url)
        cacheFile?.let { file ->
            withContext(Dispatchers.IO) { writeCache(file, index) }
        }
        return index
    }

    /** Returns the persisted index from the previous successful fetch, or null when absent. */
    suspend fun cachedIndex(): PluginRepoCacheEntry? {
        val file = cacheFile ?: return null
        return withContext(Dispatchers.IO) { readCache(file) }
    }

    /** True once the persisted entry is older than the cache lifetime. */
    fun isCacheExpired(entry: PluginRepoCacheEntry): Boolean =
        clock() - entry.fetchedAtMs >= PluginRepoContract.CACHE_TTL_MS

    private suspend fun fetchAndParse(url: String): PluginRepoIndex =
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

    private fun writeCache(file: File, index: PluginRepoIndex) {
        try {
            file.parentFile?.mkdirs()
            val payload = cacheJson.encodeToString(PluginRepoCacheEntry(index, clock()))
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(payload)
            replaceFileAtomically(temp.toPath(), file.toPath()) { from, to, options ->
                Files.move(from, to, *options)
            }
        } catch (_: Throwable) {
            // Cache writes are best-effort; a failed write only costs the offline fast path.
        }
    }

    private fun readCache(file: File): PluginRepoCacheEntry? =
        try {
            cacheJson.decodeFromString<PluginRepoCacheEntry>(file.readText())
        } catch (_: Throwable) {
            null
        }

    private companion object {
        const val DEFAULT_MAX_INDEX_BYTES = 2 * 1024 * 1024
        val cacheJson =
            Json {
                ignoreUnknownKeys = true
            }
    }
}
