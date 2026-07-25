package com.exio.inkleaf.data

import com.exio.inkleaf.data.db.BookSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReadingSessionStateMachineTest {
    private lateinit var clock: FakeReadingClock
    private lateinit var machine: ReadingSessionStateMachine
    private var nextId = 0

    private val comic =
        ReadingSessionComicRef(
            fileKey = "book-a",
            titleSnapshot = "Book A",
            sourceType = BookSourceType.EXTERNAL_ARCHIVE,
        )
    private val startPos = position(10)
    private val movedPos = position(12)

    @Before
    fun setUp() {
        clock = FakeReadingClock(initialMillis = 1_000_000L)
        nextId = 0
        machine =
            ReadingSessionStateMachine(
                clock = clock,
                idGenerator = { "s-${++nextId}" },
            )
    }

    @Test
    fun `open alone does not start a session`() {
        val effects = machine.onEvent(ReadingSessionEvent.OpenComic(comic))
        assertTrue(effects.isEmpty())
        assertNull(machine.resumable)
    }

    @Test
    fun `ready alone does not start a session`() {
        machine.onEvent(ReadingSessionEvent.OpenComic(comic))
        machine.onEvent(ReadingSessionEvent.EnteredInteractiveForeground)
        val effects = machine.onEvent(ReadingSessionEvent.ReaderReady(startPos))
        assertTrue(effects.isEmpty())
        assertNull(machine.resumable)
        assertTrue(machine.contentReady)
        assertTrue(!machine.pageVisible)
    }

    @Test
    fun `visible page without foreground does not start`() {
        machine.onEvent(ReadingSessionEvent.OpenComic(comic))
        machine.onEvent(ReadingSessionEvent.ReaderReady(startPos))
        val effects = machine.onEvent(ReadingSessionEvent.PageVisible(startPos))
        assertTrue(effects.isEmpty())
        assertNull(machine.resumable)
        assertTrue(machine.pageVisible)
        assertTrue(!machine.interactiveForeground)
    }

    @Test
    fun `all three start predicates required`() {
        machine.onEvent(ReadingSessionEvent.OpenComic(comic))
        machine.onEvent(ReadingSessionEvent.ReaderReady(startPos))
        machine.onEvent(ReadingSessionEvent.PageVisible(startPos))
        assertNull(machine.resumable)

        val effects = machine.onEvent(ReadingSessionEvent.EnteredInteractiveForeground)
        assertEquals(1, effects.filterIsInstance<ReadingSessionEffect.UpsertResumable>().size)
        val session = machine.resumable!!
        assertEquals(ReadingSessionStatus.ACTIVE, session.status)
        assertEquals(false, session.isPermanent)
        assertEquals(startPos, session.startPosition)
        assertEquals("UTC", session.timeZoneId)
    }

    @Test
    fun `background time is not counted as active reading`() {
        openReadyVisibleForeground()
        clock.advanceBy(5_000L)
        machine.onEvent(ReadingSessionEvent.LeftInteractiveForeground)
        val paused = machine.resumable!!
        assertEquals(ReadingSessionStatus.PAUSED, paused.status)
        assertEquals(5_000L, paused.activeReadingMillis)

        clock.advanceBy(180_000L)
        // Same Reader is still Ready+visible while backgrounded, so returning to
        // foreground resumes the open session immediately.
        machine.onEvent(ReadingSessionEvent.EnteredInteractiveForeground)
        clock.advanceBy(2_000L)
        machine.onEvent(ReadingSessionEvent.CheckpointTick)
        assertEquals(7_000L, machine.resumable!!.activeReadingMillis)
    }

    @Test
    fun `return within window resumes same session id`() {
        openReadyVisibleForeground()
        val id = machine.resumable!!.id
        machine.onEvent(ReadingSessionEvent.LeftInteractiveForeground)
        clock.advanceBy(60_000L)
        machine.onEvent(ReadingSessionEvent.EnteredInteractiveForeground)
        assertEquals(id, machine.resumable!!.id)
        assertEquals(ReadingSessionStatus.ACTIVE, machine.resumable!!.status)
    }

    @Test
    fun `explicit leave completes and immediate reopen starts a new session`() {
        openReadyVisibleForeground()
        clock.advanceBy(40_000L)
        val firstId = machine.resumable!!.id
        val leaveEffects =
            machine.onEvent(ReadingSessionEvent.LeaveReader(ReadingSessionEndReason.LEFT_READER))
        val completed = leaveEffects.filterIsInstance<ReadingSessionEffect.CompletePermanent>()
        assertEquals(1, completed.size)
        assertEquals(firstId, completed.single().session.id)
        assertEquals(clock.nowMillis(), completed.single().session.endedAt)
        assertNull(machine.resumable)

        openReadyVisibleForeground()
        assertEquals("s-2", machine.resumable!!.id)
        assertTrue(machine.resumable!!.id != firstId)
    }

    @Test
    fun `OpenComic while ACTIVE same comic settles leave and allows a new session`() {
        openReadyVisibleForeground()
        // Page change promotes; under 30s still permanent via position change.
        machine.onEvent(ReadingSessionEvent.PageVisible(movedPos))
        clock.advanceBy(8_000L)
        val firstId = machine.resumable!!.id
        val now = clock.nowMillis()
        val openEffects = machine.onEvent(ReadingSessionEvent.OpenComic(comic))
        val settled =
            openEffects.filterIsInstance<ReadingSessionEffect.CompletePermanent>().single()
        assertEquals(firstId, settled.session.id)
        assertEquals(ReadingSessionEndReason.LEFT_READER, settled.session.endReason)
        assertEquals(now, settled.session.endedAt)
        assertEquals(8_000L, settled.session.activeReadingMillis)
        assertNull(machine.resumable)

        openReadyVisibleForeground()
        assertEquals("s-2", machine.resumable!!.id)
        assertTrue(machine.resumable!!.id != firstId)
    }

    @Test
    fun `short unmoved session is discarded on leave`() {
        openReadyVisibleForeground()
        clock.advanceBy(10_000L)
        val effects =
            machine.onEvent(ReadingSessionEvent.LeaveReader(ReadingSessionEndReason.LEFT_READER))
        assertEquals(
            listOf(ReadingSessionEffect.DiscardTemporary("s-1")),
            effects,
        )
        assertNull(machine.resumable)
    }

    @Test
    fun `page change under 30 seconds promotes on leave`() {
        openReadyVisibleForeground()
        clock.advanceBy(5_000L)
        machine.onEvent(ReadingSessionEvent.PageVisible(movedPos))
        val effects =
            machine.onEvent(ReadingSessionEvent.LeaveReader(ReadingSessionEndReason.LEFT_READER))
        val completed = effects.filterIsInstance<ReadingSessionEffect.CompletePermanent>()
        assertEquals(1, completed.size)
        assertEquals(movedPos, completed.single().session.endPosition)
        assertEquals(5_000L, completed.single().session.activeReadingMillis)
    }

    @Test
    fun `checkpoint stores last actually visible page`() {
        openReadyVisibleForeground()
        machine.onEvent(ReadingSessionEvent.PageVisible(movedPos))
        clock.advanceBy(ReadingSessionRules.CHECKPOINT_INTERVAL_MS)
        machine.onEvent(ReadingSessionEvent.CheckpointTick)
        assertEquals(movedPos, machine.resumable!!.checkpointPosition)
        assertEquals(
            ReadingSessionRules.CHECKPOINT_INTERVAL_MS,
            machine.resumable!!.activeReadingMillis,
        )
    }

    @Test
    fun `interruption timeout ends at pause checkpoint not return time`() {
        openReadyVisibleForeground()
        clock.advanceBy(40_000L)
        machine.onEvent(ReadingSessionEvent.LeftInteractiveForeground)
        val oldId = machine.resumable!!.id
        val pauseCheckpoint = machine.resumable!!.lastCheckpointAt
        val pausedActive = machine.resumable!!.activeReadingMillis

        clock.advanceBy(ReadingSessionRules.INTERRUPTION_WINDOW_MS)
        val returnTime = clock.nowMillis()
        val effects = machine.onEvent(ReadingSessionEvent.EnteredInteractiveForeground)
        val settled = effects.filterIsInstance<ReadingSessionEffect.CompletePermanent>().single()
        assertEquals(oldId, settled.session.id)
        assertEquals(ReadingSessionEndReason.INTERRUPTION_TIMEOUT, settled.session.endReason)
        assertEquals(pauseCheckpoint, settled.session.endedAt)
        assertEquals(pauseCheckpoint, settled.session.lastCheckpointAt)
        assertEquals(pausedActive, settled.session.activeReadingMillis)
        assertTrue(settled.session.endedAt < returnTime)
        assertEquals("s-2", machine.resumable!!.id)
    }

    @Test
    fun `configuration change emits no events so session stays active`() {
        openReadyVisibleForeground()
        val before = machine.resumable
        // No events during config change — machine untouched.
        assertEquals(before, machine.resumable)
        assertEquals(ReadingSessionStatus.ACTIVE, machine.resumable!!.status)
        assertTrue(machine.interactiveSegmentStartedAt != null)
    }

    @Test
    fun `source change after permanent session settles with SOURCE_CHANGED at checkpoint`() {
        openReadyVisibleForeground()
        clock.advanceBy(40_000L)
        machine.onEvent(ReadingSessionEvent.LeftInteractiveForeground)
        val pauseCheckpoint = machine.resumable!!.lastCheckpointAt
        val oldId = machine.resumable!!.id

        // Same comic reopened after content revision changed.
        machine.onEvent(ReadingSessionEvent.OpenComic(comic))
        machine.onEvent(ReadingSessionEvent.EnteredInteractiveForeground)
        val readyPos = position(10, revision = "rev-b")
        val readyEffects = machine.onEvent(ReadingSessionEvent.ReaderReady(readyPos))
        val settled =
            readyEffects.filterIsInstance<ReadingSessionEffect.CompletePermanent>().single()
        assertEquals(oldId, settled.session.id)
        assertEquals(ReadingSessionEndReason.SOURCE_CHANGED, settled.session.endReason)
        assertEquals(pauseCheckpoint, settled.session.endedAt)
        assertEquals(pauseCheckpoint, settled.session.lastCheckpointAt)
        // OpenComic cleared pageVisible — Ready must not start the replacement yet.
        assertTrue(readyEffects.filterIsInstance<ReadingSessionEffect.UpsertResumable>().isEmpty())
        assertNull(machine.resumable)

        val visibleEffects = machine.onEvent(ReadingSessionEvent.PageVisible(readyPos))
        assertEquals(
            1,
            visibleEffects.filterIsInstance<ReadingSessionEffect.UpsertResumable>().size,
        )
        assertEquals("s-2", machine.resumable!!.id)
    }

    @Test
    fun `active switch settles immediately including open foreground segment`() {
        openReadyVisibleForeground()
        machine.onEvent(ReadingSessionEvent.PageVisible(movedPos))
        clock.advanceBy(5_000L)
        val now = clock.nowMillis()
        val other = comic.copy(fileKey = "book-b", titleSnapshot = "Book B")
        val effects = machine.onEvent(ReadingSessionEvent.OpenComic(other))
        val settled = effects.filterIsInstance<ReadingSessionEffect.CompletePermanent>().single()
        assertEquals(ReadingSessionEndReason.SWITCHED_COMIC, settled.session.endReason)
        assertEquals(now, settled.session.endedAt)
        assertEquals(5_000L, settled.session.activeReadingMillis)
        assertEquals(movedPos, settled.session.endPosition)
        assertNull(machine.resumable)
    }

    @Test
    fun `paused switch settles previous resumable at checkpoint`() {
        openReadyVisibleForeground()
        clock.advanceBy(40_000L)
        machine.onEvent(ReadingSessionEvent.LeftInteractiveForeground)
        val pauseCheckpoint = machine.resumable!!.lastCheckpointAt
        val other = comic.copy(fileKey = "book-b", titleSnapshot = "Book B")
        val effects = machine.onEvent(ReadingSessionEvent.OpenComic(other))
        val settled = effects.filterIsInstance<ReadingSessionEffect.CompletePermanent>().single()
        assertEquals(ReadingSessionEndReason.SWITCHED_COMIC, settled.session.endReason)
        assertEquals(pauseCheckpoint, settled.session.endedAt)
        assertNull(machine.resumable)
    }

    @Test
    fun `process recovery outside window settles at lastCheckpointAt`() {
        openReadyVisibleForeground()
        clock.advanceBy(40_000L)
        machine.onEvent(ReadingSessionEvent.CheckpointTick)
        val snapshot = machine.resumable!!
        val checkpoint = snapshot.lastCheckpointAt

        val restoredMachine = ReadingSessionStateMachine(clock) { "restored" }
        clock.advanceBy(ReadingSessionRules.INTERRUPTION_WINDOW_MS)
        val effects = restoredMachine.onEvent(ReadingSessionEvent.ProcessRestored(snapshot))
        val settled = effects.filterIsInstance<ReadingSessionEffect.CompletePermanent>().single()
        assertEquals(ReadingSessionEndReason.PROCESS_RECOVERY, settled.session.endReason)
        assertEquals(checkpoint, settled.session.endedAt)
        assertEquals(checkpoint, settled.session.lastCheckpointAt)
        assertNull(restoredMachine.resumable)
    }

    @Test
    fun `restored ACTIVE normalizes to PAUSED and does not time before reader ready`() {
        openReadyVisibleForeground()
        clock.advanceBy(5_000L)
        machine.onEvent(ReadingSessionEvent.CheckpointTick)
        val snapshot = machine.resumable!!.copy(status = ReadingSessionStatus.ACTIVE)
        val activeBefore = snapshot.activeReadingMillis

        val restoredMachine = ReadingSessionStateMachine(clock) { "restored" }
        val restoreEffects = restoredMachine.onEvent(ReadingSessionEvent.ProcessRestored(snapshot))
        val normalized =
            restoreEffects.filterIsInstance<ReadingSessionEffect.UpsertResumable>().single().session
        assertEquals(ReadingSessionStatus.PAUSED, normalized.status)
        assertEquals(snapshot.lastCheckpointAt, normalized.lastCheckpointAt)
        assertEquals(snapshot.activeReadingMillis, normalized.activeReadingMillis)
        assertEquals(snapshot.checkpointPosition, normalized.checkpointPosition)
        assertEquals(ReadingSessionStatus.PAUSED, restoredMachine.resumable!!.status)

        // Process enters foreground without Reader Ready — must not accumulate.
        clock.advanceBy(30_000L)
        restoredMachine.onEvent(ReadingSessionEvent.EnteredInteractiveForeground)
        assertEquals(activeBefore, restoredMachine.resumable!!.activeReadingMillis)
        assertNull(restoredMachine.interactiveSegmentStartedAt)
        assertEquals(ReadingSessionStatus.PAUSED, restoredMachine.resumable!!.status)
    }

    @Test
    fun `zone is captured at start and not reread later`() {
        clock.setZone(java.time.ZoneId.of("Asia/Tokyo"))
        openReadyVisibleForeground()
        assertEquals("Asia/Tokyo", machine.resumable!!.timeZoneId)
        clock.setZone(java.time.ZoneId.of("UTC"))
        machine.onEvent(ReadingSessionEvent.CheckpointTick)
        assertEquals("Asia/Tokyo", machine.resumable!!.timeZoneId)
    }

    private fun openReadyVisibleForeground(position: ReadingPositionSnapshot = startPos) {
        machine.onEvent(ReadingSessionEvent.OpenComic(comic))
        machine.onEvent(ReadingSessionEvent.ReaderReady(position))
        machine.onEvent(ReadingSessionEvent.PageVisible(position))
        machine.onEvent(ReadingSessionEvent.EnteredInteractiveForeground)
        assertTrue(machine.resumable != null)
    }

    private fun position(global: Int, revision: String = "rev-a") =
        ReadingPositionSnapshot(
            pageIdentity = "p$global",
            globalPageIndex = global,
            chapterIndex = 0,
            pageIndex = global,
            chapterTitle = "Ch 1",
            sourceRevision = revision,
        )
}
