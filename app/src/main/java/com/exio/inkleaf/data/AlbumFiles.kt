package com.exio.inkleaf.data

import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException

/** Serializes album page replacement, export, deletion, and cold-start reconciliation. */
internal val albumFileMutex = Mutex()

/** Resolves a database path without allowing it to escape app-private storage. */
internal fun resolveAlbumPageFile(filesDir: File, relativePath: String): File {
    val root = filesDir.canonicalFile
    val file = File(root, relativePath).canonicalFile
    if (!file.toPath().startsWith(root.toPath())) {
        throw IOException("图册页面路径无效")
    }
    return file
}
