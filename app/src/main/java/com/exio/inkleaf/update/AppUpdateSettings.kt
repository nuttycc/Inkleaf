package com.exio.inkleaf.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appUpdateDataStore by preferencesDataStore(name = "app_update")

/**
 * Persisted update preferences: a version the user opted to skip and the wall-clock time of the
 * last successful manifest fetch.
 */
class AppUpdateSettings(context: Context) {
    private val dataStore = context.applicationContext.appUpdateDataStore

    val skippedVersion: Flow<String?> =
        dataStore.data.map { prefs -> prefs[KEY_SKIPPED_VERSION] }

    val lastCheckMs: Flow<Long> =
        dataStore.data.map { prefs -> prefs[KEY_LAST_CHECK_MS] ?: 0L }

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
        private val KEY_SKIPPED_VERSION = stringPreferencesKey("skipped_version")
        private val KEY_LAST_CHECK_MS = longPreferencesKey("last_check_ms")

        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/nuttycc/inkleaf/main/app-update.json"
    }
}
