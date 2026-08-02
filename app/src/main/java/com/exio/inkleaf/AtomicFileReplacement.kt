package com.exio.inkleaf

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal fun replaceFileAtomically(
    source: Path,
    target: Path,
    move: (Path, Path, Array<out CopyOption>) -> Unit = { from, to, options ->
        Files.move(from, to, *options)
    },
) {
    try {
        move(
            source,
            target,
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
        )
    } catch (_: AtomicMoveNotSupportedException) {
        move(source, target, arrayOf(StandardCopyOption.REPLACE_EXISTING))
    }
}
