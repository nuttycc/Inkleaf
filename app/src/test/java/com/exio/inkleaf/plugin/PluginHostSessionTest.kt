package com.exio.inkleaf.plugin

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginHostSessionTest {
    @Test
    fun `kv state persists and remains plugin scoped`() = runBlocking {
        val root = Files.createTempDirectory("inkleaf-plugin-host").toFile()
        try {
            PluginHostSession("io.example.one", root.resolve("one")).use { first ->
                first.handle(
                    "kv.set",
                    PluginContentCodec.json.encodeToJsonElement(PluginKvSet("token", JsonPrimitive("one"))),
                )
            }
            PluginHostSession("io.example.one", root.resolve("one")).use { first ->
                val value = first.handle(
                    "kv.get",
                    PluginContentCodec.json.encodeToJsonElement(PluginKvGet("token")),
                )
                assertEquals("one", value.jsonPrimitive.content)
            }
            PluginHostSession("io.example.two", root.resolve("two")).use { second ->
                val value = second.handle(
                    "kv.get",
                    PluginContentCodec.json.encodeToJsonElement(PluginKvGet("token")),
                )
                assertEquals("null", value.toString())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `kv value quota is enforced`() = runBlocking {
        val root = Files.createTempDirectory("inkleaf-plugin-host-quota").toFile()
        try {
            PluginHostSession("io.example.one", root).use { session ->
                val result = runCatching {
                    session.handle(
                        "kv.set",
                        PluginContentCodec.json.encodeToJsonElement(
                            PluginKvSet("large", JsonPrimitive("x".repeat(PluginRuntimePolicy.MAX_KV_VALUE_BYTES + 1)))
                        ),
                    )
                }
                val error = result.exceptionOrNull() as PluginRpcException
                assertEquals(PluginErrorCode.QUOTA_EXCEEDED, error.error.code)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `structured log redacts sensitive fields`() = runBlocking {
        val root = Files.createTempDirectory("inkleaf-plugin-log").toFile()
        try {
            PluginHostSession("io.example.one", root).use { session ->
                session.handle(
                    "log",
                    PluginContentCodec.json.encodeToJsonElement(
                        PluginLogEntry("info", "login", mapOf("token" to "secret-value", "site" to "example"))
                    ),
                )
            }
            val text = root.resolve("logs/events.jsonl").readText()
            assertTrue(text.contains("[REDACTED]"))
            assertTrue(!text.contains("secret-value"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cookie set is exposed and persists in plugin scope`() = runBlocking {
        val root = Files.createTempDirectory("inkleaf-plugin-cookie").toFile()
        try {
            PluginHostSession("io.example.one", root).use { session ->
                session.handle(
                    "cookie.set",
                    buildJsonObject {
                        put("url", "https://example.com/")
                        put("setCookie", "fixture=one; Path=/")
                    },
                )
                val cookies = session.handle("cookie.list", JsonObject(emptyMap()))
                assertTrue(cookies.toString().contains("fixture"))
            }
            PluginHostSession("io.example.one", root).use { session ->
                val cookies = session.handle("cookie.list", JsonObject(emptyMap()))
                assertTrue(cookies.toString().contains("fixture"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `log quota keeps at most two thousand entries`() = runBlocking {
        val root = Files.createTempDirectory("inkleaf-plugin-log-quota").toFile()
        try {
            PluginHostSession("io.example.one", root).use { session ->
                repeat(2_005) { index ->
                    session.handle(
                        "log",
                        PluginContentCodec.json.encodeToJsonElement(
                            PluginLogEntry("info", "entry-$index")
                        ),
                    )
                }
            }
            assertTrue(root.resolve("logs/events.jsonl").readLines().size <= 2_000)
        } finally {
            root.deleteRecursively()
        }
    }
}
