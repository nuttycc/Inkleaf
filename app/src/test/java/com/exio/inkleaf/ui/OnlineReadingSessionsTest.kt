package com.exio.inkleaf.ui

import com.exio.inkleaf.data.OnlineChapterIdentity
import com.exio.inkleaf.data.OnlineContentIdentity
import com.exio.inkleaf.data.OnlinePageLocation
import com.exio.inkleaf.data.ReadingSessionRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineReadingSessionsTest {
    @Test
    fun `record is dropped without a session id`() {
        assertNull(record(sessionId = null))
    }

    @Test
    fun `record is dropped without a start location`() {
        assertNull(record(start = null))
    }

    @Test
    fun `record is dropped without an end location`() {
        assertNull(record(end = null))
    }

    @Test
    fun `record is dropped for a brief session that never moved`() {
        val here = location(pageIndex = 0)
        assertNull(record(activeReadingMillis = 1_000L, start = here, end = here))
    }

    @Test
    fun `record is kept once active reading passes the shared threshold`() {
        val here = location(pageIndex = 0)
        val kept =
            record(
                activeReadingMillis = ReadingSessionRules.MIN_ACTIVE_READING_MS,
                start = here,
                end = here,
            )
        assertNotNull(kept)
    }

    @Test
    fun `record is kept when the position moved within the threshold`() {
        val kept =
            record(
                activeReadingMillis = 1_000L,
                start = location(pageIndex = 0),
                end = location(pageIndex = 3),
            )
        assertNotNull(kept)
    }

    @Test
    fun `record carries the captured facts`() {
        val kept =
            record(
                sessionId = "session-1",
                titleSnapshot = "Comic",
                startedAtMs = 10L,
                endedAtMs = 90L,
                activeReadingMillis = 80L,
                timeZoneId = "Asia/Shanghai",
                start = location(pageIndex = 0),
                end = location(pageIndex = 3),
            )

        requireNotNull(kept)
        assertEquals("session-1", kept.sessionId)
        assertEquals("Comic", kept.titleSnapshot)
        assertEquals(10L, kept.startedAtMs)
        assertEquals(90L, kept.endedAtMs)
        assertEquals(80L, kept.activeReadingMillis)
        assertEquals("Asia/Shanghai", kept.timeZoneId)
        assertEquals(content(), kept.content)
        assertEquals(location(pageIndex = 0), kept.start)
        assertEquals(location(pageIndex = 3), kept.end)
    }

    private fun record(
        sessionId: String? = "session-1",
        titleSnapshot: String = "Comic",
        startedAtMs: Long = 10L,
        endedAtMs: Long = 90L,
        activeReadingMillis: Long = 80L,
        timeZoneId: String = "Asia/Shanghai",
        start: OnlinePageLocation? = location(pageIndex = 0),
        end: OnlinePageLocation? = location(pageIndex = 1),
    ) =
        OnlineReadingSessions.record(
            sessionId = sessionId,
            content = content(),
            titleSnapshot = titleSnapshot,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            activeReadingMillis = activeReadingMillis,
            timeZoneId = timeZoneId,
            start = start,
            end = end,
        )

    private fun content() = OnlineContentIdentity(PLUGIN_ID, SOURCE_ID)

    private fun location(pageIndex: Int) =
        OnlinePageLocation.create(
            chapter = OnlineChapterIdentity(content(), "chapter-1"),
            pageId = "page-$pageIndex",
            pageIndex = pageIndex,
            chapterRevision = "r1",
        )

    private companion object {
        const val PLUGIN_ID = "io.example.source"
        const val SOURCE_ID = "comic-1"
    }
}
