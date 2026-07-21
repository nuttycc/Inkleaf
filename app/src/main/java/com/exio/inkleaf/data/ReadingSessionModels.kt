package com.exio.inkleaf.data

import com.exio.inkleaf.data.db.BookSourceType

/**
 * Domain types for reading sessions.
 *
 * Persistence columns and UI models build on these; keep enums and snapshots
 * free of Room/Compose so JVM tests can drive the state machine directly.
 *
 * Spec sources: GitHub issues #14 (boundaries), #15 (data contract).
 */

/** Only three statuses exist. There is no CRASHED state. */
enum class ReadingSessionStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
}

/**
 * Why a session ended. Null while ACTIVE/PAUSED.
 * Clear-all and manual delete remove rows; they are not end reasons.
 */
enum class ReadingSessionEndReason {
    LEFT_READER,
    SWITCHED_COMIC,
    INTERRUPTION_TIMEOUT,
    SOURCE_CHANGED,
    PROCESS_RECOVERY,
    UNKNOWN,
}

/**
 * Page location captured at session start, checkpoint, or end.
 *
 * Mirrors the bookmark location fields so both features share resolution.
 */
data class ReadingPositionSnapshot(
    val pageIdentity: String?,
    val globalPageIndex: Int,
    val chapterIndex: Int,
    val pageIndex: Int,
    val chapterTitle: String,
    val sourceRevision: String,
) {
    init {
        require(globalPageIndex >= 0)
        require(chapterIndex >= 0)
        require(pageIndex >= 0)
        require(sourceRevision.isNotBlank())
    }

    /** True when the reader has moved away from the session start page. */
    fun differsFrom(other: ReadingPositionSnapshot): Boolean {
        if (pageIdentity != null || other.pageIdentity != null) {
            if (pageIdentity != other.pageIdentity) return true
        }
        return globalPageIndex != other.globalPageIndex ||
            chapterIndex != other.chapterIndex ||
            pageIndex != other.pageIndex
    }
}

/** Comic identity frozen into a session row (no FK to comics). */
data class ReadingSessionComicRef(
    val fileKey: String,
    val titleSnapshot: String,
    val sourceType: BookSourceType,
) {
    init {
        require(fileKey.isNotBlank())
        require(titleSnapshot.isNotBlank())
    }
}

/**
 * In-memory working copy of the single global resumable session.
 *
 * [checkpointPosition] is the last actually-visible page persisted at the
 * latest checkpoint/pause — not an end position. End position only exists
 * after COMPLETED settlement.
 */
data class ResumableSession(
    val id: String,
    val comic: ReadingSessionComicRef,
    val status: ReadingSessionStatus,
    val startedAt: Long,
    val lastCheckpointAt: Long,
    val activeReadingMillis: Long,
    val startPosition: ReadingPositionSnapshot,
    val checkpointPosition: ReadingPositionSnapshot,
    /** Zone captured once at session start; never re-read from the device clock. */
    val timeZoneId: String,
    val isPermanent: Boolean,
) {
    init {
        require(id.isNotBlank())
        require(status == ReadingSessionStatus.ACTIVE || status == ReadingSessionStatus.PAUSED) {
            "Only ACTIVE/PAUSED sessions are resumable"
        }
        require(activeReadingMillis >= 0)
        require(startedAt >= 0)
        require(lastCheckpointAt >= startedAt)
        require(timeZoneId.isNotBlank())
    }
}

/** Settled permanent history row produced by the state machine. */
data class CompletedSession(
    val id: String,
    val comic: ReadingSessionComicRef,
    val startedAt: Long,
    val lastCheckpointAt: Long,
    val endedAt: Long,
    val activeReadingMillis: Long,
    val startPosition: ReadingPositionSnapshot,
    /** Last durable checkpoint; may equal [endPosition] on active leave. */
    val checkpointPosition: ReadingPositionSnapshot,
    val endPosition: ReadingPositionSnapshot,
    val timeZoneId: String,
    val endReason: ReadingSessionEndReason,
) {
    init {
        require(id.isNotBlank())
        require(activeReadingMillis >= 0)
        require(startedAt >= 0)
        require(endedAt >= startedAt)
        require(lastCheckpointAt >= startedAt)
        require(timeZoneId.isNotBlank())
    }
}
