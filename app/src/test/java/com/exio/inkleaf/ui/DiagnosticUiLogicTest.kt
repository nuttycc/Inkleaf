package com.exio.inkleaf.ui

import com.exio.inkleaf.diagnostics.DiagnosticEvent
import com.exio.inkleaf.diagnostics.DiagnosticEventType
import com.exio.inkleaf.diagnostics.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticUiLogicTest {
    private val events =
        listOf(
            event("crash", DiagnosticEventType.CRASH),
            event("network", DiagnosticEventType.NETWORK),
        )

    @Test
    fun `filter retains only the requested diagnostic type`() {
        assertEquals(events, diagnosticEventsForFilter(events, null, null))
        assertEquals(
            listOf(events[1]),
            diagnosticEventsForFilter(events, DiagnosticEventType.NETWORK, null),
        )
    }

    @Test
    fun `filter combines type and severity`() {
        val warningNetwork = events[1].copy(severity = DiagnosticSeverity.WARNING)
        val candidates = events + warningNetwork

        assertEquals(
            listOf(warningNetwork),
            diagnosticEventsForFilter(
                candidates,
                DiagnosticEventType.NETWORK,
                DiagnosticSeverity.WARNING,
            ),
        )
    }

    @Test
    fun `copy text includes diagnostic context and metadata`() {
        val text =
            event("error", DiagnosticEventType.ERROR)
                .copy(message = "Unable to load", metadata = mapOf("operation" to "open"))
                .copyText()

        assertTrue(text.contains("错误 · event-error"))
        assertTrue(text.contains("级别: 错误"))
        assertTrue(text.contains("operation: open"))
        assertTrue(text.contains("Unable to load"))
    }

    @Test
    fun `export file name remains a zip document`() {
        assertEquals("inkleaf-diagnostics-42.zip", diagnosticExportFileName(42))
        assertFalse(diagnosticExportFileName(42).contains(' '))
    }

    private fun event(id: String, type: DiagnosticEventType) =
        DiagnosticEvent(
            id = id,
            timestamp = "2026-07-29T12:00:00Z",
            sessionId = "session",
            type = type,
            severity =
                when (type) {
                    DiagnosticEventType.CRASH -> DiagnosticSeverity.FATAL
                    DiagnosticEventType.NETWORK -> DiagnosticSeverity.INFO
                    else -> DiagnosticSeverity.ERROR
                },
            title = "event-$id",
        )
}
