package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.OnlineContentRepository
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfViewModelTest {
    @Test
    fun `online shelf snapshot stays current while shelf is not collected`() = runBlocking {
        val root = Files.createTempDirectory("inkleaf-online-shelf-flow").toFile()
        val shareScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val repository = OnlineContentRepository(root.resolve("state.json"))
            repository.setComicFollow(PLUGIN_ID, SOURCE_ID, present = true)
            val snapshots =
                observeOnlineBookmarked(repository.revision) {
                    repository.listBookmarked()
                }
                    .shareIn(shareScope, SharingStarted.Eagerly, replay = 1)

            val initial = withTimeout(1_000) { snapshots.first() }
            assertEquals(null, initial.single().position)

            repository.recordPosition(
                pluginId = PLUGIN_ID,
                sourceId = SOURCE_ID,
                chapterId = "chapter-2",
                pageId = "page-5",
                pageIndex = 4,
                chapterRevision = "revision-2",
            )
            withTimeout(1_000) {
                while (
                    snapshots.replayCache
                        .singleOrNull()
                        ?.singleOrNull()
                        ?.position
                        ?.chapterId != "chapter-2"
                ) {
                    yield()
                }
            }

            val current = withTimeout(1_000) { snapshots.first() }
            assertEquals("chapter-2", current.single().position?.chapterId)
            assertEquals(4, current.single().position?.pageIndex)
        } finally {
            shareScope.cancel()
            root.deleteRecursively()
        }
    }

    private companion object {
        const val PLUGIN_ID = "io.example.source"
        const val SOURCE_ID = "comic-1"
    }
}
