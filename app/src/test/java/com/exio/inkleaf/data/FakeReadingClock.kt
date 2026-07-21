package com.exio.inkleaf.data

import java.time.Instant
import java.time.ZoneId

/**
 * Test clock with explicit millis and zone. Advance with [advanceBy] or [setNow].
 */
class FakeReadingClock(
    initialMillis: Long = 0L,
    private var zone: ZoneId = ZoneId.of("UTC"),
) : ReadingClock {
    private var millis: Long = initialMillis

    override fun nowMillis(): Long = millis

    override fun zoneId(): String = zone.id

    fun setNow(epochMillis: Long) {
        millis = epochMillis
    }

    fun advanceBy(deltaMillis: Long) {
        require(deltaMillis >= 0) { "Clock can only advance forward in tests" }
        millis += deltaMillis
    }

    fun setZone(zoneId: ZoneId) {
        zone = zoneId
    }

    fun instant(): Instant = Instant.ofEpochMilli(millis)
}
