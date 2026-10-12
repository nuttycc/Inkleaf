package com.exio.inkleaf.plugin

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JS `host` object a plugin is handed is generated from the host's capability catalogue, so these
 * tests pin the two together: if a capability is renamed, added, or given a new argument shape, the
 * generated surface and the catalogue move as one.
 */
class PluginHostMethodsTest {
    private val root = Files.createTempDirectory("inkleaf-plugin-host-methods").toFile()

    @Test
    fun `every catalogue name is callable from the generated host object`() {
        val session = session()
        val script = renderPluginHostObject(session.hostMethods)

        session.hostMethods.forEach { method ->
            assertTrue(
                "missing ${method.name} in generated host object",
                script.contains("hostCall(\"${method.name}\", ${method.jsArgs.requestArgs}, signal)"),
            )
        }
    }

    @Test
    fun `the bootstrap hands the plugin that same generated object`() {
        val session = session()
        val script =
            PluginBootstrap.script("main()", "port", setOf("describe"), session.hostMethods)

        assertTrue(script.contains("const host = Object.freeze({"))
        session.hostMethods.forEach { method ->
            assertTrue(
                script.contains("hostCall(\"${method.name}\", ${method.jsArgs.requestArgs}, signal)")
            )
        }
        // The escape hatch stays a plain function over the generic caller.
        assertTrue(script.contains("call: hostCall,"))
    }

    @Test
    fun `request shaped methods forward the request object`() {
        val script = renderPluginHostObject(listOf(method("http.request", PluginHostJsArgs.Request)))

        assertTrue(script.contains("request: function(request, signal)"))
        assertTrue(script.contains("hostCall(\"http.request\", request, signal)"))
    }
    @Test
    fun `signal shaped methods send an empty request`() {
        val script = renderPluginHostObject(listOf(method("kv.keys", PluginHostJsArgs.Signal)))

        assertTrue(script.contains("keys: function(signal)"))
        assertTrue(script.contains("hostCall(\"kv.keys\", {}, signal)"))
    }

    @Test
    fun `single field methods name the field they forward`() {
        val script =
            renderPluginHostObject(
                listOf(method("settings.get", PluginHostJsArgs.Field("id"))),
            )

        assertTrue(script.contains("get: function(id, signal)"))
        assertTrue(script.contains("hostCall(\"settings.get\", { id: id }, signal)"))
    }

    @Test
    fun `log keeps its three argument shape`() {
        val script = renderPluginHostObject(listOf(method("log", PluginHostJsArgs.Log)))

        assertTrue(script.contains("log: function(level, message, fields, signal)"))
        assertTrue(script.contains("fields: fields || {}"))
    }

    /**
     * Object literal members must be comma separated; a missing comma is a syntax error that only
     * fires when the isolate evaluates the bootstrap, so the shape is pinned here.
     */
    @Test
    fun `generated members are comma separated`() {
        val script = renderPluginHostObject(session().hostMethods)
        val lines = script.lines()

        val members = lines.filter { it.contains(": function(") }
        assertTrue(members.isNotEmpty())
        lines.forEachIndexed { index, line ->
            if (!line.contains(": function(") || line.trimEnd().endsWith(",")) return@forEachIndexed
            // A member without a separator must be the last one in its group or in the object.
            val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() }.orEmpty()
            assertTrue("member not separated from $next: $line", next.contains("})"))
        }
    }

    @Test
    fun `every group is closed and comma separated`() {
        val script = renderPluginHostObject(session().hostMethods)

        assertEquals(
            script.lines().count { it.contains(": Object.freeze({") },
            script.lines().count { it.contains("}),") },
        )
        assertTrue(script.trimEnd().endsWith("});"))
    }

    @Test
    fun `an unknown method is a protocol error naming the method`() = runBlocking {
        val session = session()

        val error =
            runCatching { session.handle("host.nope", JsonObject(emptyMap())) }
                .exceptionOrNull() as PluginRpcException
        assertEquals(PluginErrorCode.PLUGIN_PROTOCOL, error.error.code)
        assertTrue(error.error.message.orEmpty().contains("host.nope"))
    }

    @Test
    fun `signal shaped methods accept the empty request the JS wrapper sends`() = runBlocking {
        val session = session(clockMs = { 0L })

        assertEquals("{\"epochMs\":0}", session.handle("clock.now", JsonObject(emptyMap())).toString())
    }

    private fun session(clockMs: () -> Long = { System.currentTimeMillis() }) =
        PluginHostSession("io.example.one", root.resolve("host-methods"), clockMs = clockMs)

    private fun method(name: String, jsArgs: PluginHostJsArgs) =
        PluginHostMethod(name, jsArgs) { _, _ -> JsonObject(emptyMap()) }
}
