package com.exio.inkleaf.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    @Test
    fun `retention keeps recent critical events before ordinary event quota`() {
        val events =
            buildList {
                repeat(1_005) { index -> add(testEvent(index, DiagnosticEventType.ERROR)) }
                repeat(25) { index -> add(testEvent(2_000 + index, DiagnosticEventType.CRASH)) }
            }

        val retained = retainDiagnosticEvents(events)

        assertEquals(1_000, retained.size)
        assertEquals(25, retained.count { it.type == DiagnosticEventType.CRASH })
        assertTrue(retained.any { it.id == "2024" })
        assertFalse(retained.any { it.id == "0" })
    }

    @Test
    fun `retention keeps only the newest fifty breadcrumbs`() {
        val events = (0..50).map { index -> testEvent(index, DiagnosticEventType.BREADCRUMB) }

        val retained = retainDiagnosticEvents(events)

        assertEquals(50, retained.size)
        assertFalse(retained.any { it.id == "0" })
        assertTrue(retained.any { it.id == "50" })
    }

    @Test
    fun `redaction removes credential values and URL queries`() {
        assertEquals("[redacted]", redactDiagnosticValue("Authorization", "Bearer secret"))
        assertEquals("https://example.test/path", redactDiagnosticValue("url", "https://example.test/path?token=secret"))
        assertEquals("value", redactDiagnosticValue("pluginId", "value"))
    }

    @Test
    fun `plugin log export omits unstructured messages and redacts nested sensitive fields`() {
        val sanitized =
            requireNotNull(
                sanitizePluginLogLine(
                    """{"pluginId":"fixture","timestampMs":1,"level":"warn","message":"Bearer raw-token","fields":{"url":"https://example.test/path","nested":{"apiToken":"raw-token"}}}"""
                )
            )
        val json = kotlinx.serialization.json.Json.parseToJsonElement(sanitized).jsonObject

        assertEquals("[omitted from diagnostic export]", json["message"]?.jsonPrimitive?.content)
        assertFalse(sanitized.contains("raw-token"))
        assertEquals("[redacted]", json["fields"]?.jsonObject?.get("nested")?.jsonObject?.get("apiToken")?.jsonPrimitive?.content)
        assertEquals("https://example.test/path", json["fields"]?.jsonObject?.get("url")?.jsonPrimitive?.content)
    }

    @Test
    fun `plugin log URL fields retain only their base path`() {
        val sanitized =
            requireNotNull(
                sanitizePluginLogLine(
                    """{"fields":{"requestURL":"https://example.test/path?token=raw-token#fragment-token","deep":{"resourceUri":"https://example.test/child?secret=raw-secret#fragment-secret"}}}"""
                )
            )

        assertTrue(sanitized.contains("https://example.test/path"))
        assertTrue(sanitized.contains("https://example.test/child"))
        assertFalse(sanitized.contains("raw-token"))
        assertFalse(sanitized.contains("fragment-token"))
        assertFalse(sanitized.contains("raw-secret"))
        assertFalse(sanitized.contains("fragment-secret"))
    }

    @Test
    fun `malformed plugin log line is skipped from export`() {
        assertEquals(null, sanitizePluginLogLine("not json"))
    }

    @Test
    fun `journal recovery restores valid previous journal when current is invalid`() {
        val directory = createTempDirectory("inkleaf-diagnostics-").toFile()
        try {
            val journal = File(directory, "events.jsonl").apply { writeText("broken") }
            val previous = File(directory, ".events.previous").apply { writeText("valid") }

            val restored =
                restoreDiagnosticJournal(journal, previous) { file ->
                    if (file.readText() == "valid") listOf(testEvent(1, DiagnosticEventType.ERROR)) else emptyList()
                }

            assertTrue(restored)
            assertEquals("valid", journal.readText())
            assertFalse(previous.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `journal recovery keeps valid current journal and deletes previous backup`() {
        val directory = createTempDirectory("inkleaf-diagnostics-").toFile()
        try {
            val journal = File(directory, "events.jsonl").apply { writeText("current") }
            val previous = File(directory, ".events.previous").apply { writeText("previous") }

            val restored =
                restoreDiagnosticJournal(journal, previous) { file ->
                    if (file.readText().isNotEmpty()) listOf(testEvent(1, DiagnosticEventType.ERROR)) else emptyList()
                }

            assertFalse(restored)
            assertEquals("current", journal.readText())
            assertFalse(previous.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `journal recovery rejects current journal containing a malformed non-empty line`() {
        val directory = createTempDirectory("inkleaf-diagnostics-").toFile()
        try {
            val validLine = diagnosticEventJsonLine(testEvent(1, DiagnosticEventType.ERROR))
            val journal = File(directory, "events.jsonl").apply { writeText("$validLine\nnot-json\n") }
            val previous = File(directory, ".events.previous").apply { writeText("$validLine\n") }

            val restored =
                restoreDiagnosticJournal(
                    journal = journal,
                    previous = previous,
                    readStrictEvents = ::readStrictDiagnosticEvents,
                )

            assertTrue(restored)
            assertEquals(listOf("1"), readStrictDiagnosticEvents(journal)?.map { it.id })
            assertFalse(previous.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun testEvent(index: Int, type: DiagnosticEventType): DiagnosticEvent =
        DiagnosticEvent(
            id = index.toString(),
            timestamp = Instant.ofEpochSecond(index.toLong()).toString(),
            sessionId = "test",
            type = type,
            title = "event-$index",
        )
}
