package com.exio.comicreader.data

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Stable identity for a SAF document.
 *
 * Uri strings can differ for the same file depending on whether it came from
 * OpenDocument or a tree scan. The provider authority plus documentId is the
 * closest stable identity SAF exposes without reading the whole file.
 */
object ComicIdentity {
    fun fileKey(uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        return if (!documentId.isNullOrBlank()) {
            fileKey(uri.authority, documentId)
        } else {
            "uri:${uri.normalizeScheme()}"
        }
    }

    fun fileKey(authority: String?, documentId: String): String {
        val provider = authority?.takeIf { it.isNotBlank() } ?: "unknown"
        return "saf:$provider:$documentId"
    }
}
