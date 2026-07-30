package com.exio.inkleaf.ui

import com.exio.inkleaf.data.db.BookSourceType
import com.exio.inkleaf.data.db.HistoryRowProjection
import com.exio.inkleaf.plugin.OnlineAvailability
import com.exio.inkleaf.data.OnlineChapterIdentity
import com.exio.inkleaf.data.OnlineContentIdentity
import com.exio.inkleaf.data.OnlinePageLocation
import com.exio.inkleaf.plugin.OnlineReadingSessionRecord
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTimelineTest {
    @Test
    fun `local and online sessions share chronological date groups`() {
        val items =
            buildHistoryTimeline(
                local = listOf(localSession(startedAt = 1_000)),
                online = listOf(onlineSession(startedAt = 2_000)),
                filter = HistorySourceFilter.ALL,
                today = LocalDate.ofEpochDay(0),
                locale = Locale.SIMPLIFIED_CHINESE,
            )

        assertEquals(
            listOf("date:1970-01-01:online:p:s:o", "online:p:s:o", "local:l"),
            items.map(HistoryListItem::stableKey),
        )
    }

    @Test
    fun `source filter removes other source without empty date headers`() {
        val items =
            buildHistoryTimeline(
                local = listOf(localSession(startedAt = 1_000)),
                online = listOf(onlineSession(startedAt = 2_000)),
                filter = HistorySourceFilter.ONLINE,
                today = LocalDate.ofEpochDay(0),
                locale = Locale.ROOT,
            )

        assertEquals(
            listOf("date:1970-01-01:online:p:s:o", "online:p:s:o"),
            items.map(HistoryListItem::stableKey),
        )
    }

    private fun localSession(startedAt: Long) =
        HistoryRowProjection(
            id = "l",
            comicFileKey = "local",
            titleSnapshot = "Local",
            sourceType = BookSourceType.EXTERNAL_ARCHIVE,
            status = "COMPLETED",
            startedAt = startedAt,
            endedAt = startedAt + 1,
            activeReadingMillis = 1,
            timeZoneId = ZoneOffset.UTC.id,
            endGlobalPageIndex = 0,
            endChapterIndex = 0,
            endPageIndex = 0,
            endChapterTitle = "Chapter",
            endPageIdentity = null,
            endSourceRevision = "r",
            currentTitle = null,
            coverPath = null,
            comicId = null,
            isMissing = null,
            isDraft = null,
        )

    private fun onlineSession(startedAt: Long): OnlineHistorySessionUi {
        val content = OnlineContentIdentity("p", "s")
        val location =
            OnlinePageLocation.create(
                chapter = OnlineChapterIdentity(content, "c"),
                pageId = "p0",
                pageIndex = 0,
                chapterRevision = "r",
            )
        val stored =
            OnlineReadingSessionRecord(
                sessionId = "o",
                content = content,
                titleSnapshot = "Online",
                startedAtMs = startedAt,
                endedAtMs = startedAt + 1,
                activeReadingMillis = 1,
                timeZoneId = ZoneOffset.UTC.id,
                start = location,
                end = location,
            )
        return OnlineHistorySessionUi(
            key = "online:p:s:o",
            title = "Online",
            endLocationLabel = "Chapter",
            timeRangeLabel = "00:00-00:00",
            durationLabel = "Reading",
            cover = null,
            availability = OnlineAvailability.AVAILABLE,
            target = OnlineReaderTarget("p", "s", "c", "r", null, "p0", 0),
            stored = stored,
        )
    }
}
