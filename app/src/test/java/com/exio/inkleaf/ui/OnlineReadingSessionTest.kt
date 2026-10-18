package com.exio.inkleaf.ui

import com.exio.inkleaf.data.OnlineChapterIdentity
import com.exio.inkleaf.data.OnlineContentIdentity
import com.exio.inkleaf.data.OnlinePageLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineReadingSessionTest {

    private var wallMs = 1_000L
    private var elapsedMs = 0L

    private fun session(id: String = "session-1") =
        OnlineReadingSession(
            elapsedMs = { elapsedMs },
            nowMs = { wallMs },
            newSessionId = { id },
        )

    @Test
    fun `begin starts the session at the initial position`() {
        val session = session()

        session.begin(location(0))

        assertTrue(session.hasStarted)
        assertFalse(session.isEnded)
        val finished = requireNotNull(session.finish(null))
        assertEquals("session-1", finished.sessionId)
        assertEquals(1_000L, finished.startedAtMs)
        assertEquals(1_000L, finished.endedAtMs)
        assertEquals(0L, finished.activeReadingMillis)
        assertEquals(location(0), finished.start)
        assertEquals(location(0), finished.end)
    }

    @Test
    fun `active time only counts while the chapter is ready`() {
        val session = session()
        session.begin(location(0))

        elapsedMs = 1_000
        session.resumeSegment()
        elapsedMs = 2_000
        session.setChapterReady(true)
        session.resumeSegment()
        elapsedMs = 7_000
        session.pauseSegment()
        elapsedMs = 9_000
        session.resumeSegment()
        elapsedMs = 12_000
        session.pauseSegment()

        val finished = requireNotNull(session.finish(null))
        assertEquals(8_000L, finished.activeReadingMillis)
    }

    @Test
    fun `finish pauses a running segment and stamps the end`() {
        val session = session()
        session.begin(location(0))
        session.setChapterReady(true)
        elapsedMs = 100
        session.resumeSegment()
        elapsedMs = 30_100
        wallMs = 31_000

        val finished = requireNotNull(session.finish(location(2)))

        assertEquals(30_000L, finished.activeReadingMillis)
        assertEquals(31_000L, finished.endedAtMs)
        assertEquals(location(2), finished.end)
    }

    @Test
    fun `finish falls back to the latest position`() {
        val session = session()
        session.begin(location(0))
        session.moveTo(location(3))

        val finished = requireNotNull(session.finish(null))

        assertEquals(location(3), finished.end)
    }

    @Test
    fun `finish prefers the supplied final position`() {
        val session = session()
        session.begin(location(0))
        session.moveTo(location(3))

        val finished = requireNotNull(session.finish(location(5)))

        assertEquals(location(5), finished.end)
    }

    @Test
    fun `finish ends the session once`() {
        val session = session()
        session.begin(location(0))

        assertNotNull(session.finish(location(1)))
        assertTrue(session.isEnded)
        assertNull(session.finish(location(2)))
    }

    @Test
    fun `finish without a session returns null`() {
        val session = session()

        assertFalse(session.hasStarted)
        assertNull(session.finish(location(0)))
        assertTrue(session.isEnded)
    }

    @Test
    fun `endedAt never precedes the start`() {
        val session = session()
        session.begin(location(0))
        wallMs = 500

        val finished = requireNotNull(session.finish(null))

        assertEquals(1_000L, finished.endedAtMs)
    }

    @Test
    fun `pausing twice counts one segment`() {
        val session = session()
        session.begin(location(0))
        session.setChapterReady(true)
        elapsedMs = 100
        session.resumeSegment()
        elapsedMs = 1_100

        session.pauseSegment()
        session.pauseSegment()

        val finished = requireNotNull(session.finish(null))
        assertEquals(1_000L, finished.activeReadingMillis)
    }

    @Test
    fun `a chapter that is no longer ready stops a later resume`() {
        val session = session()
        session.begin(location(0))
        session.setChapterReady(true)
        elapsedMs = 100
        session.resumeSegment()
        elapsedMs = 500
        session.setChapterReady(false)
        session.pauseSegment()
        elapsedMs = 1_000
        session.resumeSegment()
        elapsedMs = 2_000

        val finished = requireNotNull(session.finish(null))
        assertEquals(400L, finished.activeReadingMillis)
    }

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
