package com.exio.inkleaf.plugin

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A top-level delegate guarantees one DataStore instance for this name. */
private val Context.pluginSessionDataStore by preferencesDataStore(name = "plugin_session")

/** Whether the host believes a source has a live session, and the account it belongs to. */
data class PluginSessionState(val signedIn: Boolean = false, val account: String? = null)

/**
 * Host-side memory of a source's sign-in state.
 *
 * The host cannot know whether a site session is still valid, so this is a UI hint recorded from
 * the outcome of the plugin's own login and sign-out actions, never an authority on server state.
 *
 * It lives in its own DataStore because [PluginSettingsRepository.setValues] removes every stored
 * key outside the current descriptor snapshot, which would erase a reserved key on the next save.
 */
class PluginSessionStateRepository(context: Context) {
    private val dataStore = context.applicationContext.pluginSessionDataStore

    fun observe(pluginId: String): Flow<PluginSessionState> =
        dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map { prefs ->
                PluginSessionState(
                    signedIn = prefs[signedInKey(pluginId)] == true,
                    account = prefs[accountKey(pluginId)]?.takeIf { it.isNotBlank() },
                )
            }

    suspend fun state(pluginId: String): PluginSessionState = observe(pluginId).first()

    suspend fun signedIn(pluginId: String, account: String?) {
        dataStore.edit { prefs ->
            prefs[signedInKey(pluginId)] = true
            val trimmed = account?.trim()
            if (trimmed.isNullOrEmpty()) prefs.remove(accountKey(pluginId))
            else prefs[accountKey(pluginId)] = trimmed
        }
    }

    suspend fun signedOut(pluginId: String) {
        dataStore.edit { prefs ->
            prefs.remove(signedInKey(pluginId))
            prefs.remove(accountKey(pluginId))
        }
    }

    /** Removes the stored session as part of the source uninstall and reset contract. */
    suspend fun clear(pluginId: String) {
        signedOut(pluginId)
    }

    private fun signedInKey(pluginId: String) = booleanPreferencesKey("$pluginId\nsignedIn")

    private fun accountKey(pluginId: String) = stringPreferencesKey("$pluginId\naccount")
}
