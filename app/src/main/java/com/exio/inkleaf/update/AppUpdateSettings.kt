package com.exio.inkleaf.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.exio.inkleaf.data.toEnum
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appUpdateDataStore by preferencesDataStore(name = "app_update")

/**
 * Persisted update preferences: the channel the user subscribes to, a version they opted to skip,
 * and the wall-clock time of the last successful manifest fetch.
 */
class AppUpdateSettings(context: Context) {
    private val dataStore = context.applicationContext.appUpdateDataStore

    val channel: Flow<AppUpdateChannel> =
        dataStore.data.map { prefs ->
            prefs[KEY_CHANNEL].toEnum(AppUpdateChannel.STABLE)
        }

    val skippedVersion: Flow<String?> =
        dataStore.data.map { prefs -> prefs[KEY_SKIPPED_VERSION] }

    val lastCheckMs: Flow<Long> =
        dataStore.data.map { prefs -> prefs[KEY_LAST_CHECK_MS] ?: 0L }

    suspend fun setChannel(value: AppUpdateChannel) {
        dataStore.edit { it[KEY_CHANNEL] = value.name }
    }

    suspend fun setSkippedVersion(version: String?) {
        dataStore.edit {
            if (version != null) it[KEY_SKIPPED_VERSION] = version
            else it.remove(KEY_SKIPPED_VERSION)
        }
    }

    suspend fun setLastCheckMs(value: Long) {
        dataStore.edit { it[KEY_LAST_CHECK_MS] = value }
    }

    companion object {
        private val KEY_CHANNEL = stringPreferencesKey("update_channel")
        private val KEY_SKIPPED_VERSION = stringPreferencesKey("skipped_version")
        private val KEY_LAST_CHECK_MS = longPreferencesKey("last_check_ms")

        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/nuttycc/inkleaf-plugins/main/app-update.json"
    }
}
