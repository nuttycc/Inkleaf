package com.exio.inkleaf.data

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDateGroupingTest {
    @Test
    fun `session local date uses session zone not device travel`() {
        // 2024-06-02 02:00 in Tokyo == 2024-06-01 17:00 UTC.
        // Grouping must keep the Tokyo calendar day even if we later
        // reinterpret the same instant in UTC.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val startedAt =
            java.time.ZonedDateTime.of(2024, 6, 2, 2, 0, 0, 0, tokyo).toInstant().toEpochMilli()

        assertEquals(
            LocalDate.of(2024, 6, 2),
            HistoryDateGrouping.sessionLocalDate(startedAt, "Asia/Tokyo"),
        )
        assertEquals(
            LocalDate.of(2024, 6, 1),
            HistoryDateGrouping.sessionLocalDate(startedAt, "UTC"),
        )
    }

    @Test
    fun `relative labels use device today`() {
        val today = LocalDate.of(2024, 7, 21)
        assertEquals("今天", HistoryDateGrouping.labelFor(today, today, Locale.CHINA))
        assertEquals(
            "昨天",
            HistoryDateGrouping.labelFor(today.minusDays(1), today, Locale.CHINA),
        )
        val older =
            HistoryDateGrouping.labelFor(
                LocalDate.of(2024, 7, 1),
                today,
                Locale.CHINA,
            )
        assertTrue(older.contains("2024") || older.contains("7"))
    }

    @Test
    fun `separators insert once per local day on newest-first list`() {
        val day1 = LocalDate.of(2024, 7, 21)
        val day0 = LocalDate.of(2024, 7, 20)
        data class Row(val id: String, val day: LocalDate)

        val rows =
            listOf(
                Row("a", day1),
                Row("b", day1),
                Row("c", day0),
            )
        val timeline =
            HistoryDateGrouping.withDateSeparators(
                items = rows,
                sessionDateOf = { it.day },
                headerOf = { date ->
                    HistoryTimelineEntry.DateHeader(
                        localDate = date,
                        label = HistoryDateGrouping.labelFor(date, day1, Locale.CHINA),
                    )
                },
                itemOf = { HistoryTimelineEntry.Session(sessionId = it.id) },
            )

        assertEquals(
            listOf(
                "date:2024-07-21",
                "session:a",
                "session:b",
                "date:2024-07-20",
                "session:c",
            ),
            timeline.map { it.stableKey },
        )
    }

    @Test
    fun `stable keys stay unique for same-millisecond sessions via uuid`() {
        val id1 = UUID.randomUUID().toString()
        val id2 = UUID.randomUUID().toString()
        val entries =
            listOf(
                HistoryTimelineEntry.Session(id1),
                HistoryTimelineEntry.Session(id2),
            )
        assertEquals(2, entries.map { it.stableKey }.toSet().size)
    }

    @Test
    fun `cross-midnight session stays on start day`() {
        val zone = "America/Los_Angeles"
        val start =
            java.time.ZonedDateTime.of(2024, 3, 10, 23, 50, 0, 0, ZoneId.of(zone))
                .toInstant()
                .toEpochMilli()
        // Session continues past midnight; grouping uses start only.
        assertEquals(
            LocalDate.of(2024, 3, 10),
            HistoryDateGrouping.sessionLocalDate(start, zone),
        )
    }
}
