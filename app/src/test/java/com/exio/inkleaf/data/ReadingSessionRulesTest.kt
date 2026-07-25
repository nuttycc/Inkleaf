package com.exio.inkleaf.data

import com.exio.inkleaf.data.db.BookSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSessionRulesTest {
    private val start = position(global = 10, chapter = 0, page = 10, identity = "p10")
    private val moved = position(global = 12, chapter = 0, page = 12, identity = "p12")

    @Test
    fun `permanent when effective reading reaches 30 seconds without page change`() {
        assertTrue(
            ReadingSessionRules.qualifiesAsPermanent(
                activeReadingMillis = 30_000L,
                startPosition = start,
                latestPosition = start,
            )
        )
        assertFalse(
            ReadingSessionRules.qualifiesAsPermanent(
                activeReadingMillis = 29_999L,
                startPosition = start,
                latestPosition = start,
            )
        )
    }

    @Test
    fun `permanent when page changes even under 30 seconds`() {
        assertTrue(
            ReadingSessionRules.qualifiesAsPermanent(
                activeReadingMillis = 1_000L,
                startPosition = start,
                latestPosition = moved,
            )
        )
    }

    @Test
    fun `interruption window is half-open up to 10 minutes`() {
        val pausedAt = 1_000_000L
        assertTrue(ReadingSessionRules.isWithinInterruptionWindow(pausedAt, pausedAt))
        assertTrue(
            ReadingSessionRules.isWithinInterruptionWindow(
                pausedAt,
                pausedAt + ReadingSessionRules.INTERRUPTION_WINDOW_MS - 1,
            )
        )
        assertFalse(
            ReadingSessionRules.isWithinInterruptionWindow(
                pausedAt,
                pausedAt + ReadingSessionRules.INTERRUPTION_WINDOW_MS,
            )
        )
    }

    @Test
    fun `accumulate active reading rejects negative deltas`() {
        assertEquals(5_000L, ReadingSessionRules.accumulateActiveReading(5_000L, 0L))
        assertEquals(8_000L, ReadingSessionRules.accumulateActiveReading(5_000L, 3_000L))
    }

    @Test
    fun `segment duration is zero when timing is paused`() {
        assertEquals(0L, ReadingSessionRules.segmentDurationMillis(null, 100L))
        assertEquals(40L, ReadingSessionRules.segmentDurationMillis(60L, 100L))
    }

    @Test
    fun `resume same comic within window`() {
        val existing = resumable(lastCheckpointAt = 10_000L)
        val decision =
            ReadingSessionRules.decideResume(
                existing = existing,
                incomingFileKey = existing.comic.fileKey,
                incomingSourceRevision = existing.checkpointPosition.sourceRevision,
                nowMillis = 10_000L + ReadingSessionRules.INTERRUPTION_WINDOW_MS - 1,
            )
        assertEquals(ResumeDecision.Resume(existing.id), decision)
    }

    @Test
    fun `timeout after interruption window settles and starts new`() {
        val existing = resumable(lastCheckpointAt = 10_000L)
        val decision =
            ReadingSessionRules.decideResume(
                existing = existing,
                incomingFileKey = existing.comic.fileKey,
                incomingSourceRevision = existing.checkpointPosition.sourceRevision,
                nowMillis = 10_000L + ReadingSessionRules.INTERRUPTION_WINDOW_MS,
            )
        assertEquals(
            ResumeDecision.SettleThenStart(
                sessionId = existing.id,
                reason = ReadingSessionEndReason.INTERRUPTION_TIMEOUT,
            ),
            decision,
        )
    }

    @Test
    fun `source revision change settles old session`() {
        val existing = resumable(lastCheckpointAt = 10_000L)
        val decision =
            ReadingSessionRules.decideResume(
                existing = existing,
                incomingFileKey = existing.comic.fileKey,
                incomingSourceRevision = "rev-b",
                nowMillis = 10_500L,
            )
        assertEquals(
            ResumeDecision.SettleThenStart(
                sessionId = existing.id,
                reason = ReadingSessionEndReason.SOURCE_CHANGED,
            ),
            decision,
        )
    }

    @Test
    fun `different comic settles with switched reason`() {
        val existing = resumable(lastCheckpointAt = 10_000L)
        val decision =
            ReadingSessionRules.decideResume(
                existing = existing,
                incomingFileKey = "other-book",
                incomingSourceRevision = existing.checkpointPosition.sourceRevision,
                nowMillis = 10_500L,
            )
        assertEquals(
            ResumeDecision.SettleThenStart(
                sessionId = existing.id,
                reason = ReadingSessionEndReason.SWITCHED_COMIC,
            ),
            decision,
        )
    }

    @Test
    fun `process recovery keeps resumable inside window`() {
        val existing = resumable(lastCheckpointAt = 50_000L)
        assertEquals(
            ProcessRecoveryDecision.KeepResumable,
            ReadingSessionRules.decideProcessRecovery(existing, 50_000L + 60_000L),
        )
    }

    @Test
    fun `process recovery settles outside window`() {
        val existing = resumable(lastCheckpointAt = 50_000L)
        assertEquals(
            ProcessRecoveryDecision.Settle(
                sessionId = existing.id,
                reason = ReadingSessionEndReason.PROCESS_RECOVERY,
            ),
            ReadingSessionRules.decideProcessRecovery(
                existing,
                50_000L + ReadingSessionRules.INTERRUPTION_WINDOW_MS,
            ),
        )
    }

    @Test
    fun `position differs when only page identity changes`() {
        val a = position(global = 5, chapter = 0, page = 5, identity = "old")
        val b = position(global = 5, chapter = 0, page = 5, identity = "new")
        assertTrue(a.differsFrom(b))
    }

    private fun position(
        global: Int,
        chapter: Int,
        page: Int,
        identity: String?,
        revision: String = "rev-a",
    ) =
        ReadingPositionSnapshot(
            pageIdentity = identity,
            globalPageIndex = global,
            chapterIndex = chapter,
            pageIndex = page,
            chapterTitle = "Ch ${chapter + 1}",
            sourceRevision = revision,
        )

    private fun resumable(lastCheckpointAt: Long) =
        ResumableSession(
            id = "session-1",
            comic =
                ReadingSessionComicRef(
                    fileKey = "book-a",
                    titleSnapshot = "Book A",
                    sourceType = BookSourceType.EXTERNAL_ARCHIVE,
                ),
            status = ReadingSessionStatus.PAUSED,
            startedAt = (lastCheckpointAt - 60_000L).coerceAtLeast(0L),
            lastCheckpointAt = lastCheckpointAt,
            activeReadingMillis = 5_000L,
            startPosition = start,
            checkpointPosition = start,
            timeZoneId = "UTC",
            isPermanent = false,
        )
}
