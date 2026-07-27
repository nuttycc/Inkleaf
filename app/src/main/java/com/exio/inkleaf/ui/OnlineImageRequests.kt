package com.exio.inkleaf.ui

import android.content.Context
import coil.request.ImageRequest
import com.exio.inkleaf.plugin.PageImage

/** Builds the authenticated Coil request required by plugin-provided images. */
internal fun PageImage.toImageRequest(
    context: Context,
    crossfadeMillis: Int? = null,
): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .apply {
            headers.forEach { (name, value) -> setHeader(name, value) }
            referer?.let { setHeader("Referer", it) }
            crossfadeMillis?.let { crossfade(it) }
        }
        .build()
