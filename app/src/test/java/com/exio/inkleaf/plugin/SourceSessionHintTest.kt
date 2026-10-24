package com.exio.inkleaf.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSessionHintTest {

    internal class RecordingStore(initial: PluginSessionState = PluginSessionState()) :
        SourceSessionStore {
        private val states = mutableMapOf<String, MutableStateFlow<PluginSessionState>>()
        private val initialState = initial
        val calls = mutableListOf<String>()

        private fun flow(pluginId: String) =
            states.getOrPut(pluginId) { MutableStateFlow(initialState) }

        fun stored(pluginId: String = "source") = flow(pluginId).value

        override fun observe(pluginId: String) = flow(pluginId)

        override suspend fun read(pluginId: String) = flow(pluginId).value

        override suspend fun signedIn(pluginId: String, account: String?) {
            flow(pluginId).value = PluginSessionState(signedIn = true, account = account)
            calls += "in:$pluginId:${account.orEmpty()}"
        }

        override suspend fun signedOut(pluginId: String) {
            flow(pluginId).value = PluginSessionState()
            calls += "out:$pluginId"
        }

        override suspend fun clear(pluginId: String) {
            flow(pluginId).value = PluginSessionState()
            calls += "clear:$pluginId"
        }
    }

    private val alice = PluginSessionState(signedIn = true, account = "alice")

    private fun login() = PluginActionDescriptor("login", "Login", kind = "login")

    private fun logout() = PluginActionDescriptor("logout", "Logout", kind = "clearSession")

    private fun result(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    @Test
    fun `observe reports the stored hint`() = runBlocking {
        val hint = SourceSessionHint(RecordingStore(alice)) { PluginSessionCheck() }

        assertEquals(alice, hint.observe("source").first())
    }

    @Test
    fun `login records the reported account`() = runBlocking {
        val store = RecordingStore()
        val hint = SourceSessionHint(store) { PluginSessionCheck() }

        hint.applyOutcome("source", login(), result("account" to "alice"))

        assertEquals(alice, store.stored())
    }

    @Test
    fun `login keeps the previous account when the plugin reports none`() = runBlocking {
        val store = RecordingStore(alice)
        val hint = SourceSessionHint(store) { PluginSessionCheck() }

        hint.applyOutcome("source", login(), result("message" to "登录成功"))

        assertEquals(alice, store.stored())
    }

    @Test
    fun `verifying credentials also marks the source signed in`() = runBlocking {
        val store = RecordingStore()
        val hint = SourceSessionHint(store) { PluginSessionCheck() }

        hint.applyOutcome(
            "source",
            PluginActionDescriptor("verify", "Verify", kind = "verifyCredentials"),
            result("account" to "alice"),
        )

        assertEquals(alice, store.stored())
    }

    @Test
    fun `sign out clears the stored hint`() = runBlocking {
        val store = RecordingStore(alice)
        val hint = SourceSessionHint(store) { PluginSessionCheck() }

        hint.applyOutcome("source", logout(), result("message" to "bye"))

        assertEquals(PluginSessionState(), store.stored())
    }

    @Test
    fun `unrelated actions write nothing`() = runBlocking {
        val store = RecordingStore(alice)
        val hint = SourceSessionHint(store) { PluginSessionCheck() }

        hint.applyOutcome("source", PluginActionDescriptor("refresh", "Refresh"), result())

        assertEquals(alice, store.stored())
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun `an auth-required answer clears a recorded session`() = runBlocking {
        val store = RecordingStore(alice)
        val hint = SourceSessionHint(store) { PluginSessionCheck() }

        hint.authRequired("source")

        assertEquals(PluginSessionState(), store.stored())
        assertEquals(listOf("out:source"), store.calls)
    }

    @Test
    fun `an auth-required answer writes nothing when already signed out`() = runBlocking {
        val store = RecordingStore()
        val hint = SourceSessionHint(store) { PluginSessionCheck() }

        hint.authRequired("source")

        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun `hints are kept per source`() = runBlocking {
        val store = RecordingStore()
        val hint = SourceSessionHint(store) { PluginSessionCheck() }

        hint.applyOutcome("a", login(), result("account" to "alice"))

        assertEquals(alice, store.stored("a"))
        assertEquals(PluginSessionState(), store.stored("b"))
    }

    @Test
    fun `reconcile adopts a live session`() = runBlocking {
        val store = RecordingStore()
        val hint =
            SourceSessionHint(store) { PluginSessionCheck(signedIn = true, account = "alice") }

        hint.reconcile("source")

        assertEquals(alice, store.stored())
    }

    @Test
    fun `reconcile clears a stale hint`() = runBlocking {
        val store = RecordingStore(alice)
        val hint = SourceSessionHint(store) { PluginSessionCheck(signedIn = false) }

        hint.reconcile("source")

        assertEquals(PluginSessionState(), store.stored())
    }

    @Test
    fun `reconcile keeps the hint when the check fails`() = runBlocking {
        val store = RecordingStore(alice)
        val hint = SourceSessionHint(store) { throw IllegalStateException("offline") }

        hint.reconcile("source")

        assertEquals(alice, store.stored())
    }

    @Test
    fun `a late check does not overwrite a newer login`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = RecordingStore()
        val hint =
            SourceSessionHint(store) {
                started.complete(Unit)
                release.await()
                PluginSessionCheck(signedIn = false)
            }

        val job = launch(Dispatchers.Default) { hint.reconcile("source") }
        started.await()
        hint.applyOutcome("source", login(), result("account" to "alice"))
        release.complete(Unit)
        job.join()

        assertEquals(alice, store.stored())
    }

    @Test
    fun `clear resets the store`() = runBlocking {
        val store = RecordingStore(alice)
        val hint = SourceSessionHint(store) { PluginSessionCheck() }

        hint.clear("source")

        assertEquals(PluginSessionState(), store.stored())
        assertTrue(store.calls.contains("clear:source"))
    }
}
