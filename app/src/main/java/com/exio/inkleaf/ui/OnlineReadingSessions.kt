package com.exio.inkleaf.ui

import com.exio.inkleaf.data.OnlineContentIdentity
import com.exio.inkleaf.data.OnlinePageLocation
import com.exio.inkleaf.data.ReadingSessionRules
import com.exio.inkleaf.plugin.OnlineReadingSessionRecord

/**
 * Pure policy for the online reader's own reading session.
 *
 * The online reader keeps a lighter session than the offline machine in [ReadingSessionRules] — no
 * resumable slot, no interruption windows — but it must agree with that module on when a session is
 * worth keeping, so the shared threshold is read from there rather than restated here.
 */
internal object OnlineReadingSessions {
    /**
     * The history record for a finished online session, or null when the session should be dropped:
     * either the reader never captured a start/end location, or the session was too brief to leave a
     * trace.
     */
    fun record(
        sessionId: String?,
        content: OnlineContentIdentity,
        titleSnapshot: String,
        startedAtMs: Long,
        endedAtMs: Long,
        activeReadingMillis: Long,
        timeZoneId: String,
        start: OnlinePageLocation?,
        end: OnlinePageLocation?,
    ): OnlineReadingSessionRecord? {
        if (sessionId == null || start == null || end == null) return null
        if (!qualifiesAsPermanent(activeReadingMillis, start, end)) return null
        return OnlineReadingSessionRecord(
            sessionId = sessionId,
            content = content,
            titleSnapshot = titleSnapshot,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            activeReadingMillis = activeReadingMillis,
            timeZoneId = timeZoneId,
            start = start,
            end = end,
        )
    }

    /** Same threshold as the offline history: enough reading time, or a position that moved. */
    private fun qualifiesAsPermanent(
        activeReadingMillis: Long,
        start: OnlinePageLocation,
        end: OnlinePageLocation,
    ): Boolean =
        activeReadingMillis >= ReadingSessionRules.MIN_ACTIVE_READING_MS || start != end
}
