package com.exio.inkleaf.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One reading session row.
 *
 * No foreign key to [ComicEntity]: sessions outlive shelf rows and re-associate by [comicFileKey].
 * Location fields are flattened (no JSON).
 *
 * Checkpoint columns always hold the last actually-visible page. End columns are null while
 * ACTIVE/PAUSED and filled only on COMPLETED permanent rows.
 *
 * Uniqueness of the global resumable slot uses [resumableSlot]:
 * - ACTIVE/PAUSED → 1 (unique index allows only one such row)
 * - COMPLETED → null (SQLite permits many nulls in a unique column)
 *
 * Temporary sessions that never qualify are deleted, never stored as COMPLETED.
 *
 * Spec: #15 data contract, #18 timeline query order.
 */
@Entity(
    tableName = "reading_sessions",
    indices =
        [
            Index(value = ["comicFileKey"]),
            Index(value = ["resumableSlot"], unique = true),
            // Permanent timeline: filter isPermanent + ORDER BY startedAt DESC, id DESC.
            Index(value = ["isPermanent", "startedAt", "id"]),
        ],
)
data class ReadingSessionEntity(
    @PrimaryKey val id: String,
    /** Stable comic source identity (not the shelf row id). */
    val comicFileKey: String,
    val titleSnapshot: String,
    val sourceType: BookSourceType,
    /** ACTIVE | PAUSED | COMPLETED stored as enum name. */
    val status: String,
    val startedAt: Long,
    val lastCheckpointAt: Long,
    val endedAt: Long?,
    val activeReadingMillis: Long,
    /** Null while ACTIVE/PAUSED; one of ReadingSessionEndReason names when COMPLETED. */
    val endReason: String?,
    /** IANA zone captured once at session start. */
    val timeZoneId: String,
    /** True once the session has entered permanent history. */
    val isPermanent: Boolean,
    /**
     * 1 while ACTIVE/PAUSED (global single slot); null when COMPLETED. Unique index + SQLite NULL
     * rules enforce at most one resumable row.
     */
    val resumableSlot: Int?,
    // --- start position ---
    val startPageIdentity: String?,
    val startGlobalPageIndex: Int,
    val startChapterIndex: Int,
    val startPageIndex: Int,
    val startChapterTitle: String,
    val startSourceRevision: String,
    // --- last checkpoint position (always present) ---
    val checkpointPageIdentity: String?,
    val checkpointGlobalPageIndex: Int,
    val checkpointChapterIndex: Int,
    val checkpointPageIndex: Int,
    val checkpointChapterTitle: String,
    val checkpointSourceRevision: String,
    // --- end position (null while ACTIVE/PAUSED) ---
    val endPageIdentity: String?,
    val endGlobalPageIndex: Int?,
    val endChapterIndex: Int?,
    val endPageIndex: Int?,
    val endChapterTitle: String?,
    val endSourceRevision: String?,
) {
    companion object {
        const val RESUMABLE_SLOT = 1
    }
}

/**
 * Lightweight row for the history timeline PagingSource. Joins the current shelf record by fileKey
 * when present.
 */
data class HistoryRowProjection(
    val id: String,
    val comicFileKey: String,
    val titleSnapshot: String,
    val sourceType: BookSourceType,
    val status: String,
    val startedAt: Long,
    /** Completed end time, or last checkpoint for permanent ACTIVE/PAUSED rows. */
    val endedAt: Long,
    val activeReadingMillis: Long,
    val timeZoneId: String,
    /** Effective end position (COALESCE end, checkpoint) for list display. */
    val endGlobalPageIndex: Int,
    val endChapterIndex: Int,
    val endPageIndex: Int,
    val endChapterTitle: String,
    val endPageIdentity: String?,
    val endSourceRevision: String,
    /** Current shelf title when associated; null if unavailable. */
    val currentTitle: String?,
    val coverPath: String?,
    val comicId: Long?,
    val isMissing: Boolean?,
    val isDraft: Boolean?,
)
