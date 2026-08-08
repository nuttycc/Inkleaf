package com.exio.inkleaf.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readerSettingsDataStore by preferencesDataStore(name = "reader_settings")

enum class ReaderPageDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
}

enum class ReaderStageBackground {
    BLACK,
    DARK_GRAY,
    BEIGE,
}

enum class ReaderPageStatusPosition {
    START,
    CENTER,
    END,
}

enum class ReaderPageStatusColor {
    AUTO,
    WHITE,
    BLACK,
}

data class ReaderSettings(
    val pageDirection: ReaderPageDirection = ReaderPageDirection.LEFT_TO_RIGHT,
    val stageBackground: ReaderStageBackground = ReaderStageBackground.BLACK,
    val pageStatusPosition: ReaderPageStatusPosition = ReaderPageStatusPosition.CENTER,
    val pageStatusColor: ReaderPageStatusColor = ReaderPageStatusColor.AUTO,
)

/** Converts stored enum names to a complete settings value, including safe defaults. */
internal fun readerSettingsFromStoredValues(
    pageDirection: String?,
    stageBackground: String?,
    pageStatusPosition: String?,
    pageStatusColor: String?,
): ReaderSettings =
    ReaderSettings(
        pageDirection = pageDirection.toEnum(ReaderPageDirection.LEFT_TO_RIGHT),
        stageBackground = stageBackground.toEnum(ReaderStageBackground.BLACK),
        pageStatusPosition = pageStatusPosition.toEnum(ReaderPageStatusPosition.CENTER),
        pageStatusColor = pageStatusColor.toEnum(ReaderPageStatusColor.AUTO),
    )

class ReaderSettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.readerSettingsDataStore

    val settings: Flow<ReaderSettings> =
        dataStore.data.map { prefs ->
            readerSettingsFromStoredValues(
                pageDirection = prefs[KEY_PAGE_DIRECTION],
                stageBackground = prefs[KEY_STAGE_BACKGROUND],
                pageStatusPosition = prefs[KEY_PAGE_STATUS_POSITION],
                pageStatusColor = prefs[KEY_PAGE_STATUS_COLOR],
            )
        }

    suspend fun setPageDirection(value: ReaderPageDirection) {
        dataStore.edit { it[KEY_PAGE_DIRECTION] = value.name }
    }

    suspend fun setStageBackground(value: ReaderStageBackground) {
        dataStore.edit { it[KEY_STAGE_BACKGROUND] = value.name }
    }

    suspend fun setPageStatusPosition(value: ReaderPageStatusPosition) {
        dataStore.edit { it[KEY_PAGE_STATUS_POSITION] = value.name }
    }

    suspend fun setPageStatusColor(value: ReaderPageStatusColor) {
        dataStore.edit { it[KEY_PAGE_STATUS_COLOR] = value.name }
    }

    private companion object {
        val KEY_PAGE_DIRECTION = stringPreferencesKey("page_direction")
        val KEY_STAGE_BACKGROUND = stringPreferencesKey("stage_background")
        val KEY_PAGE_STATUS_POSITION = stringPreferencesKey("page_status_position")
        val KEY_PAGE_STATUS_COLOR = stringPreferencesKey("page_status_color")
    }
}
