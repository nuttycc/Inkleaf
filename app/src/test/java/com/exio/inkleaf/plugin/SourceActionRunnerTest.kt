package com.exio.inkleaf.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceActionRunnerTest {
    private val store = SourceSessionHintTest.RecordingStore()
    private val requests = mutableListOf<PluginActionRequest>()
    private val events = mutableListOf<String>()
    private var result: JsonElement = JsonObject(emptyMap())

    private val runner =
        SourceActionRunner(
            invoke = { pluginId, request ->
                requests += request
                events += "invoke:$pluginId"
                result
            },
            fetchOptions = { _, request ->
                requests += request
                PluginActionOptions(applied = true)
            },
            sessionHint = SourceSessionHint(store) { PluginSessionCheck() },
            invalidateDerivedData = { pluginId -> events += "invalidate:$pluginId" },
        )

    private val refresh = PluginActionDescriptor("refresh", "刷新")

    @Test
    fun `a source action sends no comic input and invalidates derived data`() = runBlocking {
        runner.run("source", refresh)

        assertEquals(PluginActionRequest("refresh"), requests.single())
        assertEquals(listOf("invoke:source", "invalidate:source"), events)
    }

    @Test
    fun `a comic action sends the comic and the chosen option`() = runBlocking {
        runner.run("source", refresh, sourceId = "comic-1", optionId = "folder-2")

        assertEquals(
            buildJsonObject {
                put("sourceId", "comic-1")
                put(PluginActionDescriptor.OPTION_INPUT_ID, "folder-2")
            },
            requests.single().input,
        )
    }

    @Test
    fun `the plugin's message is shown`() = runBlocking {
        result = buildJsonObject { put("message", "done") }

        assertEquals("done", runner.run("source", refresh))
    }

    @Test
    fun `an object message falls back to the action title`() = runBlocking {
        result = buildJsonObject { put("message", buildJsonObject { put("text", "done") }) }

        assertEquals("刷新：操作完成", runner.run("source", refresh))
    }

    @Test
    fun `a login action records the session before invalidating`() = runBlocking {
        result = buildJsonObject { put("account", "alice") }

        runner.run("source", PluginActionDescriptor("login", "Login", kind = "login"))

        assertEquals(PluginSessionState(signedIn = true, account = "alice"), store.stored())
        assertEquals(listOf("in:source:alice"), store.calls)
        assertEquals("invalidate:source", events.last())
    }

    @Test
    fun `a failed action changes nothing`() = runBlocking {
        val failing =
            SourceActionRunner(
                invoke = { _, _ -> throw IllegalStateException("boom") },
                fetchOptions = { _, _ -> PluginActionOptions() },
                sessionHint = SourceSessionHint(store) { PluginSessionCheck() },
                invalidateDerivedData = { events += "invalidate" },
            )

        val error = runCatching { failing.run("source", refresh) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(events.isEmpty())
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun `options ask for the comic without running the action`() = runBlocking {
        val options = runner.options("source", refresh, sourceId = "comic-1")

        assertTrue(options.applied)
        assertEquals(buildJsonObject { put("sourceId", "comic-1") }, requests.single().input)
        assertFalse(events.any { it.startsWith("invalidate") })
    }
}
