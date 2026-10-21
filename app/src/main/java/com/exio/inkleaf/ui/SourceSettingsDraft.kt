package com.exio.inkleaf.ui

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persistence behind [SourceSettingsDraft]. */
internal interface SourceSettingsStore {
    suspend fun setValues(pluginId: String, values: Map<String, String>, settingIds: Set<String>)

    suspend fun clear(pluginId: String)
}

/**
 * Owns the values shown by the source detail screen and the rule that an edit becomes durable before
 * an action runs. The revision pair keeps an edit made while a save is in flight from being marked
 * persisted, so the next flush persists it.
 */
internal class SourceSettingsDraft(private val store: SourceSettingsStore) {
    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    val values: StateFlow<Map<String, String>> = _values.asStateFlow()

    private var authoritativeSettingIds: Set<String>? = null
    private val settingsRevision = AtomicLong(0L)
    private val persistedSettingsRevision = AtomicLong(0L)
    private val saveMutex = Mutex()

    /** Replaces the displayed values with a stored snapshot and starts a clean revision. */
    fun load(values: Map<String, String>, settingIds: Set<String>? = null) {
        _values.value = values
        authoritativeSettingIds = settingIds
        markClean()
    }

    /** Adopts the descriptor ids a save may persist. */
    fun adoptDescriptorIds(settingIds: Set<String>) {
        authoritativeSettingIds = settingIds
    }

    fun setValue(settingId: String, value: String) {
        if (_values.value[settingId] == value) return
        // Update optimistically so controls do not bounce while DataStore catches up.
        _values.value = _values.value + (settingId to value)
        settingsRevision.incrementAndGet()
    }

    fun hasPending(): Boolean = settingsRevision.get() != persistedSettingsRevision.get()

    /**
     * Persists the pending snapshot under the save mutex. An edit that lands while the save is in
     * flight leaves a newer revision behind, so the next flush persists it too.
     */
    suspend fun flush(pluginId: String) {
        saveMutex.withLock {
            val revision = settingsRevision.get()
            if (revision == persistedSettingsRevision.get()) return@withLock
            val values = _values.value
            val settingIds =
                checkNotNull(authoritativeSettingIds) {
                    "Plugin setting descriptors are unavailable"
                }
            store.setValues(pluginId, values, settingIds)
            persistedSettingsRevision.set(revision)
        }
    }

    /** Clears stored and displayed values; descriptor ids stay valid for later edits. */
    suspend fun clear(pluginId: String) {
        saveMutex.withLock {
            store.clear(pluginId)
            _values.value = emptyMap()
            val revision = settingsRevision.incrementAndGet()
            persistedSettingsRevision.set(revision)
        }
    }

    /** Marks the current snapshot persisted without writing, after uninstall cleared it. */
    fun markClean() {
        persistedSettingsRevision.set(settingsRevision.get())
    }
}
