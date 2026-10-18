package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.PluginActionDescriptor
import com.exio.inkleaf.plugin.PluginSessionCheck
import com.exio.inkleaf.plugin.PluginSessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSessionHintTest {

    private class RecordingStore(initial: PluginSessionState = PluginSessionState()) :
        SourceSessionStore {
        var stored = initial
        val calls = mutableListOf<String>()

        override suspend fun read(pluginId: String): PluginSessionState =
            stored.also { calls += "read:$pluginId" }

        override suspend fun signedIn(pluginId: String, account: String?) {
            stored = PluginSessionState(signedIn = true, account = account)
            calls += "in:$pluginId:${account.orEmpty()}"
        }

        override suspend fun signedOut(pluginId: String) {
            stored = PluginSessionState()
            calls += "out:$pluginId"
        }

        override suspend fun clear(pluginId: String) {
            stored = PluginSessionState()
            calls += "clear:$pluginId"
        }
    }

    private fun login() = PluginActionDescriptor("login", "Login", kind = "login")

    private fun logout() = PluginActionDescriptor("logout", "Logout", kind = "clearSession")

    private fun result(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    @Test
    fun `load adopts the stored hint`() = runBlocking {
        val stored = PluginSessionState(signedIn = true, account = "alice")
        val hint = SourceSessionHint(RecordingStore(stored)) { PluginSessionCheck() }

        hint.load("source")

        assertEquals(stored, hint.state.value)
    }

    @Test
    fun `login records the reported account`() = runBlocking {
        val store = RecordingStore()
        val hint = SourceSessionHint(store) { PluginSessionCheck() }
        hint.load("source")

        hint.applyOutcome("source", login(), result("account" to "alice"))

        assertEquals(PluginSessionState(signedIn = true, account = "alice"), hint.state.value)
        assertEquals(PluginSessionState(signedIn = true, account = "alice"), store.stored)
    }

    @Test
    fun `sign out clears the stored hint`() = runBlocking {
        val store = RecordingStore(PluginSessionState(signedIn = true, account = "alice"))
        val hint = SourceSessionHint(store) { PluginSessionCheck() }
        hint.load("source")

        hint.applyOutcome("source", logout(), result("message" to "bye"))

        assertEquals(PluginSessionState(), hint.state.value)
        assertEquals(PluginSessionState(), store.stored)
    }

    @Test
    fun `reconcile adopts a live session`() = runBlocking {
        val store = RecordingStore()
        val hint =
            SourceSessionHint(store) { PluginSessionCheck(signedIn = true, account = "alice") }
        hint.load("source")

        hint.reconcile("source")

        assertEquals(PluginSessionState(signedIn = true, account = "alice"), hint.state.value)
        assertEquals(PluginSessionState(signedIn = true, account = "alice"), store.stored)
    }

    @Test
    fun `reconcile clears a stale hint`() = runBlocking {
        val store = RecordingStore(PluginSessionState(signedIn = true, account = "alice"))
        val hint = SourceSessionHint(store) { PluginSessionCheck(signedIn = false) }
        hint.load("source")

        hint.reconcile("source")

        assertEquals(PluginSessionState(), hint.state.value)
        assertEquals(PluginSessionState(), store.stored)
    }

    @Test
    fun `reconcile keeps the hint when the check fails`() = runBlocking {
        val stored = PluginSessionState(signedIn = true, account = "alice")
        val hint = SourceSessionHint(RecordingStore(stored)) {
            throw IllegalStateException("offline")
        }
        hint.load("source")

        hint.reconcile("source")

        assertEquals(stored, hint.state.value)
    }

    @Test
    fun `a late check does not overwrite a newer login`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val hint =
            SourceSessionHint(RecordingStore()) {
                started.complete(Unit)
                release.await()
                PluginSessionCheck(signedIn = false)
            }
        hint.load("source")

        val job = launch(Dispatchers.Default) { hint.reconcile("source") }
        started.await()
        hint.applyOutcome("source", login(), result("account" to "alice"))
        release.complete(Unit)
        job.join()

        assertEquals(PluginSessionState(signedIn = true, account = "alice"), hint.state.value)
    }

    @Test
    fun `clear resets the hint and the store`() = runBlocking {
        val store = RecordingStore(PluginSessionState(signedIn = true, account = "alice"))
        val hint = SourceSessionHint(store) { PluginSessionCheck() }
        hint.load("source")

        hint.clear("source")

        assertEquals(PluginSessionState(), hint.state.value)
        assertEquals(PluginSessionState(), store.stored)
        assertTrue(store.calls.contains("clear:source"))
    }
}
