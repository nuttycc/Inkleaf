package com.exio.inkleaf.data

import com.exio.inkleaf.data.db.BookSourceType

internal fun shouldPersistLibraryExclusion(sourceType: BookSourceType): Boolean =
    sourceType == BookSourceType.EXTERNAL_ARCHIVE

/** A file discovered by one library-folder scan. */
internal data class LibraryScanEntry(val uri: String, val fileKey: String)

/** A shelf row already stored for the same library folder. */
internal data class LibraryExistingEntry(
    val id: Long,
    val uri: String,
    val fileKey: String,
    val isMissing: Boolean,
)

/** A shelf row whose identity must be rewritten to the key produced by the current scan. */
internal data class LibraryIdentityUpdate(val id: Long, val uri: String, val fileKey: String)

internal data class LibrarySyncDiff(
    val newFiles: List<LibraryScanEntry>,
    val identityUpdates: List<LibraryIdentityUpdate>,
    val toMarkMissing: List<Long>,
    val restored: List<Long>,
    val alreadyInLibrary: Int,
)

/**
 * Compares the files under one library folder with its shelf rows.
 *
 * `fileKey` is the primary identity, but providers can hand out different keys for the same
 * document across device restarts (`stat:` keys embed the mount's device number). When the key no
 * longer matches, the normalized SAF document identity derived from `uri` still does, so the row
 * keeps its progress and only its key is rewritten.
 */
internal fun computeLibrarySyncDiff(
    scanned: List<LibraryScanEntry>,
    existing: List<LibraryExistingEntry>,
    excludedKeys: Set<String>,
): LibrarySyncDiff {
    val distinctScanned = scanned.distinctBy { it.fileKey }
    val scannedByKey = distinctScanned.associateBy { it.fileKey }
    val scannedByIdentity =
        distinctScanned
            .mapNotNull { entry -> libraryDocumentIdentity(entry.uri)?.let { it to entry } }
            .toMap()

    val matchedKeys = linkedSetOf<String>()
    val identityUpdates = mutableListOf<LibraryIdentityUpdate>()
    val toMarkMissing = mutableListOf<Long>()
    val restored = mutableListOf<Long>()
    var alreadyInLibrary = 0

    // Exact key matches first: they claim their scanned file, so a stale row holding another key
    // can never rewrite itself onto an identity that is already taken (that would violate the
    // unique fileKey index and create a duplicate shelf entry).
    for (comic in existing) {
        val file = scannedByKey[comic.fileKey] ?: continue
        matchedKeys.add(file.fileKey)
        if (comic.isMissing) restored.add(comic.id) else alreadyInLibrary++
    }

    for (comic in existing) {
        if (comic.fileKey in matchedKeys) continue
        val identityFile = libraryDocumentIdentity(comic.uri)?.let(scannedByIdentity::get)
        when {
            identityFile == null -> {
                if (!comic.isMissing) toMarkMissing.add(comic.id)
            }
            // Same document is already represented by another shelf row.
            identityFile.fileKey in matchedKeys -> Unit
            else -> {
                matchedKeys.add(identityFile.fileKey)
                identityUpdates.add(
                    LibraryIdentityUpdate(
                        id = comic.id,
                        uri = identityFile.uri,
                        fileKey = identityFile.fileKey,
                    )
                )
                if (comic.isMissing) restored.add(comic.id) else alreadyInLibrary++
            }
        }
    }

    val newFiles = distinctScanned.filter {
        it.fileKey !in matchedKeys && it.fileKey !in excludedKeys
    }
    return LibrarySyncDiff(
        newFiles = newFiles,
        identityUpdates = identityUpdates,
        toMarkMissing = toMarkMissing,
        restored = restored,
        alreadyInLibrary = alreadyInLibrary,
    )
}

/**
 * Extracts the provider + document id from a SAF `.../document/<id>` URI.
 *
 * Document ids live in the last path segment and may contain percent-encoded separators
 * (`primary%3ADownload%2F...`). Decoding before comparing keeps matches stable even if the provider
 * changes how it encodes the URI.
 */
internal fun libraryDocumentIdentity(uri: String): String? {
    val marker = "/document/"
    val markerIndex = uri.indexOf(marker)
    if (markerIndex < 0) return null
    val schemeIndex = uri.indexOf("://")
    if (schemeIndex < 0) return null
    val authority = uri.substring(schemeIndex + 3).substringBefore('/')
    if (authority.isBlank()) return null
    val rawDocumentId =
        uri.substring(markerIndex + marker.length)
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore('/')
    val documentId = decodePercent(rawDocumentId)
    if (documentId.isBlank()) return null
    return "$authority:$documentId"
}

private fun decodePercent(value: String): String {
    if ('%' !in value) return value
    val decoded = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val code = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (code != null) {
                decoded.append(code.toChar())
                index += 3
                continue
            }
        }
        decoded.append(char)
        index++
    }
    return decoded.toString()
}
