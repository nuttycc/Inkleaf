package com.exio.comicreader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.materialkolor.PaletteStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 同名 DataStore 全局只能创建一次：顶层属性委托单例（同 shelfDataStore） */
private val Context.themeDataStore by preferencesDataStore(name = "theme_settings")

/**
 * 内置主题种子。色值取自中国社科院《色谱》一系的传统色名录
 * （zhongguose.com 同源），刻意避开主流 App 主色扎堆的靛蓝区：
 * 胭脂（玫红）/ 石青（青而非蓝）/ 琥珀（暖金棕）两两相隔约 170°。
 * 低饱和的墨色必须用 Neutral——TonalSpot 会把灰"提纯"成紫灰；
 * 其余有彩度的种子用 TonalSpot（M3 标准观感）。
 */
enum class ThemeSeed(val argb: Long, val style: PaletteStyle, val label: String) {
    INK(0xFF2B2B2E, PaletteStyle.Neutral, "墨"),
    ROUGE(0xFF9D2933, PaletteStyle.TonalSpot, "胭脂"),
    AZURITE(0xFF1685A9, PaletteStyle.TonalSpot, "石青"),
    AMBER(0xFFCA6924, PaletteStyle.TonalSpot, "琥珀"),
}

/** 深浅模式：跟随系统 / 强制浅色 / 强制深色 */
enum class DarkMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/**
 * 自定义色的浓淡档。饱和度滑条在 M3 调色下是无效轴（chroma 会被
 * PaletteStyle 限幅），但 PaletteStyle 本身是真实有效的第二轴：
 * 同一色相在三档下是三套可分辨的主题。
 */
enum class CustomStyle(val style: PaletteStyle, val label: String) {
    MUTED(PaletteStyle.Neutral, "素雅"),
    STANDARD(PaletteStyle.TonalSpot, "标准"),
    VIVID(PaletteStyle.Vibrant, "浓艳"),
}

data class ThemeSettings(
    val seed: ThemeSeed = ThemeSeed.INK,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    // 壁纸取色（Material You，API 31+）。开启时忽略 seed / 自定义色
    val useWallpaper: Boolean = false,
    // 用户自定义种子色（ARGB）。null = 从未设置过；设置过就一直留着，
    // 即使切回预设种子，色卡上也能展示"上次的自定义色"
    val customArgb: Long? = null,
    // 来源三选一里的"自定义"档：true 时忽略 seed（useWallpaper 仍优先）
    val useCustom: Boolean = false,
    // 自定义色的浓淡档，仅在自定义档生效
    val customStyle: CustomStyle = CustomStyle.STANDARD,
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
            customArgb = prefs[KEY_CUSTOM_ARGB],
            useCustom = prefs[KEY_USE_CUSTOM] ?: false,
            customStyle = prefs[KEY_CUSTOM_STYLE].toEnum(CustomStyle.STANDARD),
        )
    }

    suspend fun setSeed(value: ThemeSeed) {
        dataStore.edit {
            it[KEY_SEED] = value.name
            // 选了具体种子就视为放弃壁纸取色/自定义色，避免"点了色卡却没反应"
            it[KEY_USE_WALLPAPER] = false
            it[KEY_USE_CUSTOM] = false
        }
    }

    /** 设置并启用自定义种子色。与预设种子、壁纸取色互斥 */
    suspend fun setCustomColor(argb: Long) {
        dataStore.edit {
            it[KEY_CUSTOM_ARGB] = argb
            it[KEY_USE_CUSTOM] = true
            it[KEY_USE_WALLPAPER] = false
        }
    }

    /**
     * 设置自定义色的浓淡档。已设置过自定义色时顺带启用自定义档
     * （在取色面板里调浓淡 = 在调"我的自定义主题"）；从未取过色
     * 则只记档位，不产生可见变化
     */
    suspend fun setCustomStyle(value: CustomStyle) {
        dataStore.edit {
            it[KEY_CUSTOM_STYLE] = value.name
            if (it[KEY_CUSTOM_ARGB] != null) {
                it[KEY_USE_CUSTOM] = true
                it[KEY_USE_WALLPAPER] = false
            }
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
        private val KEY_CUSTOM_ARGB = longPreferencesKey("custom_argb")
        private val KEY_USE_CUSTOM = booleanPreferencesKey("use_custom")
        private val KEY_CUSTOM_STYLE = stringPreferencesKey("custom_style")

        /** 存储值损坏或来自旧版枚举：回退默认 */
        private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
            this?.let { stored -> runCatching { enumValueOf<T>(stored) }.getOrNull() } ?: default
    }
}
