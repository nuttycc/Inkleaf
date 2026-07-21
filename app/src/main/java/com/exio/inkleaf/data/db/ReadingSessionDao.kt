package com.exio.inkleaf.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Persistence access for reading sessions.
 *
 * Inserts use ABORT so a second resumableSlot=1 row fails loudly instead of
 * silently replacing the global slot. The repository settles/deletes the prior
 * resumable row before inserting a new one.
 */
@Dao
interface ReadingSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: ReadingSessionEntity)

    @Update
    suspend fun update(session: ReadingSessionEntity)

    @Query("SELECT * FROM reading_sessions WHERE id = :id")
    suspend fun getById(id: String): ReadingSessionEntity?

    /** The single global ACTIVE/PAUSED row, if any. */
    @Query("SELECT * FROM reading_sessions WHERE resumableSlot = 1 LIMIT 1")
    suspend fun getResumable(): ReadingSessionEntity?

    @Query("DELETE FROM reading_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Clear history: every permanent row plus the current resumable slot
     * (which may still be temporary).
     */
    @Query(
        """
        DELETE FROM reading_sessions
        WHERE isPermanent = 1 OR resumableSlot = 1
        """,
    )
    suspend fun clearHistory()

    @Query("SELECT COUNT(*) FROM reading_sessions WHERE isPermanent = 1")
    suspend fun countPermanent(): Long

    /**
     * Permanent timeline, newest first. UUID [id] breaks same-millisecond ties.
     * Shelf fields are left-joined by fileKey so deleted comics stay visible
     * as unavailable history rows.
     */
    @Query(
        """
        SELECT
            s.id AS id,
            s.comicFileKey AS comicFileKey,
            s.titleSnapshot AS titleSnapshot,
            s.sourceType AS sourceType,
            s.status AS status,
            s.startedAt AS startedAt,
            COALESCE(s.endedAt, s.lastCheckpointAt) AS endedAt,
            s.activeReadingMillis AS activeReadingMillis,
            s.timeZoneId AS timeZoneId,
            COALESCE(s.endGlobalPageIndex, s.checkpointGlobalPageIndex) AS endGlobalPageIndex,
            COALESCE(s.endChapterIndex, s.checkpointChapterIndex) AS endChapterIndex,
            COALESCE(s.endPageIndex, s.checkpointPageIndex) AS endPageIndex,
            COALESCE(s.endChapterTitle, s.checkpointChapterTitle) AS endChapterTitle,
            COALESCE(s.endPageIdentity, s.checkpointPageIdentity) AS endPageIdentity,
            COALESCE(s.endSourceRevision, s.checkpointSourceRevision) AS endSourceRevision,
            c.title AS currentTitle,
            c.coverPath AS coverPath,
            c.id AS comicId,
            c.isMissing AS isMissing,
            c.isDraft AS isDraft
        FROM reading_sessions AS s
        LEFT JOIN comics AS c
            ON c.fileKey = s.comicFileKey AND c.isDraft = 0
        WHERE s.isPermanent = 1
        ORDER BY s.startedAt DESC, s.id DESC
        """,
    )
    fun observeHistoryPaging(): PagingSource<Int, HistoryRowProjection>
}
