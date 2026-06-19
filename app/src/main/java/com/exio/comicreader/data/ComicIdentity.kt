package com.exio.comicreader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants

/**
 * Stable identity for a comic file.
 *
 * SAF document IDs are only provider-local. A single local file can be exposed
 * through different providers, so prefer the provider-independent file stat,
 * then fall back to MediaStore and finally the SAF document identity.
 */
object ComicIdentity {
    fun fileKey(context: Context, uri: Uri): String {
        fileDescriptorKey(context, uri)?.let { return it }
        mediaStoreKey(context, uri)?.let { return it }
        return safKey(uri)
    }

    private fun mediaStoreKey(context: Context, uri: Uri): String? =
        runCatching { MediaStore.getMediaUri(context, uri) }
            .getOrNull()
            ?.normalizeScheme()
            ?.toString()
            ?.let { "media:$it" }

    private fun fileDescriptorKey(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val stat = Os.fstat(pfd.fileDescriptor)
                if (OsConstants.S_ISREG(stat.st_mode)) {
                    "stat:${stat.st_dev}:${stat.st_ino}"
                } else {
                    null
                }
            }
        }.getOrNull()

    private fun safKey(uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        return if (!documentId.isNullOrBlank()) {
            safKey(uri.authority, documentId)
        } else {
            "uri:${uri.normalizeScheme()}"
        }
    }

    private fun safKey(authority: String?, documentId: String): String {
        val provider = authority?.takeIf { it.isNotBlank() } ?: "unknown"
        return "saf:$provider:$documentId"
    }
}
