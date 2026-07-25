package com.exio.inkleaf.data

/**
 * Pure reducer for the single global reading session.
 *
 * Platform code (ReaderViewModel + ProcessLifecycleOwner) translates Android signals into
 * [ReadingSessionEvent] values. Configuration changes must never emit events — brief Activity
 * teardown is continuous reading.
 *
 * ## Event order contract
 *
 * Start requires three independent facts, in any arrival order:
 * 1. [ReadingSessionEvent.ReaderReady] — content opened (candidate page only)
 * 2. [ReadingSessionEvent.PageVisible] — a page is actually on screen
 * 3. [ReadingSessionEvent.EnteredInteractiveForeground] — app is interactive
 *
 * [interactiveForeground] defaults to false. If the process is already in the foreground when the
 * Reader attaches, the platform MUST still emit an explicit EnteredInteractiveForeground; otherwise
 * a session never starts.
 *
 * [ReadingSessionEvent.OpenComic] only identifies the comic. Source revision is compared later on
 * ReaderReady once the opened volume is known.
 *
 * After [ReadingSessionEvent.ProcessRestored], the slot stays PAUSED until the matching Reader
 * satisfies Ready + PageVisible + Foreground again.
 *
 * Spec: #14 boundaries, #15 status/permanence, #17 injectable clock + events.
 */
class ReadingSessionStateMachine(
    private val clock: ReadingClock,
    private val idGenerator: () -> String,
) {
    /** Current resumable session, or null when the global slot is empty. */
    var resumable: ResumableSession? = null
        private set

    /**
     * Wall-clock start of the current interactive foreground segment. Null while paused, completed,
     * or before the session has started timing.
     */
    var interactiveSegmentStartedAt: Long? = null
        private set

    /** Last page reported visible; drives checkpoint/complete payloads. */
    var lastVisiblePosition: ReadingPositionSnapshot? = null
        private set

    /** Comic the reader is attempting to open; set by OpenComic / cleared on leave. */
    var pendingComic: ReadingSessionComicRef? = null
        private set

    /** True after content Ready for [pendingComic]. */
    var contentReady: Boolean = false
        private set

    /**
     * True after at least one PageVisible for the current Ready book. Ready alone must not set this
     * — loading success ≠ page actually shown.
     */
    var pageVisible: Boolean = false
        private set

    /**
     * True while ProcessLifecycleOwner reports interactive foreground. Defaults false so a session
     * never starts without an explicit enter event.
     */
    var interactiveForeground: Boolean = false
        private set

    /** Clears in-memory session facts (history clear / test setup). */
    fun reset() {
        resumable = null
        interactiveSegmentStartedAt = null
        lastVisiblePosition = null
        pendingComic = null
        contentReady = false
        pageVisible = false
        interactiveForeground = false
    }

    fun onEvent(event: ReadingSessionEvent): List<ReadingSessionEffect> {
        return when (event) {
            is ReadingSessionEvent.OpenComic -> onOpenComic(event)
            is ReadingSessionEvent.ReaderReady -> onReaderReady(event)
            is ReadingSessionEvent.PageVisible -> onPageVisible(event)
            ReadingSessionEvent.EnteredInteractiveForeground -> onEnteredForeground()
            ReadingSessionEvent.LeftInteractiveForeground -> onLeftForeground()
            is ReadingSessionEvent.LeaveReader -> onLeaveReader(event.reason)
            ReadingSessionEvent.CheckpointTick -> onCheckpointTick()
            is ReadingSessionEvent.ProcessRestored -> onProcessRestored(event)
        }
    }

    private fun onOpenComic(event: ReadingSessionEvent.OpenComic): List<ReadingSessionEffect> {
        // Source revision is unknown until the volume opens (ReaderReady).
        //
        // Platform contract: emit LeaveReader(LEFT_READER) before replacing the
        // current Reader route. If an ACTIVE same-comic session is still open
        // here, treat the new Reader instance as an explicit leave so a fresh
        // session is created instead of silently merging two Reader lifetimes.
        val existing = resumable
        val effects =
            when {
                existing == null -> emptyList()
                existing.comic.fileKey != event.comic.fileKey -> {
                    settleForeignOrActive(
                        existing = existing,
                        reason = ReadingSessionEndReason.SWITCHED_COMIC,
                    )
                }
                existing.status == ReadingSessionStatus.ACTIVE &&
                    interactiveSegmentStartedAt != null -> {
                    settleForeignOrActive(
                        existing = existing,
                        reason = ReadingSessionEndReason.LEFT_READER,
                    )
                }
                else -> emptyList() // PAUSED same comic: keep for resume/timeout/source checks
            }

        pendingComic = event.comic
        contentReady = false
        pageVisible = false
        lastVisiblePosition = null
        return effects
    }

    private fun settleForeignOrActive(
        existing: ResumableSession,
        reason: ReadingSessionEndReason,
    ): List<ReadingSessionEffect> {
        val now = clock.nowMillis()
        val activelyTiming =
            existing.status == ReadingSessionStatus.ACTIVE && interactiveSegmentStartedAt != null
        // Settle before callers clear visible-page state so ACTIVE ends keep
        // the last actually-visible page and open foreground segment.
        return settle(
            current = existing,
            reason = reason,
            settlementInstant =
                settlementInstantFor(
                    existing,
                    now,
                    wasActivelyTiming = activelyTiming,
                ),
        )
    }

    private fun onReaderReady(event: ReadingSessionEvent.ReaderReady): List<ReadingSessionEffect> {
        contentReady = true
        // Candidate page only — never set pageVisible here. PageVisible is required.
        if (lastVisiblePosition == null) {
            lastVisiblePosition = event.initialPosition
        }

        val existing = resumable
        if (existing != null && pendingComic?.fileKey == existing.comic.fileKey) {
            val now = clock.nowMillis()
            val sameSource =
                existing.checkpointPosition.sourceRevision == event.initialPosition.sourceRevision
            val withinWindow =
                ReadingSessionRules.isWithinInterruptionWindow(existing.lastCheckpointAt, now)
            if (!sameSource || !withinWindow) {
                val reason =
                    when {
                        !sameSource -> ReadingSessionEndReason.SOURCE_CHANGED
                        else -> ReadingSessionEndReason.INTERRUPTION_TIMEOUT
                    }
                val activelyTiming =
                    existing.status == ReadingSessionStatus.ACTIVE &&
                        interactiveSegmentStartedAt != null
                val settled =
                    settle(
                        current = existing,
                        reason = reason,
                        settlementInstant =
                            settlementInstantFor(
                                existing,
                                now,
                                wasActivelyTiming = activelyTiming,
                            ),
                    )
                return settled + tryStart()
            }
        }
        return tryStart()
    }

    private fun onPageVisible(event: ReadingSessionEvent.PageVisible): List<ReadingSessionEffect> {
        lastVisiblePosition = event.position
        pageVisible = true
        val current = resumable
        if (current == null) {
            return tryStart()
        }
        // ACTIVE/PAUSED: keep latest page in memory; durable write on checkpoint/pause/leave.
        return emptyList()
    }

    private fun onEnteredForeground(): List<ReadingSessionEffect> {
        interactiveForeground = true
        val current = resumable
        if (current == null) {
            return tryStart()
        }
        // A restored or paused session must not start timing until Reader is
        // Ready + visible for the matching comic. Foreground alone is not enough.
        if (!contentReady || !pageVisible) {
            return emptyList()
        }
        if (pendingComic?.fileKey != null && pendingComic?.fileKey != current.comic.fileKey) {
            return emptyList()
        }
        if (current.status == ReadingSessionStatus.ACTIVE) {
            if (interactiveSegmentStartedAt == null) {
                interactiveSegmentStartedAt = clock.nowMillis()
            }
            return emptyList()
        }
        // PAUSED + Ready + visible + foreground → resume within window, else settle.
        return tryStart()
    }

    private fun onLeftForeground(): List<ReadingSessionEffect> {
        interactiveForeground = false
        val current = resumable ?: return emptyList()
        if (current.status != ReadingSessionStatus.ACTIVE) return emptyList()
        // Only pause timing if we were actually accumulating (Reader ready path).
        if (interactiveSegmentStartedAt == null && !contentReady) {
            // ACTIVE row that never started timing (shouldn't happen after normalize).
            return pauseActive(current, accumulateSegment = false)
        }
        return pauseActive(current, accumulateSegment = true)
    }

    private fun onLeaveReader(reason: ReadingSessionEndReason): List<ReadingSessionEffect> {
        val current = resumable
        pendingComic = null
        contentReady = false
        pageVisible = false
        val now = clock.nowMillis()
        val effects =
            if (current != null) {
                settle(
                    current = current,
                    reason = reason,
                    settlementInstant =
                        settlementInstantFor(
                            current,
                            now,
                            wasActivelyTiming =
                                current.status == ReadingSessionStatus.ACTIVE &&
                                    interactiveSegmentStartedAt != null,
                        ),
                )
            } else {
                emptyList()
            }
        interactiveSegmentStartedAt = null
        lastVisiblePosition = null
        return effects
    }

    private fun onCheckpointTick(): List<ReadingSessionEffect> {
        val current = resumable ?: return emptyList()
        if (current.status != ReadingSessionStatus.ACTIVE) return emptyList()
        if (interactiveSegmentStartedAt == null) return emptyList()
        val position = lastVisiblePosition ?: return emptyList()
        return checkpointActive(current, position)
    }

    private fun onProcessRestored(
        event: ReadingSessionEvent.ProcessRestored
    ): List<ReadingSessionEffect> {
        interactiveSegmentStartedAt = null
        contentReady = false
        pageVisible = false
        pendingComic = null
        interactiveForeground = false

        val existing = event.existing
        if (existing == null) {
            resumable = null
            lastVisiblePosition = null
            return emptyList()
        }

        // Normalize ACTIVE → PAUSED so process death never resumes a hot timer.
        val normalized =
            if (existing.status == ReadingSessionStatus.ACTIVE) {
                existing.copy(status = ReadingSessionStatus.PAUSED)
            } else {
                existing
            }
        lastVisiblePosition = normalized.checkpointPosition

        val now = clock.nowMillis()
        return when (val decision = ReadingSessionRules.decideProcessRecovery(normalized, now)) {
            ProcessRecoveryDecision.KeepResumable -> {
                resumable = normalized
                if (normalized.status != existing.status) {
                    listOf(ReadingSessionEffect.UpsertResumable(normalized))
                } else {
                    emptyList()
                }
            }
            is ProcessRecoveryDecision.Settle ->
                settle(
                    current = normalized,
                    reason = decision.reason,
                    settlementInstant =
                        SettlementInstant(
                            endedAt = normalized.lastCheckpointAt,
                            includeActiveSegment = false,
                            // Keep stored checkpoint time; do not rewrite to "now".
                            lastCheckpointAt = normalized.lastCheckpointAt,
                        ),
                )
        }
    }

    /** Starts or resumes only when Ready + page visible + interactive foreground. */
    private fun tryStart(): List<ReadingSessionEffect> {
        if (!contentReady || !pageVisible || !interactiveForeground) return emptyList()
        val comic = pendingComic ?: return emptyList()
        val position = lastVisiblePosition ?: return emptyList()
        val now = clock.nowMillis()
        val existing = resumable

        if (existing != null) {
            if (existing.comic.fileKey != comic.fileKey) {
                // Should have been settled on OpenComic; belt-and-suspenders.
                val activelyTiming =
                    existing.status == ReadingSessionStatus.ACTIVE &&
                        interactiveSegmentStartedAt != null
                return settle(
                    current = existing,
                    reason = ReadingSessionEndReason.SWITCHED_COMIC,
                    settlementInstant =
                        settlementInstantFor(
                            existing,
                            now,
                            wasActivelyTiming = activelyTiming,
                        ),
                ) + startNew(comic, position, now)
            }
            val sameSource = existing.checkpointPosition.sourceRevision == position.sourceRevision
            val withinWindow =
                ReadingSessionRules.isWithinInterruptionWindow(existing.lastCheckpointAt, now)
            if (!sameSource || !withinWindow) {
                val reason =
                    when {
                        !sameSource -> ReadingSessionEndReason.SOURCE_CHANGED
                        else -> ReadingSessionEndReason.INTERRUPTION_TIMEOUT
                    }
                return settle(
                    current = existing,
                    reason = reason,
                    settlementInstant =
                        settlementInstantFor(
                            existing,
                            now,
                            wasActivelyTiming = false,
                        ),
                ) + startNew(comic, position, now)
            }
            // Resume same session.
            val updated =
                existing.copy(
                    status = ReadingSessionStatus.ACTIVE,
                    checkpointPosition = position,
                )
            resumable = updated
            interactiveSegmentStartedAt = now
            return listOf(ReadingSessionEffect.UpsertResumable(updated))
        }
        return startNew(comic, position, now)
    }

    private fun startNew(
        comic: ReadingSessionComicRef,
        position: ReadingPositionSnapshot,
        now: Long,
    ): List<ReadingSessionEffect> {
        // Capture zone once at start; later device travel must not rewrite it.
        val session =
            ResumableSession(
                id = idGenerator(),
                comic = comic,
                status = ReadingSessionStatus.ACTIVE,
                startedAt = now,
                lastCheckpointAt = now,
                activeReadingMillis = 0L,
                startPosition = position,
                checkpointPosition = position,
                timeZoneId = clock.zoneId(),
                isPermanent = false,
            )
        resumable = session
        interactiveSegmentStartedAt = now
        lastVisiblePosition = position
        return listOf(ReadingSessionEffect.UpsertResumable(session))
    }

    private fun pauseActive(
        current: ResumableSession,
        accumulateSegment: Boolean,
    ): List<ReadingSessionEffect> {
        val now = clock.nowMillis()
        val position = lastVisiblePosition ?: current.checkpointPosition
        val delta =
            if (accumulateSegment) {
                ReadingSessionRules.segmentDurationMillis(interactiveSegmentStartedAt, now)
            } else {
                0L
            }
        val active = ReadingSessionRules.accumulateActiveReading(current.activeReadingMillis, delta)
        val permanent =
            current.isPermanent ||
                ReadingSessionRules.qualifiesAsPermanent(active, current.startPosition, position)
        val paused =
            current.copy(
                status = ReadingSessionStatus.PAUSED,
                lastCheckpointAt = now,
                activeReadingMillis = active,
                checkpointPosition = position,
                isPermanent = permanent,
            )
        resumable = paused
        interactiveSegmentStartedAt = null
        return listOf(ReadingSessionEffect.UpsertResumable(paused))
    }

    private fun checkpointActive(
        current: ResumableSession,
        position: ReadingPositionSnapshot,
    ): List<ReadingSessionEffect> {
        val now = clock.nowMillis()
        val delta = ReadingSessionRules.segmentDurationMillis(interactiveSegmentStartedAt, now)
        val active = ReadingSessionRules.accumulateActiveReading(current.activeReadingMillis, delta)
        val permanent =
            current.isPermanent ||
                ReadingSessionRules.qualifiesAsPermanent(active, current.startPosition, position)
        val updated =
            current.copy(
                lastCheckpointAt = now,
                activeReadingMillis = active,
                checkpointPosition = position,
                isPermanent = permanent,
            )
        resumable = updated
        interactiveSegmentStartedAt = now
        return listOf(ReadingSessionEffect.UpsertResumable(updated))
    }

    private fun settle(
        current: ResumableSession,
        reason: ReadingSessionEndReason,
        settlementInstant: SettlementInstant,
    ): List<ReadingSessionEffect> {
        val position =
            when {
                settlementInstant.includeActiveSegment ->
                    lastVisiblePosition ?: current.checkpointPosition
                else -> current.checkpointPosition
            }
        val delta =
            if (settlementInstant.includeActiveSegment) {
                ReadingSessionRules.segmentDurationMillis(
                    interactiveSegmentStartedAt,
                    settlementInstant.endedAt,
                )
            } else {
                0L
            }
        val active = ReadingSessionRules.accumulateActiveReading(current.activeReadingMillis, delta)
        val permanent =
            ReadingSessionRules.qualifiesAsPermanent(
                activeReadingMillis = active,
                startPosition = current.startPosition,
                latestPosition = position,
            )
        resumable = null
        interactiveSegmentStartedAt = null

        return if (permanent) {
            // Active leave/switch: checkpoint and end both land on the latest visible page.
            // Paused timeout/source/process recovery: keep stored checkpoint as end.
            val checkpointPosition =
                if (settlementInstant.includeActiveSegment) {
                    position
                } else {
                    current.checkpointPosition
                }
            val completed =
                CompletedSession(
                    id = current.id,
                    comic = current.comic,
                    startedAt = current.startedAt,
                    lastCheckpointAt = settlementInstant.lastCheckpointAt,
                    endedAt = settlementInstant.endedAt,
                    activeReadingMillis = active,
                    startPosition = current.startPosition,
                    checkpointPosition = checkpointPosition,
                    endPosition = position,
                    timeZoneId = current.timeZoneId,
                    endReason = reason,
                )
            listOf(ReadingSessionEffect.CompletePermanent(completed))
        } else {
            listOf(ReadingSessionEffect.DiscardTemporary(current.id))
        }
    }

    /**
     * Paused / recovered sessions end at their stored checkpoint time. Actively timing sessions end
     * at [now] and fold in the open segment.
     */
    private fun settlementInstantFor(
        current: ResumableSession,
        now: Long,
        wasActivelyTiming: Boolean,
    ): SettlementInstant {
        return if (wasActivelyTiming) {
            SettlementInstant(
                endedAt = now,
                includeActiveSegment = true,
                lastCheckpointAt = now,
            )
        } else {
            SettlementInstant(
                endedAt = current.lastCheckpointAt,
                includeActiveSegment = false,
                lastCheckpointAt = current.lastCheckpointAt,
            )
        }
    }

    private data class SettlementInstant(
        val endedAt: Long,
        val includeActiveSegment: Boolean,
        val lastCheckpointAt: Long,
    )
}

/**
 * Domain events. Configuration changes intentionally have no event — emitting nothing keeps the
 * session continuous across Activity recreation.
 */
sealed interface ReadingSessionEvent {
    /**
     * Reader is opening a comic. Source revision is unknown until the volume opens — only [comic]
     * is available here. Settles a different fileKey as SWITCHED_COMIC; same-key
     * resume/timeout/source checks wait for Ready.
     */
    data class OpenComic(val comic: ReadingSessionComicRef) : ReadingSessionEvent

    /**
     * Comic content reached Ready. Carries the opened volume's start position (including
     * sourceRevision) so deferred source checks can run.
     */
    data class ReaderReady(val initialPosition: ReadingPositionSnapshot) : ReadingSessionEvent

    /** A page became the actually visible page in the reader. */
    data class PageVisible(val position: ReadingPositionSnapshot) : ReadingSessionEvent

    /** App is interactive foreground (ProcessLifecycleOwner ON_RESUME). */
    data object EnteredInteractiveForeground : ReadingSessionEvent

    /**
     * App left interactive foreground (background, lock screen, multi-window unfocus, covering
     * Activity). Pauses timing; does not end the session.
     */
    data object LeftInteractiveForeground : ReadingSessionEvent

    /** User left the reader (back) or an explicit end is required. */
    data class LeaveReader(val reason: ReadingSessionEndReason) : ReadingSessionEvent

    /** Periodic foreground tick (every [ReadingSessionRules.CHECKPOINT_INTERVAL_MS]). */
    data object CheckpointTick : ReadingSessionEvent

    /** Hydrate machine from DB after process start. */
    data class ProcessRestored(val existing: ResumableSession?) : ReadingSessionEvent
}

/** Durable side effects the repository applies transactionally. */
sealed interface ReadingSessionEffect {
    data class UpsertResumable(val session: ResumableSession) : ReadingSessionEffect

    data class CompletePermanent(val session: CompletedSession) : ReadingSessionEffect

    data class DiscardTemporary(val sessionId: String) : ReadingSessionEffect
}
