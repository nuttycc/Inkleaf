package com.exio.inkleaf.plugin

import com.exio.inkleaf.data.OnlineChapterIdentity
import com.exio.inkleaf.data.OnlineContentIdentity
import com.exio.inkleaf.data.OnlinePageLocation
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun `favorite snapshot publication rejects a file allocated for another page`() {
        val root = Files.createTempDirectory("inkleaf-online-snapshot-identity").toFile()
        try {
            val repository = OnlineContentRepository(root.resolve("state.json"))
            val firstPage = location(pageId = "page-1", pageIndex = 0, revision = "revision-1")
            val secondPage = location(pageId = "page-2", pageIndex = 1, revision = "revision-1")
            val firstPageFile = repository.pageFavoriteSnapshotFile(firstPage.identity, "webp")
            firstPageFile.writeBytes(byteArrayOf(1, 2, 3))

            assertThrows(IllegalArgumentException::class.java) {
                repository.recordPageFavoriteSnapshot(
                    secondPage,
                    firstPageFile,
                    mimeType = "image/webp",
                    width = 1200,
                    height = 1800,
                )
            }
            assertTrue(repository.listPageFavorites().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `favorite replacement publishes a unique file before removing the prior snapshot`() {
        val root = Files.createTempDirectory("inkleaf-online-snapshot-replace").toFile()
        try {
            val repository = OnlineContentRepository(root.resolve("state.json"), clockMs = { 100L })
            val page = location(pageId = "page-1", pageIndex = 0, revision = "revision-1")
            val firstFile = repository.pageFavoriteSnapshotFile(page.identity, "webp")
            val replacementFile = repository.pageFavoriteSnapshotFile(page.identity, "webp")
            assertNotEquals(firstFile, replacementFile)

            firstFile.writeBytes(byteArrayOf(1, 2, 3))
            repository.recordPageFavoriteSnapshot(
                page,
                firstFile,
                mimeType = "image/webp",
                width = 1200,
                height = 1800,
            )
            assertTrue(firstFile.isFile)

            replacementFile.writeBytes(byteArrayOf(4, 5, 6, 7))
            val replacement = repository.recordPageFavoriteSnapshot(
                page,
                replacementFile,
                mimeType = "image/webp",
                width = 1200,
                height = 1800,
            )

            assertFalse(firstFile.exists())
            assertTrue(replacementFile.isFile)
            assertEquals(replacementFile.canonicalFile, repository.resolvePageFavoriteSnapshot(replacement))
            assertEquals(4L, replacement.snapshot.byteCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `removing a page favorite deletes its published snapshot`() {
        val root = Files.createTempDirectory("inkleaf-online-snapshot-remove").toFile()
        try {
            val repository = OnlineContentRepository(root.resolve("state.json"))
            val page = location(pageId = "page-1", pageIndex = 0, revision = "revision-1")
            val snapshotFile = repository.pageFavoriteSnapshotFile(page.identity, "webp")
            snapshotFile.writeBytes(byteArrayOf(1, 2, 3))
            repository.recordPageFavoriteSnapshot(
                page,
                snapshotFile,
                mimeType = "image/webp",
                width = 1200,
                height = 1800,
            )

            assertTrue(repository.removePageFavorite(page.identity))

            assertFalse(snapshotFile.exists())
            assertTrue(repository.listPageFavorites().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `page user records and reading sessions survive repository recreation`() {
        val root = Files.createTempDirectory("inkleaf-online-records").toFile()
        val file = root.resolve("state.json")
        try {
            val repository = OnlineContentRepository(file, clockMs = { 100L })
            val firstPage = location(pageId = "page-1", pageIndex = 0, revision = "revision-1")
            val lastPage = location(pageId = null, pageIndex = 7, revision = "revision-1")
            repository.setComicFollow(PLUGIN_ID, SOURCE_ID, present = true)
            repository.addPageBookmark(firstPage, chapterTitleSnapshot = "Chapter 1")
            repository.setComicFollow(PLUGIN_ID, SOURCE_ID, present = false)

            val snapshotFile = repository.pageFavoriteSnapshotFile(firstPage.identity, "webp")
            assertThrows(IllegalArgumentException::class.java) {
                repository.recordPageFavoriteSnapshot(
                    firstPage,
                    snapshotFile,
                    mimeType = "image/webp",
                    width = 1200,
                    height = 1800,
                )
            }
            snapshotFile.writeBytes(byteArrayOf(1, 2, 3, 4))
            repository.recordPageFavoriteSnapshot(
                firstPage,
                snapshotFile,
                mimeType = "image/webp",
                width = 1200,
                height = 1800,
                chapterTitleSnapshot = "Chapter 1",
            )
            repository.recordReadingSession(
                OnlineReadingSessionRecord(
                    sessionId = "session-1",
                    content = content(),
                    titleSnapshot = "Comic",
                    startedAtMs = 10L,
                    endedAtMs = 90L,
                    activeReadingMillis = 80L,
                    timeZoneId = "Asia/Shanghai",
                    start = firstPage,
                    end = lastPage,
                )
            )
            repository.setPluginAvailability(PLUGIN_ID, OnlineAvailability.PLUGIN_UNINSTALLED)

            val restored = OnlineContentRepository(file)
            val record = requireNotNull(restored.get(PLUGIN_ID, SOURCE_ID))
            assertEquals(OnlineAvailability.PLUGIN_UNINSTALLED, record.availability)
            assertFalse(OnlineUserReference.BOOKMARK in record.references)
            assertEquals(firstPage.identity, record.pageBookmarks.single().location.identity)
            assertEquals("session-1", record.readingSessions.single().sessionId)
            val favorite = record.pageFavorites.single()
            assertEquals(4L, favorite.snapshot.byteCount)
            assertEquals("page-favorites", favorite.snapshot.relativePath.substringBefore('/'))
            assertFalse(favorite.snapshot.relativePath.startsWith(root.absolutePath))
            assertEquals(snapshotFile.canonicalFile, restored.resolvePageFavoriteSnapshot(favorite))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reading sessions can be removed restored and cleared without deleting progress`() {
        val root = Files.createTempDirectory("inkleaf-online-history-actions").toFile()
        try {
            val repository = OnlineContentRepository(root.resolve("state.json"), clockMs = { 100L })
            val firstPage = location(pageId = "page-1", pageIndex = 0, revision = "revision-1")
            val lastPage = location(pageId = "page-8", pageIndex = 7, revision = "revision-1")
            repository.recordPosition(PLUGIN_ID, SOURCE_ID, "chapter-1", "page-8", 7, "revision-1")
            val firstSession = readingSession("session-1", firstPage, lastPage)
            val secondSession = readingSession("session-2", firstPage, lastPage)
            repository.recordReadingSession(firstSession)
            repository.recordReadingSession(secondSession)

            assertTrue(repository.removeReadingSession(content(), firstSession.sessionId))
            assertEquals(listOf("session-2"), repository.listReadingSessions().map { it.sessionId })

            repository.recordReadingSession(firstSession)
            assertEquals(2, repository.listReadingSessions().size)
            assertEquals(2, repository.clearReadingSessions())
            assertTrue(repository.listReadingSessions().isEmpty())
            assertEquals(7, repository.get(PLUGIN_ID, SOURCE_ID)?.position?.pageIndex)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `existing state json decodes with defaults for new user records`() {
        val root = Files.createTempDirectory("inkleaf-online-legacy").toFile()
        val file = root.resolve("state.json")
        try {
            file.writeText(
                """
                {
                  "records": [{
                    "key": {"pluginId": "$PLUGIN_ID", "sourceId": "$SOURCE_ID"},
                    "position": {
                      "chapterId": "chapter-1",
                      "pageIndex": 2,
                      "chapterRevision": "revision-1",
                      "updatedAtMs": 42
                    },
                    "references": ["HISTORY"]
                  }]
                }
                """.trimIndent()
            )

            val record = requireNotNull(OnlineContentRepository(file).get(PLUGIN_ID, SOURCE_ID))
            assertEquals(2, record.position?.pageIndex)
            assertTrue(record.pageBookmarks.isEmpty())
            assertTrue(record.pageFavorites.isEmpty())
            assertTrue(record.readingSessions.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun content() = OnlineContentIdentity(PLUGIN_ID, SOURCE_ID)

    private fun location(pageId: String?, pageIndex: Int, revision: String) =
        OnlinePageLocation.create(
            chapter = OnlineChapterIdentity(content(), "chapter-1"),
            pageId = pageId,
            pageIndex = pageIndex,
            chapterRevision = revision,
        )

    private fun readingSession(
        id: String,
        start: OnlinePageLocation,
        end: OnlinePageLocation,
    ) =
        OnlineReadingSessionRecord(
            sessionId = id,
            content = content(),
            titleSnapshot = "Comic",
            startedAtMs = 10L,
            endedAtMs = 90L,
            activeReadingMillis = 80L,
            timeZoneId = "Asia/Shanghai",
            start = start,
            end = end,
        )

    private companion object {
        const val PLUGIN_ID = "io.example.source"
        const val SOURCE_ID = "comic-1"
    }
}
