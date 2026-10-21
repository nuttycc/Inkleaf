package com.exio.inkleaf.ui

import com.exio.inkleaf.data.OnlinePageLocation
import com.exio.inkleaf.data.ReadingSessionRules
import java.util.UUID

/** The facts a finished online reading session contributes to history. */
internal data class FinishedOnlineSession(
    val sessionId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val activeReadingMillis: Long,
    val start: OnlinePageLocation,
    val end: OnlinePageLocation,
)

/**
 * Owns one online reading session's lifetime: when it starts, which position is latest, how much
 * foreground time counts, and when it ends.
 *
 * Wall time stamps the history record; elapsed time measures active segments, so a wall-clock
 * change never shrinks or rejects a segment. The reader reports chapter readiness and process
 * pauses; the module decides whether a segment is running. The eligibility filter stays in
 * [OnlineReadingSessions.record], which is applied by the caller to the finished facts.
 */
internal class OnlineReadingSession(
    private val elapsedMs: () -> Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
) {
    private var sessionId: String? = null
    private var startedAtMs: Long = 0L
    private var start: OnlinePageLocation? = null
    private var latest: OnlinePageLocation? = null
    private var activeReadingMillis: Long = 0L
    private var segmentStartedAtElapsedMs: Long? = null
    private var chapterReady = false
    private var ended = false

    val hasStarted: Boolean
        get() = sessionId != null

    val isEnded: Boolean
        get() = ended

    /** Starts the session at the first readable position. */
    fun begin(initialLocation: OnlinePageLocation) {
        if (ended) return
        sessionId = newSessionId()
        startedAtMs = nowMs()
        start = initialLocation
        latest = initialLocation
        activeReadingMillis = 0L
        segmentStartedAtElapsedMs = null
        chapterReady = false
    }

    /** Moves the latest position; a finish without a final page falls back to it. */
    fun moveTo(location: OnlinePageLocation) {
        latest = location
    }

    /** Gates active time on the current chapter being readable. */
    fun setChapterReady(ready: Boolean) {
        chapterReady = ready
    }

    /** Starts a foreground segment when the chapter is readable and none is running. */
    fun resumeSegment() {
        if (!chapterReady || ended || segmentStartedAtElapsedMs != null) return
        segmentStartedAtElapsedMs = elapsedMs()
    }

    /** Accumulates the running segment, if any. */
    fun pauseSegment() {
        val started = segmentStartedAtElapsedMs ?: return
        activeReadingMillis +=
            ReadingSessionRules.segmentDurationMillis(started, elapsedMs())
        segmentStartedAtElapsedMs = null
    }

    /**
     * Ends the session once. The end position is [finalLocation] when the reader still has one,
     * otherwise the latest position seen. Returns null when the session was already ended or never
     * began.
     */
    fun finish(finalLocation: OnlinePageLocation?): FinishedOnlineSession? {
        if (ended) return null
        ended = true
        pauseSegment()
        val id = sessionId ?: return null
        val start = start ?: return null
        val end = finalLocation ?: latest ?: return null
        val endedAtMs = nowMs().coerceAtLeast(startedAtMs)
        return FinishedOnlineSession(
            sessionId = id,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            activeReadingMillis = activeReadingMillis,
            start = start,
            end = end,
        )
    }
}
