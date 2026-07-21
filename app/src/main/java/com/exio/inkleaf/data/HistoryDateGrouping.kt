package com.exio.inkleaf.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Date grouping for the history timeline.
 *
 * Each session belongs to the local calendar day of its start time in the
 * zone captured at session start. Crossing midnight does not split a session.
 * Relative labels ("今天" / "昨天") use the device's current date and refresh
 * without rewriting stored rows.
 *
 * Spec: #13 timeline IA, #18 insertSeparators + stable date keys.
 */
object HistoryDateGrouping {
    /**
     * Local calendar date for a session start, using the session's own zone.
     * Device travel later must not move the grouping day.
     */
    fun sessionLocalDate(startedAtMillis: Long, sessionZoneId: String): LocalDate {
        val zone = ZoneId.of(sessionZoneId)
        return Instant.ofEpochMilli(startedAtMillis).atZone(zone).toLocalDate()
    }

    /** ISO-8601 calendar date used as the stable separator key (`date:<value>`). */
    fun dateKey(localDate: LocalDate): String = localDate.toString()

    /**
     * Human label for a date header.
     * "今天"/"昨天" relative to [today]; older dates use a localized medium date.
     */
    fun labelFor(
        sessionDate: LocalDate,
        today: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): String {
        return when (sessionDate) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> DateTimeFormatter
                .ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(sessionDate)
        }
    }

    /**
     * Inserts a date header before the first item of each local day.
     * Items must already be ordered newest-first (startedAt DESC, id DESC).
     *
     * Used by unit tests on plain lists; production applies the same rule via
     * PagingData.insertSeparators after mapping rows to UI models.
     */
    fun <T> withDateSeparators(
        items: List<T>,
        sessionDateOf: (T) -> LocalDate,
        headerOf: (LocalDate) -> HistoryTimelineEntry.DateHeader,
        itemOf: (T) -> HistoryTimelineEntry.Session,
    ): List<HistoryTimelineEntry> {
        if (items.isEmpty()) return emptyList()
        val result = ArrayList<HistoryTimelineEntry>(items.size * 2)
        var previousDate: LocalDate? = null
        for (item in items) {
            val date = sessionDateOf(item)
            if (date != previousDate) {
                result += headerOf(date)
                previousDate = date
            }
            result += itemOf(item)
        }
        return result
    }
}

/**
 * Flattened timeline rows after date separators are inserted.
 * Stable Compose keys: `date:<local-date>` and `session:<uuid>`.
 */
sealed interface HistoryTimelineEntry {
    val stableKey: String

    data class DateHeader(
        val localDate: LocalDate,
        val label: String,
    ) : HistoryTimelineEntry {
        override val stableKey: String = "date:${HistoryDateGrouping.dateKey(localDate)}"
    }

    /** Session row placeholder; UI supplies its own typed model keyed by [sessionId]. */
    data class Session(
        val sessionId: String,
    ) : HistoryTimelineEntry {
        override val stableKey: String = "session:$sessionId"
    }
}
