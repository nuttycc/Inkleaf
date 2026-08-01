package com.exio.inkleaf

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AtomicFileReplacementTest {
    @Test
    fun `replacement falls back when atomic moves are unsupported`() {
        val directory = Files.createTempDirectory("inkleaf-atomic-replacement")
        try {
            val source = directory.resolve("source")
            val target = directory.resolve("target")
            source.toFile().writeText("new")
            target.toFile().writeText("old")
            val attempts = mutableListOf<List<CopyOption>>()

            replaceFileAtomically(source, target) { from, to, options ->
                attempts += options.toList()
                if (attempts.size == 1) {
                    throw AtomicMoveNotSupportedException(
                        from.toString(),
                        to.toString(),
                        "test fallback",
                    )
                }
                Files.move(from, to, *options)
            }

            assertEquals(
                listOf(
                    listOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                    listOf(StandardCopyOption.REPLACE_EXISTING),
                ),
                attempts,
            )
            assertEquals("new", target.toFile().readText())
            assertFalse(source.toFile().exists())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
