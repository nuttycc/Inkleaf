package com.exio.inkleaf.diagnostics

import org.junit.Assert.assertEquals
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
}
