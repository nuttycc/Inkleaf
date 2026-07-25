package com.exio.inkleaf.data

import com.exio.inkleaf.data.db.BookSourceType
import com.exio.inkleaf.data.db.ReadingSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingSessionMappingTest {
    private val start =
        ReadingPositionSnapshot(
            pageIdentity = "p1",
            globalPageIndex = 1,
            chapterIndex = 0,
            pageIndex = 1,
            chapterTitle = "Ch 1",
            sourceRevision = "rev-a",
        )
    private val checkpoint = start.copy(globalPageIndex = 3, pageIndex = 3, pageIdentity = "p3")
    private val end = start.copy(globalPageIndex = 9, pageIndex = 9, pageIdentity = "p9")
    private val comic =
        ReadingSessionComicRef(
            fileKey = "book-a",
            titleSnapshot = "Book A",
            sourceType = BookSourceType.EXTERNAL_ARCHIVE,
        )

    @Test
    fun `resumable round trip keeps checkpoint separate from null end`() {
        val domain =
            ResumableSession(
                id = "uuid-1",
                comic = comic,
                status = ReadingSessionStatus.PAUSED,
                startedAt = 100,
                lastCheckpointAt = 200,
                activeReadingMillis = 50,
                startPosition = start,
                checkpointPosition = checkpoint,
                timeZoneId = "Asia/Tokyo",
                isPermanent = true,
            )
        val entity = ReadingSessionMapping.fromResumable(domain)
        assertEquals(ReadingSessionEntity.RESUMABLE_SLOT, entity.resumableSlot)
        assertNull(entity.endedAt)
        assertNull(entity.endReason)
        assertNull(entity.endGlobalPageIndex)
        assertEquals(checkpoint.globalPageIndex, entity.checkpointGlobalPageIndex)

        val back = ReadingSessionMapping.toResumable(entity)
        assertEquals(domain, back)
    }

    @Test
    fun `completed round trip preserves distinct checkpoint and end positions`() {
        val domain =
            CompletedSession(
                id = "uuid-2",
                comic = comic,
                startedAt = 100,
                lastCheckpointAt = 200,
                endedAt = 250,
                activeReadingMillis = 40_000,
                startPosition = start,
                checkpointPosition = checkpoint,
                endPosition = end,
                timeZoneId = "UTC",
                endReason = ReadingSessionEndReason.LEFT_READER,
            )
        val entity = ReadingSessionMapping.fromCompleted(domain)
        assertNull(entity.resumableSlot)
        assertEquals(ReadingSessionStatus.COMPLETED.name, entity.status)
        assertEquals(true, entity.isPermanent)
        assertEquals(3, entity.checkpointGlobalPageIndex)
        assertEquals(9, entity.endGlobalPageIndex)

        val back = ReadingSessionMapping.toCompleted(entity)
        assertEquals(domain, back)
        assertEquals(checkpoint, back.checkpointPosition)
        assertEquals(end, back.endPosition)
    }
}
