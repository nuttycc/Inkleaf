package com.exio.comicreader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.materialkolor.PaletteStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 同名 DataStore 全局只能创建一次：顶层属性委托单例（同 shelfDataStore） */
private val Context.themeDataStore by preferencesDataStore(name = "theme_settings")

/**
 * 内置主题种子。每个预设绑定自己的调色风格：
 * 低饱和的墨色必须用 Neutral——TonalSpot 会把灰"提纯"成紫灰；
 * 其余有彩度的种子用 TonalSpot（M3 标准观感）。
 */
enum class ThemeSeed(val argb: Long, val style: PaletteStyle, val label: String) {
    INK(0xFF2B2B2E, PaletteStyle.Neutral, "墨"),
    INDIGO(0xFF3A5070, PaletteStyle.TonalSpot, "黛"),
    VERMILION(0xFF9A3B2E, PaletteStyle.TonalSpot, "朱"),
    TEA(0xFF6B5B45, PaletteStyle.TonalSpot, "茶"),
    BAMBOO(0xFF4A6B4F, PaletteStyle.TonalSpot, "竹"),
}

/** 深浅模式：跟随系统 / 强制浅色 / 强制深色 */
enum class DarkMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

data class ThemeSettings(
    val seed: ThemeSeed = ThemeSeed.INK,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    // 壁纸取色（Material You，API 31+）。开启时忽略 seed
    val useWallpaper: Boolean = false,
)

/**
 * 主题设置的唯一持久化来源。只存"种子 + 模式"两三个原始值，
 * 整套 ColorScheme 由种子在运行时重新生成——不存派生数据。
 */
class ThemeSettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.themeDataStore

    val settings: Flow<ThemeSettings> = dataStore.data.map { prefs ->
        ThemeSettings(
            seed = prefs[KEY_SEED].toEnum(ThemeSeed.INK),
            darkMode = prefs[KEY_DARK_MODE].toEnum(DarkMode.SYSTEM),
            useWallpaper = prefs[KEY_USE_WALLPAPER] ?: false,
        )
    }

    suspend fun setSeed(value: ThemeSeed) {
        dataStore.edit {
            it[KEY_SEED] = value.name
            // 选了具体种子就视为放弃壁纸取色，避免"点了色卡却没反应"
            it[KEY_USE_WALLPAPER] = false
        }
    }

    suspend fun setDarkMode(value: DarkMode) {
        dataStore.edit { it[KEY_DARK_MODE] = value.name }
    }

    suspend fun setUseWallpaper(value: Boolean) {
        dataStore.edit { it[KEY_USE_WALLPAPER] = value }
    }

    companion object {
        private val KEY_SEED = stringPreferencesKey("theme_seed")
        private val KEY_DARK_MODE = stringPreferencesKey("dark_mode")
        private val KEY_USE_WALLPAPER = booleanPreferencesKey("use_wallpaper")

        /** 存储值损坏或来自旧版枚举：回退默认 */
        private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
            this?.let { stored -> runCatching { enumValueOf<T>(stored) }.getOrNull() } ?: default
    }
}
