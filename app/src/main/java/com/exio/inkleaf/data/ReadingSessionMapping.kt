package com.exio.inkleaf.data

import com.exio.inkleaf.data.db.ReadingSessionEntity

/**
 * Maps between domain session models and the flattened Room entity.
 * Keeps status/reason enums out of the DAO layer.
 */
internal object ReadingSessionMapping {
    fun toResumable(entity: ReadingSessionEntity): ResumableSession {
        require(entity.resumableSlot == ReadingSessionEntity.RESUMABLE_SLOT) {
            "Entity ${entity.id} is not in the resumable slot"
        }
        require(entity.endedAt == null) { "Resumable row must not have endedAt" }
        require(entity.endReason == null) { "Resumable row must not have endReason" }
        require(entity.endGlobalPageIndex == null) { "Resumable row must not have end position" }
        val status = ReadingSessionStatus.valueOf(entity.status)
        require(status == ReadingSessionStatus.ACTIVE || status == ReadingSessionStatus.PAUSED) {
            "Resumable status must be ACTIVE or PAUSED, was ${entity.status}"
        }
        return ResumableSession(
            id = entity.id,
            comic = ReadingSessionComicRef(
                fileKey = entity.comicFileKey,
                titleSnapshot = entity.titleSnapshot,
                sourceType = entity.sourceType,
            ),
            status = status,
            startedAt = entity.startedAt,
            lastCheckpointAt = entity.lastCheckpointAt,
            activeReadingMillis = entity.activeReadingMillis,
            startPosition = startPositionOf(entity),
            checkpointPosition = checkpointPositionOf(entity),
            timeZoneId = entity.timeZoneId,
            isPermanent = entity.isPermanent,
        )
    }

    fun fromResumable(session: ResumableSession): ReadingSessionEntity =
        ReadingSessionEntity(
            id = session.id,
            comicFileKey = session.comic.fileKey,
            titleSnapshot = session.comic.titleSnapshot,
            sourceType = session.comic.sourceType,
            status = session.status.name,
            startedAt = session.startedAt,
            lastCheckpointAt = session.lastCheckpointAt,
            endedAt = null,
            activeReadingMillis = session.activeReadingMillis,
            endReason = null,
            timeZoneId = session.timeZoneId,
            isPermanent = session.isPermanent,
            resumableSlot = ReadingSessionEntity.RESUMABLE_SLOT,
            startPageIdentity = session.startPosition.pageIdentity,
            startGlobalPageIndex = session.startPosition.globalPageIndex,
            startChapterIndex = session.startPosition.chapterIndex,
            startPageIndex = session.startPosition.pageIndex,
            startChapterTitle = session.startPosition.chapterTitle,
            startSourceRevision = session.startPosition.sourceRevision,
            checkpointPageIdentity = session.checkpointPosition.pageIdentity,
            checkpointGlobalPageIndex = session.checkpointPosition.globalPageIndex,
            checkpointChapterIndex = session.checkpointPosition.chapterIndex,
            checkpointPageIndex = session.checkpointPosition.pageIndex,
            checkpointChapterTitle = session.checkpointPosition.chapterTitle,
            checkpointSourceRevision = session.checkpointPosition.sourceRevision,
            endPageIdentity = null,
            endGlobalPageIndex = null,
            endChapterIndex = null,
            endPageIndex = null,
            endChapterTitle = null,
            endSourceRevision = null,
        )

    fun fromCompleted(session: CompletedSession): ReadingSessionEntity =
        ReadingSessionEntity(
            id = session.id,
            comicFileKey = session.comic.fileKey,
            titleSnapshot = session.comic.titleSnapshot,
            sourceType = session.comic.sourceType,
            status = ReadingSessionStatus.COMPLETED.name,
            startedAt = session.startedAt,
            lastCheckpointAt = session.lastCheckpointAt,
            endedAt = session.endedAt,
            activeReadingMillis = session.activeReadingMillis,
            endReason = session.endReason.name,
            timeZoneId = session.timeZoneId,
            isPermanent = true,
            resumableSlot = null,
            startPageIdentity = session.startPosition.pageIdentity,
            startGlobalPageIndex = session.startPosition.globalPageIndex,
            startChapterIndex = session.startPosition.chapterIndex,
            startPageIndex = session.startPosition.pageIndex,
            startChapterTitle = session.startPosition.chapterTitle,
            startSourceRevision = session.startPosition.sourceRevision,
            checkpointPageIdentity = session.checkpointPosition.pageIdentity,
            checkpointGlobalPageIndex = session.checkpointPosition.globalPageIndex,
            checkpointChapterIndex = session.checkpointPosition.chapterIndex,
            checkpointPageIndex = session.checkpointPosition.pageIndex,
            checkpointChapterTitle = session.checkpointPosition.chapterTitle,
            checkpointSourceRevision = session.checkpointPosition.sourceRevision,
            endPageIdentity = session.endPosition.pageIdentity,
            endGlobalPageIndex = session.endPosition.globalPageIndex,
            endChapterIndex = session.endPosition.chapterIndex,
            endPageIndex = session.endPosition.pageIndex,
            endChapterTitle = session.endPosition.chapterTitle,
            endSourceRevision = session.endPosition.sourceRevision,
        )

    fun toCompleted(entity: ReadingSessionEntity): CompletedSession {
        require(entity.status == ReadingSessionStatus.COMPLETED.name) {
            "Completed status required, was ${entity.status}"
        }
        require(entity.resumableSlot == null) { "Completed row must leave the resumable slot" }
        require(entity.isPermanent) { "Completed history rows must be permanent" }
        val endedAt = requireNotNull(entity.endedAt) { "Completed row requires endedAt" }
        val endReasonName = requireNotNull(entity.endReason) { "Completed row requires endReason" }
        require(endReasonName.isNotBlank())
        val endGlobal = requireNotNull(entity.endGlobalPageIndex) { "Completed row requires end page" }
        val endChapter = requireNotNull(entity.endChapterIndex)
        val endPage = requireNotNull(entity.endPageIndex)
        val endTitle = requireNotNull(entity.endChapterTitle)
        val endRevision = requireNotNull(entity.endSourceRevision)
        return CompletedSession(
            id = entity.id,
            comic = ReadingSessionComicRef(
                fileKey = entity.comicFileKey,
                titleSnapshot = entity.titleSnapshot,
                sourceType = entity.sourceType,
            ),
            startedAt = entity.startedAt,
            lastCheckpointAt = entity.lastCheckpointAt,
            endedAt = endedAt,
            activeReadingMillis = entity.activeReadingMillis,
            startPosition = startPositionOf(entity),
            checkpointPosition = checkpointPositionOf(entity),
            endPosition = ReadingPositionSnapshot(
                pageIdentity = entity.endPageIdentity,
                globalPageIndex = endGlobal,
                chapterIndex = endChapter,
                pageIndex = endPage,
                chapterTitle = endTitle,
                sourceRevision = endRevision,
            ),
            timeZoneId = entity.timeZoneId,
            endReason = ReadingSessionEndReason.valueOf(endReasonName),
        )
    }

    private fun startPositionOf(entity: ReadingSessionEntity) = ReadingPositionSnapshot(
        pageIdentity = entity.startPageIdentity,
        globalPageIndex = entity.startGlobalPageIndex,
        chapterIndex = entity.startChapterIndex,
        pageIndex = entity.startPageIndex,
        chapterTitle = entity.startChapterTitle,
        sourceRevision = entity.startSourceRevision,
    )

    private fun checkpointPositionOf(entity: ReadingSessionEntity) = ReadingPositionSnapshot(
        pageIdentity = entity.checkpointPageIdentity,
        globalPageIndex = entity.checkpointGlobalPageIndex,
        chapterIndex = entity.checkpointChapterIndex,
        pageIndex = entity.checkpointPageIndex,
        chapterTitle = entity.checkpointChapterTitle,
        sourceRevision = entity.checkpointSourceRevision,
    )
}
