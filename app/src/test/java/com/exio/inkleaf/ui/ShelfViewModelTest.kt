package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.OnlineComicRecord
import com.exio.inkleaf.plugin.OnlineContentKey
import com.exio.inkleaf.plugin.OnlineContentRepository
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Test
    fun `online snapshot recovers after a failed read`() = runBlocking {
        val revisions = MutableStateFlow(0)
        val fail = AtomicBoolean(true)
        val shareScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val snapshots =
                observeOnlineBookmarked(revisions) {
                        if (fail.get()) error("corrupt")
                        listOf(OnlineComicRecord(OnlineContentKey(PLUGIN_ID, SOURCE_ID)))
                    }
                    .shareIn(shareScope, SharingStarted.Eagerly, replay = 1)

            withTimeout(1_000) {
                while (snapshots.replayCache.isEmpty()) yield()
            }
            assertEquals(emptyList<OnlineComicRecord>(), snapshots.replayCache.single())

            fail.set(false)
            revisions.value = 1
            withTimeout(1_000) {
                while (snapshots.replayCache.lastOrNull()?.isEmpty() != false) yield()
            }
            assertEquals(
                listOf(SOURCE_ID),
                snapshots.replayCache.last().map { it.key.sourceId },
            )
        } finally {
            shareScope.cancel()
        }
    }

    private companion object {
        const val PLUGIN_ID = "io.example.source"
        const val SOURCE_ID = "comic-1"
    }
}
