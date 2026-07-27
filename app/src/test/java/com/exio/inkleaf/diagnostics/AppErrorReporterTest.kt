package com.exio.inkleaf.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorReporterTest {
    @Test
    fun `report preserves exception type when message is absent`() {
        val error = IllegalStateException(null, IllegalArgumentException("bad input"))

        val report =
            buildAppErrorReport(
                operation = "Run source action",
                error = error,
                metadata = mapOf("Plugin ID" to "fixture"),
                timestamp = "2026-07-27T12:00:00Z",
                appVersion = "test",
            )

        assertEquals("操作失败：IllegalStateException", report.summary)
        assertTrue(report.details.contains("Exception: java.lang.IllegalStateException"))
        assertTrue(report.details.contains("Message: (no message)"))
        assertTrue(report.details.contains("java.lang.IllegalArgumentException: bad input"))
    }

    @Test
    fun `error log retains the newest one hundred entries`() {
        var log = ""
        repeat(105) { index -> log = retainErrorLogEntries(log, "entry-$index") }

        assertFalse(log.contains("entry-4\n"))
        assertTrue(log.contains("entry-5\n"))
        assertTrue(log.contains("entry-104\n"))
        assertEquals(100, Regex("entry-\\d+").findAll(log).count())
    }

    @Test
    fun `summary uses only the first non-empty message line`() {
        val report =
            buildAppErrorReport(
                operation = "Run source action",
                error = IllegalArgumentException("First reason\nstack-like detail"),
                metadata = emptyMap(),
                timestamp = "2026-07-27T12:00:00Z",
                appVersion = "test",
            )

        assertEquals("操作失败：First reason", report.summary)
        assertTrue(report.details.contains("stack-like detail"))
    }

    @Test
    fun `error marker inside details does not create another log entry`() {
        val log = retainErrorLogEntries("", "before\n\n=== Inkleaf error ===\n\nafter")

        assertFalse(log.contains("\n\n=== Inkleaf error ===\n\n"))
        assertTrue(log.contains("before"))
        assertTrue(log.contains("after"))
    }
}
