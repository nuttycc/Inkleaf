package com.exio.inkleaf.plugin

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineContentRepositoryTest {
    @Test
    fun `detail chapters and progress survive repository recreation`() {
        val root = Files.createTempDirectory("inkleaf-online-content").toFile()
        val file = root.resolve("state.json")
        try {
            val repository = OnlineContentRepository(file, clockMs = { 42L })
            repository.recordDetail(PLUGIN_ID, ComicDetail(SOURCE_ID, "Comic"))
            repository.recordChapters(
                PLUGIN_ID,
                PluginChaptersResponse(
                    sourceId = SOURCE_ID,
                    revision = "r1",
                    chapters = listOf(ChapterSummary("chapter-1", "Chapter 1", revision = "c1")),
                ),
            )
            repository.recordPosition(PLUGIN_ID, SOURCE_ID, "chapter-1", "page-2", 1, "c1")

            val restored = requireNotNull(OnlineContentRepository(file).get(PLUGIN_ID, SOURCE_ID))
            assertEquals("Comic", restored.detail?.title)
            assertEquals("chapter-1", restored.chapters.single().chapterId)
            assertEquals(1, restored.position?.pageIndex)
            assertTrue(OnlineUserReference.HISTORY in restored.references)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `plugin lifecycle changes availability without deleting snapshots`() {
        val root = Files.createTempDirectory("inkleaf-online-lifecycle").toFile()
        try {
            val repository = OnlineContentRepository(root.resolve("state.json"))
            repository.recordDetail(PLUGIN_ID, ComicDetail(SOURCE_ID, "Comic"))
            repository.setPluginAvailability(PLUGIN_ID, OnlineAvailability.PLUGIN_UNINSTALLED)
            val record = requireNotNull(repository.get(PLUGIN_ID, SOURCE_ID))
            assertEquals(OnlineAvailability.PLUGIN_UNINSTALLED, record.availability)
            assertNotNull(record.detail)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val PLUGIN_ID = "io.example.source"
        const val SOURCE_ID = "comic-1"
    }
}
