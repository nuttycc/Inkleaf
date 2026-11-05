package com.exio.inkleaf.ui

import com.exio.inkleaf.data.OnlineContentIdentity
import com.exio.inkleaf.data.OnlinePageLocation
import com.exio.inkleaf.data.ReadingPosition
import com.exio.inkleaf.data.ReadingPositionSnapshot
import com.exio.inkleaf.data.ReadingSessionComicRef
import com.exio.inkleaf.data.ReadingSessionEndReason
import com.exio.inkleaf.data.ReadingSessionEvent
import com.exio.inkleaf.data.ReadingSessionRepository
import com.exio.inkleaf.data.ReadingSessionRules
import com.exio.inkleaf.plugin.OnlineReadingSessionRecord
import java.util.TimeZone
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What both readers report about one reading visit.
 *
 * The recorders translate those moments into their own persistence: the offline adapter dispatches
 * the events that own the resumable slot, the interruption window, the checkpoint loop and process
 * recovery; the online adapter drives the lighter in-memory session that ends in one history
 * record. Readers only decide when each moment happens.
 */
internal interface ReaderSessionRecorder<P : ReadingPosition<P>> {
    /** True once the visit has ended; later calls are ignored by the implementations. */
    val isEnded: Boolean

    /** The reader opened content. Idempotent within one reader lifetime. */
    fun begin()

    /** [page] is readable and on screen; null means the reader has no readable page right now. */
    fun pageVisible(page: P?)

    /** The app entered or left interactive foreground. */
    fun foregroundChanged(foreground: Boolean)

    /** A periodic durable checkpoint. Implementations without one ignore it. */
    fun checkpoint()

    /** Ends the visit once. */
    fun end(reason: ReadingSessionEndReason, finalPage: P?)
}

/**
 * Offline adapter over [ReadingSessionRepository].
 *
 * Persistence semantics stay in the machine behind the repository: this class only turns the
 * interface calls into the events it already understands, and owns the checkpoint loop so the
 * reader no longer schedules it. [openScope] matches the old per-ViewModel dispatch (cancelled with
 * the reader); [terminalScope] matches the application-scoped pause/leave dispatch.
 */
internal class OfflineReaderSessionRecorder(
    private val repository: ReadingSessionRepository,
    private val comic: ReadingSessionComicRef,
    private val openScope: CoroutineScope,
    private val terminalScope: CoroutineScope,
    private val checkpointIntervalMillis: Long = ReadingSessionRules.CHECKPOINT_INTERVAL_MS,
) : ReaderSessionRecorder<ReadingPositionSnapshot> {
    private var ended = false
    private var readyReported = false
    private var checkpointJob: Job? = null

    override val isEnded: Boolean
        get() = ended

    override fun begin() {
        if (ended) return
        dispatch(ReadingSessionEvent.OpenComic(comic))
    }

    override fun pageVisible(page: ReadingPositionSnapshot?) {
        if (ended || page == null) return
        // The first readable page proves both that content is ready and that a page is on screen.
        if (!readyReported) {
            readyReported = true
            dispatch(ReadingSessionEvent.ReaderReady(page))
        }
        dispatch(ReadingSessionEvent.PageVisible(page))
    }

    override fun foregroundChanged(foreground: Boolean) {
        if (ended) return
        if (foreground) {
            dispatch(ReadingSessionEvent.EnteredInteractiveForeground)
            startCheckpointLoop()
        } else {
            stopCheckpointLoop()
            dispatchTerminal(ReadingSessionEvent.LeftInteractiveForeground)
        }
    }

    override fun checkpoint() {
        if (ended) return
        dispatch(ReadingSessionEvent.CheckpointTick)
    }

    override fun end(
        reason: ReadingSessionEndReason,
        finalPage: ReadingPositionSnapshot?,
    ) {
        if (ended) return
        ended = true
        stopCheckpointLoop()
        dispatchTerminal(ReadingSessionEvent.LeaveReader(reason))
    }

    private fun startCheckpointLoop() {
        if (checkpointJob?.isActive == true) return
        checkpointJob = openScope.launch {
            while (true) {
                delay(checkpointIntervalMillis.milliseconds)
                if (ended) break
                checkpoint()
            }
        }
    }

    private fun stopCheckpointLoop() {
        checkpointJob?.cancel()
        checkpointJob = null
    }

    private fun dispatch(event: ReadingSessionEvent) {
        openScope.launch {
            try {
                repository.dispatch(event)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Session tracking must not break reading.
            }
        }
    }

    private fun dispatchTerminal(event: ReadingSessionEvent) {
        // Start immediately so the terminal event is queued before navigation creates another
        // Reader.
        terminalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                repository.dispatch(event)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Session tracking must not break navigation or process-owned cleanup.
            }
        }
    }
}

/**
 * Online adapter over [OnlineReadingSession].
 *
 * No periodic checkpoint exists here: a segment boundary (pause, chapter change, end) is the only
 * moment active time is accumulated. [onRecord] receives an already-filtered history record, so the
 * eligibility rule stays in [OnlineReadingSessions].
 */
internal class OnlineReaderSessionRecorder(
    private val session: OnlineReadingSession,
    private val content: OnlineContentIdentity,
    private val title: () -> String,
    private val pageAt: () -> OnlinePageLocation?,
    private val timeZoneId: () -> String = { TimeZone.getDefault().id },
    private val onRecord: (OnlineReadingSessionRecord) -> Unit,
) : ReaderSessionRecorder<OnlinePageLocation> {
    private var foreground = false

    override val isEnded: Boolean
        get() = session.isEnded

    override fun begin() {
        if (isEnded) return
        val page = pageAt() ?: return
        if (session.hasStarted) session.moveTo(page) else session.begin(page)
    }

    override fun pageVisible(page: OnlinePageLocation?) {
        if (isEnded) return
        if (page == null) {
            session.pauseSegment()
            session.setChapterReady(false)
            return
        }
        session.moveTo(page)
        session.setChapterReady(true)
        if (foreground) session.resumeSegment()
    }

    override fun foregroundChanged(foreground: Boolean) {
        if (isEnded) return
        this.foreground = foreground
        if (foreground) session.resumeSegment() else session.pauseSegment()
    }

    override fun checkpoint() = Unit

    override fun end(
        reason: ReadingSessionEndReason,
        finalPage: OnlinePageLocation?,
    ) {
        if (isEnded) return
        val finished = session.finish(finalPage ?: pageAt()) ?: return
        foreground = false
        val record =
            OnlineReadingSessions.record(
                sessionId = finished.sessionId,
                content = content,
                titleSnapshot = title(),
                startedAtMs = finished.startedAtMs,
                endedAtMs = finished.endedAtMs,
                activeReadingMillis = finished.activeReadingMillis,
                timeZoneId = timeZoneId(),
                start = finished.start,
                end = finished.end,
            ) ?: return
        onRecord(record)
    }
}
