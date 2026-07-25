package com.exio.inkleaf.data

/**
 * Pure reading-session policy: timing windows, promotion, and resume decisions.
 *
 * No Android, Room, or UI dependencies. Reader/platform code maps lifecycle signals into these
 * inputs; the repository applies the resulting decisions inside transactions.
 *
 * Spec: #14 session boundaries, #15 permanence and single resumable slot.
 */
object ReadingSessionRules {
    /** Return within this window after pause/background keeps the same session. */
    const val INTERRUPTION_WINDOW_MS: Long = 10 * 60 * 1000L

    /** Effective reading time that alone promotes a temporary session. */
    const val MIN_ACTIVE_READING_MS: Long = 30_000L

    /** Foreground checkpoint cadence while a session is ACTIVE. */
    const val CHECKPOINT_INTERVAL_MS: Long = 30_000L

    /**
     * A session enters permanent history when either threshold is met: 30s of effective reading, or
     * the end/checkpoint position differs from start.
     */
    fun qualifiesAsPermanent(
        activeReadingMillis: Long,
        startPosition: ReadingPositionSnapshot,
        latestPosition: ReadingPositionSnapshot,
    ): Boolean {
        require(activeReadingMillis >= 0)
        if (activeReadingMillis >= MIN_ACTIVE_READING_MS) return true
        return latestPosition.differsFrom(startPosition)
    }

    fun isWithinInterruptionWindow(lastCheckpointAt: Long, nowMillis: Long): Boolean {
        require(lastCheckpointAt >= 0)
        require(nowMillis >= 0)
        val elapsed = nowMillis - lastCheckpointAt
        return elapsed in 0 until INTERRUPTION_WINDOW_MS
    }

    /**
     * Adds [deltaMillis] of interactive foreground time. Negative deltas are rejected; zero is a
     * no-op.
     */
    fun accumulateActiveReading(currentMillis: Long, deltaMillis: Long): Long {
        require(currentMillis >= 0)
        require(deltaMillis >= 0) { "Active reading delta cannot be negative" }
        return currentMillis + deltaMillis
    }

    /**
     * Interactive segment length from [segmentStartedAt] to [nowMillis]. Null segment start means
     * timing was already paused — contribute 0.
     */
    fun segmentDurationMillis(segmentStartedAt: Long?, nowMillis: Long): Long {
        if (segmentStartedAt == null) return 0L
        require(nowMillis >= segmentStartedAt) {
            "Clock moved backwards during an active reading segment"
        }
        return nowMillis - segmentStartedAt
    }

    /**
     * Decide whether opening [incomingFileKey] should resume [existing] or settle it and start
     * fresh.
     *
     * Explicit leave already COMPLETED the prior session, so this only sees ACTIVE/PAUSED rows
     * (process recovery or background return).
     *
     * Source identity is the checkpoint revision — the last content version this session was
     * actually reading.
     */
    fun decideResume(
        existing: ResumableSession,
        incomingFileKey: String,
        incomingSourceRevision: String,
        nowMillis: Long,
    ): ResumeDecision {
        val sameComic = existing.comic.fileKey == incomingFileKey
        val sameSource = existing.checkpointPosition.sourceRevision == incomingSourceRevision
        val withinWindow = isWithinInterruptionWindow(existing.lastCheckpointAt, nowMillis)

        return when {
            sameComic && sameSource && withinWindow -> ResumeDecision.Resume(existing.id)
            !sameComic ->
                ResumeDecision.SettleThenStart(
                    sessionId = existing.id,
                    reason = ReadingSessionEndReason.SWITCHED_COMIC,
                )
            !sameSource ->
                ResumeDecision.SettleThenStart(
                    sessionId = existing.id,
                    reason = ReadingSessionEndReason.SOURCE_CHANGED,
                )
            else ->
                ResumeDecision.SettleThenStart(
                    sessionId = existing.id,
                    reason = ReadingSessionEndReason.INTERRUPTION_TIMEOUT,
                )
        }
    }

    /**
     * After process restart, an orphan ACTIVE/PAUSED row is either still resumable or settled at
     * its last checkpoint.
     */
    fun decideProcessRecovery(
        existing: ResumableSession,
        nowMillis: Long,
    ): ProcessRecoveryDecision {
        return if (isWithinInterruptionWindow(existing.lastCheckpointAt, nowMillis)) {
            ProcessRecoveryDecision.KeepResumable
        } else {
            ProcessRecoveryDecision.Settle(
                sessionId = existing.id,
                reason = ReadingSessionEndReason.PROCESS_RECOVERY,
            )
        }
    }
}

/** Outcome of matching an incoming open against the global resumable slot. */
sealed interface ResumeDecision {
    data class Resume(val sessionId: String) : ResumeDecision

    data class SettleThenStart(
        val sessionId: String,
        val reason: ReadingSessionEndReason,
    ) : ResumeDecision
}

/** Outcome of inspecting a leftover resumable row after process death. */
sealed interface ProcessRecoveryDecision {
    data object KeepResumable : ProcessRecoveryDecision

    data class Settle(
        val sessionId: String,
        val reason: ReadingSessionEndReason,
    ) : ProcessRecoveryDecision
}
