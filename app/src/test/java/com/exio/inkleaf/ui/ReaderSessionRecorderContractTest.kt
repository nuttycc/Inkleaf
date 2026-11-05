package com.exio.inkleaf.ui

import com.exio.inkleaf.data.OnlineChapterIdentity
import com.exio.inkleaf.data.OnlineContentIdentity
import com.exio.inkleaf.data.OnlinePageLocation
import com.exio.inkleaf.data.ReadingSessionEndReason
import com.exio.inkleaf.data.ReadingSessionRules
import com.exio.inkleaf.plugin.OnlineReadingSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared expectations of [ReaderSessionRecorder], exercised through the online in-memory
 * adapter. The offline adapter needs Room, so its own machine tests keep covering the persisted
 * transitions while this contract pins the moments both readers must report.
 */
class ReaderSessionRecorderContractTest {
    private var wallMs = 1_000L
    private var elapsedMs = 0L
    private var currentPage: OnlinePageLocation? = null
    private val records = mutableListOf<OnlineReadingSessionRecord>()

    private val recorder =
        OnlineReaderSessionRecorder(
            session =
                OnlineReadingSession(
                    elapsedMs = { elapsedMs },
                    nowMs = { wallMs },
                    newSessionId = { "session-1" },
                ),
            content = content(),
            title = { "Comic" },
            pageAt = { currentPage },
            timeZoneId = { "Asia/Shanghai" },
            onRecord = { records += it },
        )

    @Test
    fun `a visit without a readable page leaves no record`() {
        recorder.begin()
        recorder.end(ReadingSessionEndReason.LEFT_READER, page(0))

        assertTrue(records.isEmpty())
    }

    @Test
    fun `a page reported before the visit begins leaves no record`() {
        currentPage = page(0)
        recorder.pageVisible(currentPage)
        recorder.end(ReadingSessionEndReason.LEFT_READER, null)

        assertTrue(records.isEmpty())
    }

    @Test
    fun `ending twice records one visit`() {
        startAt(0)
        recorder.end(ReadingSessionEndReason.LEFT_READER, page(3))
        recorder.end(ReadingSessionEndReason.LEFT_READER, page(4))

        assertEquals(1, records.size)
    }

    @Test
    fun `a chapter that is not ready does not count time`() {
        currentPage = page(0)
        recorder.begin()
        elapsedMs = 1_000L
        recorder.foregroundChanged(true)
        elapsedMs = 41_000L

        recorder.end(ReadingSessionEndReason.LEFT_READER, page(3))

        assertEquals(0L, requireNotNull(records.singleOrNull()).activeReadingMillis)
    }

    @Test
    fun `foreground time accumulates only while a page is visible`() {
        startAt(0)
        elapsedMs = 1_000L
        recorder.foregroundChanged(true)
        elapsedMs = 31_000L

        recorder.end(ReadingSessionEndReason.LEFT_READER, page(0))

        assertEquals(30_000L, requireNotNull(records.single()).activeReadingMillis)
    }

    @Test
    fun `background time is not counted`() {
        startAt(0)
        elapsedMs = 0L
        recorder.foregroundChanged(true)
        elapsedMs = 1_000L
        recorder.foregroundChanged(false)
        elapsedMs = 5_000L
        recorder.foregroundChanged(true)
        elapsedMs = 6_000L

        recorder.end(ReadingSessionEndReason.LEFT_READER, page(3))

        assertEquals(2_000L, requireNotNull(records.single()).activeReadingMillis)
    }

    @Test
    fun `end pauses the running segment and stamps the end wall time`() {
        startAt(0)
        elapsedMs = 100L
        recorder.foregroundChanged(true)
        elapsedMs = 30_100L
        wallMs = 31_000L

        recorder.end(ReadingSessionEndReason.LEFT_READER, page(2))

        val record = requireNotNull(records.single())
        assertEquals(30_000L, record.activeReadingMillis)
        assertEquals(31_000L, record.endedAtMs)
        assertEquals(page(2), record.end)
    }

    @Test
    fun `end falls back to the latest visible page`() {
        startAt(0)
        currentPage = page(3)
        recorder.pageVisible(currentPage)

        recorder.end(ReadingSessionEndReason.LEFT_READER, null)

        assertEquals(page(3), requireNotNull(records.single()).end)
    }

    @Test
    fun `end prefers the supplied final page`() {
        startAt(0)
        currentPage = page(3)
        recorder.pageVisible(currentPage)

        recorder.end(ReadingSessionEndReason.LEFT_READER, page(5))

        assertEquals(page(5), requireNotNull(records.single()).end)
    }

    @Test
    fun `a page that is no longer visible stops a later resume`() {
        startAt(0)
        elapsedMs = 100L
        recorder.foregroundChanged(true)
        elapsedMs = 500L
        recorder.pageVisible(null)
        elapsedMs = 1_000L
        recorder.foregroundChanged(true)
        elapsedMs = 2_000L
        currentPage = page(3)
        recorder.pageVisible(currentPage)
        elapsedMs = 3_000L

        recorder.end(ReadingSessionEndReason.LEFT_READER, null)

        assertEquals(1_400L, requireNotNull(records.single()).activeReadingMillis)
    }

    @Test
    fun `ended time never precedes the start`() {
        startAt(0)
        wallMs = 500L

        recorder.end(ReadingSessionEndReason.LEFT_READER, page(1))

        assertEquals(1_000L, requireNotNull(records.single()).endedAtMs)
    }

    @Test
    fun `a brief stationary visit leaves no record`() {
        startAt(0)
        elapsedMs = 1_000L
        recorder.foregroundChanged(true)
        elapsedMs = 2_000L

        recorder.end(ReadingSessionEndReason.LEFT_READER, page(0))

        assertNull(records.singleOrNull())
    }

    @Test
    fun `a stationary visit is kept at the shared threshold`() {
        startAt(0)
        elapsedMs = 1_000L
        recorder.foregroundChanged(true)
        elapsedMs = 1_000L + ReadingSessionRules.MIN_ACTIVE_READING_MS

        recorder.end(ReadingSessionEndReason.LEFT_READER, page(0))

        assertEquals(
            ReadingSessionRules.MIN_ACTIVE_READING_MS,
            requireNotNull(records.single()).activeReadingMillis,
        )
    }

    @Test
    fun `a recorded visit carries the captured facts`() {
        startAt(0)
        currentPage = page(3)
        recorder.pageVisible(currentPage)

        recorder.end(ReadingSessionEndReason.LEFT_READER, page(3))

        val record = requireNotNull(records.single())
        assertEquals("session-1", record.sessionId)
        assertEquals("Comic", record.titleSnapshot)
        assertEquals(1_000L, record.startedAtMs)
        assertEquals(1_000L, record.endedAtMs)
        assertEquals("Asia/Shanghai", record.timeZoneId)
        assertEquals(content(), record.content)
        assertEquals(page(0), record.start)
        assertEquals(page(3), record.end)
    }

    /** Starts the visit at [index] and reports that page as visible. */
    private fun startAt(index: Int) {
        currentPage = page(index)
        recorder.begin()
        recorder.pageVisible(currentPage)
    }

    private fun content() = OnlineContentIdentity(PLUGIN_ID, SOURCE_ID)

    private fun page(index: Int) =
        OnlinePageLocation.create(
            chapter = OnlineChapterIdentity(content(), "chapter-1"),
            pageId = "page-$index",
            pageIndex = index,
            chapterRevision = "r1",
        )

    private companion object {
        const val PLUGIN_ID = "io.example.source"
        const val SOURCE_ID = "comic-1"
    }
}
