package com.exio.inkleaf.plugin

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A top-level delegate guarantees one DataStore instance for this name. */
private val Context.pluginSettingsDataStore by preferencesDataStore(name = "plugin_settings")

/**
 * Host-side owner of source settings.
 *
 * Plugins declare settings through describe(). The site-agnostic host persists user choices and
 * returns them through settings.get.
 *
 * Descriptor caching avoids a reentrant RPC: settings.get is called while plugin code awaits a
 * host response, so calling describe() at that point could deadlock. Isolate startup prewarms the
 * cache and settings.get remains a direct memory lookup.
 *
 * The cache need not be persisted because a running plugin always passed isolate prewarming.
 */
class PluginSettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.pluginSettingsDataStore
    private val descriptors = ConcurrentHashMap<String, List<PluginSettingDescriptor>>()

    /** Stored values for a source, excluding untouched descriptor defaults. */
    fun observeValues(pluginId: String): Flow<Map<String, String>> =
        dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map { prefs -> prefs.readNamespace(pluginId) }

    suspend fun storedValues(pluginId: String): Map<String, String> =
        observeValues(pluginId).first()

    /** Persists a settings snapshot in one DataStore transaction. */
    suspend fun setValues(pluginId: String, values: Map<String, String>) {
        dataStore.edit { prefs ->
            values.forEach { (settingId, value) ->
                prefs[compositeKey(pluginId, settingId)] = value
            }
        }
    }

    /** Clears settings as part of the source uninstall contract. */
    suspend fun clear(pluginId: String) {
        descriptors.remove(pluginId)
        val prefix = namespacePrefix(pluginId)
        dataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(prefix) }.forEach { prefs.remove(it) }
        }
    }

    /** Prewarms descriptors from the same code backing the running isolate. */
    fun cacheDescriptors(pluginId: String, value: List<PluginSettingDescriptor>) {
        descriptors[pluginId] = value
    }

    fun descriptors(pluginId: String): List<PluginSettingDescriptor> =
        descriptors[pluginId].orEmpty()

    fun forgetDescriptors(pluginId: String) {
        descriptors.remove(pluginId)
    }

    /**
     * Resolves a stored value first, then its descriptor default.
     *
     * Resolving defaults in the host keeps this contract consistent across plugins and prevents
     * missing plugin-side fallbacks from producing undefined behavior.
     */
    suspend fun resolve(pluginId: String, settingId: String): String? =
        storedValues(pluginId)[settingId]
            ?: descriptors(pluginId).firstOrNull { it.id == settingId }?.defaultValue

    private fun Preferences.readNamespace(pluginId: String): Map<String, String> {
        val prefix = namespacePrefix(pluginId)
        return asMap()
            .asSequence()
            .filter { (key, value) -> key.name.startsWith(prefix) && value is String }
            .associate { (key, value) -> key.name.removePrefix(prefix) to value as String }
    }

    private fun compositeKey(pluginId: String, settingId: String) =
        stringPreferencesKey(namespacePrefix(pluginId) + settingId)

    // Newline is safe because validateId rejects ISO control characters in setting ids.
    private fun namespacePrefix(pluginId: String) = "$pluginId\n"
}
