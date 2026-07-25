package com.exio.inkleaf.data

import java.time.Clock

/**
 * Injectable time source for reading-session timing.
 *
 * Session windows, checkpoints, and date grouping must not call System.currentTimeMillis() directly
 * — tests advance a fake clock instead of sleeping through the 10-minute interruption window.
 *
 * [zoneId] is read once when a session starts and stored on the row. Later calls must not rewrite
 * an existing session's zone.
 */
interface ReadingClock {
    /** Wall-clock epoch millis used for absolute session timestamps. */
    fun nowMillis(): Long

    /** IANA zone id (e.g. "Asia/Shanghai") for newly started sessions. */
    fun zoneId(): String
}

/** Production clock backed by the JVM system clock and default zone. */
class SystemReadingClock(private val clock: Clock = Clock.systemDefaultZone()) : ReadingClock {
    override fun nowMillis(): Long = clock.millis()

    override fun zoneId(): String = clock.zone.id
}
