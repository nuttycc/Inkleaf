package com.exio.inkleaf.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSettingsDraftTest {

    private class RecordingStore(private val beforeSet: suspend () -> Unit = {}) :
        SourceSettingsStore {
        val setCalls = mutableListOf<SetCall>()
        val clearCalls = mutableListOf<String>()

        override suspend fun setValues(
            pluginId: String,
            values: Map<String, String>,
            settingIds: Set<String>,
        ) {
            beforeSet()
            setCalls += SetCall(pluginId, values, settingIds)
        }

        override suspend fun clear(pluginId: String) {
            clearCalls += pluginId
        }
    }

    private data class SetCall(
        val pluginId: String,
        val values: Map<String, String>,
        val settingIds: Set<String>,
    )

    @Test
    fun `load exposes stored values and starts clean`() {
        val draft = SourceSettingsDraft(RecordingStore())

        draft.load(mapOf("a" to "1"))

        assertEquals(mapOf("a" to "1"), draft.values.value)
        assertFalse(draft.hasPending())
    }

    @Test
    fun `setValue is optimistic and marks the draft pending`() {
        val draft = SourceSettingsDraft(RecordingStore())
        draft.load(emptyMap())

        draft.setValue("a", "1")

        assertEquals("1", draft.values.value["a"])
        assertTrue(draft.hasPending())
    }

    @Test
    fun `setting the same value does not mark the draft pending`() {
        val draft = SourceSettingsDraft(RecordingStore())
        draft.load(mapOf("a" to "1"))
        draft.setValue("a", "1")

        assertFalse(draft.hasPending())
    }

    @Test
    fun `flush persists the snapshot once and clears pending`() = runBlocking {
        val store = RecordingStore()
        val draft = SourceSettingsDraft(store)
        draft.load(emptyMap(), setOf("a"))
        draft.setValue("a", "1")

        draft.flush("source")
        draft.flush("source")

        assertEquals(listOf(SetCall("source", mapOf("a" to "1"), setOf("a"))), store.setCalls)
        assertFalse(draft.hasPending())
    }

    @Test
    fun `flush without descriptor ids fails instead of persisting unfiltered values`() = runBlocking {
        val store = RecordingStore()
        val draft = SourceSettingsDraft(store)
        draft.load(mapOf("a" to "1"))
        draft.setValue("a", "2")

        val error = runCatching { draft.flush("source") }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("Plugin setting descriptors are unavailable", error?.message)
        assertTrue(store.setCalls.isEmpty())
    }

    @Test
    fun `an edit during a flush stays pending`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store =
            RecordingStore {
                started.complete(Unit)
                release.await()
            }
        val draft = SourceSettingsDraft(store)
        draft.load(emptyMap(), setOf("a", "b"))
        draft.setValue("a", "1")

        val job = launch(Dispatchers.Default) { draft.flush("source") }
        started.await()
        draft.setValue("b", "2")
        release.complete(Unit)
        job.join()

        assertTrue(draft.hasPending())
        draft.flush("source")
        assertEquals(mapOf("a" to "1", "b" to "2"), store.setCalls.last().values)
    }

    @Test
    fun `clear empties the snapshot and persists the clear`() = runBlocking {
        val store = RecordingStore()
        val draft = SourceSettingsDraft(store)
        draft.load(mapOf("a" to "1"), setOf("a"))
        draft.setValue("a", "2")

        draft.clear("source")

        assertTrue(draft.values.value.isEmpty())
        assertFalse(draft.hasPending())
        assertEquals(listOf("source"), store.clearCalls)
    }

    @Test
    fun `markClean accepts an external clear`() {
        val draft = SourceSettingsDraft(RecordingStore())
        draft.load(emptyMap(), setOf("a"))
        draft.setValue("a", "1")

        draft.markClean()

        assertFalse(draft.hasPending())
    }
}
