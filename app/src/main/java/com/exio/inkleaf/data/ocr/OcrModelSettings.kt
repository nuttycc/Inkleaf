package com.exio.inkleaf.data.ocr

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ocrModelDataStore by preferencesDataStore(name = "ocr_model_settings")

class OcrModelSettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.ocrModelDataStore

    val activeVariant: Flow<OcrModelVariant> =
        dataStore.data.map { preferences ->
            preferences[ACTIVE_MODEL_KEY]?.let { id ->
                OcrModelVariant.entries.firstOrNull { it.id == id }
            } ?: OcrModelVariant.SMALL
        }

    suspend fun setActiveVariant(variant: OcrModelVariant) {
        dataStore.edit { preferences -> preferences[ACTIVE_MODEL_KEY] = variant.id }
    }

    private companion object {
        val ACTIVE_MODEL_KEY = stringPreferencesKey("active_model")
    }
}
