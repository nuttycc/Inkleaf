package com.exio.inkleaf.diagnostics

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NetworkDiagnosticTest {
    @Test
    fun `network metadata records only sanitized URL fields`() {
        val request =
            Request.Builder()
                .url("https://example.test/comic/chapter?token=secret#page")
                .header("Authorization", "Bearer secret")
                .get()
                .build()

        val metadata =
            networkDiagnosticMetadata(
                request = request,
                source = "plugin_http",
                pluginId = "io.example.source",
                statusCode = 200,
                durationMs = 42,
            )

        assertEquals("GET", metadata.metadata["method"])
        assertEquals("example.test", metadata.metadata["host"])
        assertEquals("/comic/chapter", metadata.metadata["path"])
        assertEquals("200", metadata.metadata["status"])
        assertEquals("42", metadata.metadata["durationMs"])
        assertEquals("io.example.source", metadata.metadata["pluginId"])
        assertFalse(metadata.metadata.values.any { it.contains("secret") })
        assertFalse(metadata.title.contains("token"))
    }

    @Test
    fun `network metadata records failure class without message`() {
        val request = Request.Builder().url("https://example.test/image").build()

        val metadata =
            networkDiagnosticMetadata(
                request = request,
                source = "online_image",
                durationMs = 3,
                failure = IllegalStateException("credential=secret"),
            )

        assertEquals("IllegalStateException", metadata.metadata["failureType"])
        assertFalse(metadata.metadata.values.any { it.contains("secret") })
    }
}
