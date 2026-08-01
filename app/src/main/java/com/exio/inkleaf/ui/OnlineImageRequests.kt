package com.exio.inkleaf.ui

import android.content.Context
import coil.request.ImageRequest
import com.exio.inkleaf.data.ReaderPageCacheKey
import com.exio.inkleaf.plugin.PageImage
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Builds the authenticated Coil request required by plugin-provided images. */
internal fun PageImage.toImageRequest(
    context: Context,
    crossfadeMillis: Int? = null,
): ImageRequest {
    val canonicalHeaders = canonicalHeaders()
    val cacheKey = cacheKey(canonicalHeaders)
    return ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey(cacheKey)
        .diskCacheKey(cacheKey)
        .apply {
            canonicalHeaders.forEach { (name, value) -> setHeader(name, value) }
            referer?.let { setHeader("Referer", it) }
            crossfadeMillis?.let { crossfade(it) }
        }
        .build()
}

internal fun PageImage.cacheKey(): String = cacheKey(canonicalHeaders())

private fun PageImage.cacheKey(
    canonicalHeaders: List<Map.Entry<String, String>>
): String =
    ReaderPageCacheKey.sourceRevision(
        buildList {
            add(url.toHttpUrlOrNull()?.toString() ?: url)
            canonicalHeaders.forEach { (name, value) ->
                add(name.lowercase(Locale.ROOT))
                add(value)
            }
            add(referer.orEmpty())
        }
    )

private fun PageImage.canonicalHeaders(): List<Map.Entry<String, String>> =
    headers.entries.sortedWith(
        compareBy<Map.Entry<String, String>>(
            { it.key.lowercase(Locale.ROOT) },
            { it.value },
        )
    )
