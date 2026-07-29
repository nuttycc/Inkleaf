package com.exio.inkleaf.data

import com.exio.inkleaf.data.db.BookSourceType

internal fun shouldPersistLibraryExclusion(sourceType: BookSourceType): Boolean =
    sourceType == BookSourceType.EXTERNAL_ARCHIVE

internal fun discoverableLibraryFileKeys(
    scannedKeys: Iterable<String>,
    existingKeys: Set<String>,
    excludedKeys: Set<String>,
): Set<String> =
    scannedKeys.filterTo(linkedSetOf()) { fileKey ->
        fileKey !in existingKeys && fileKey !in excludedKeys
    }
