package com.exio.inkleaf.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 同名 DataStore 全局只能创建一次：顶层属性委托单例（同 shelfDataStore） */
private val Context.themeDataStore by preferencesDataStore(name = "theme_settings")

/**
 * Built-in seeds use traditional Chinese colors and deliberately avoid the common indigo app
 * palette. Palette style is selected independently in advanced settings; low-chroma seeds are
 * still forced through Neutral at generation time so gray ink never drifts toward purple.
 */
enum class ThemeSeed(val argb: Long, val label: String) {
    INK(0xFF2B2B2E, "墨"),
    ROUGE(0xFF9D2933, "胭脂"),
    AZURITE(0xFF1685A9, "石青"),
    AMBER(0xFFCA6924, "琥珀"),
}

/** 深浅模式：跟随系统 / 强制浅色 / 强制深色 */
enum class DarkMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/** Palette-generation strategies exposed as understandable theme presets. */
enum class CustomStyle(val style: PaletteStyle, val label: String) {
    MUTED(PaletteStyle.Neutral, "素雅"),
    STANDARD(PaletteStyle.TonalSpot, "标准"),
    VIVID(PaletteStyle.Vibrant, "浓艳"),
    EXPRESSIVE(PaletteStyle.Expressive, "跃动"),
    FIDELITY(PaletteStyle.Fidelity, "忠于种子色"),
    CONTENT(PaletteStyle.Content, "内容取向"),
    MONOCHROME(PaletteStyle.Monochrome, "单色"),
}

/** Material dynamic-color generation used for non-wallpaper themes. */
enum class ThemeColorSpec(
    val specVersion: ColorSpec.SpecVersion,
    val label: String,
) {
    MATERIAL_2025(ColorSpec.SpecVersion.SPEC_2025, "Expressive 2025"),
    MATERIAL_2021(ColorSpec.SpecVersion.SPEC_2021, "经典 2021"),
}

/** Named contrast levels mirror Material Kolor and avoid storing raw doubles. */
enum class ThemeContrast(
    val contrast: Contrast,
    val label: String,
    val description: String,
) {
    REDUCED(Contrast.Reduced, "柔和", "降低色彩角色之间的明暗差异"),
    DEFAULT(Contrast.Default, "标准", "遵循 Material 默认对比度"),
    MEDIUM(Contrast.Medium, "增强", "提高文字与控件的辨识度"),
    HIGH(Contrast.High, "最高", "使用最强的可用对比度"),
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
    // Palette style applies to preset and custom seeds; wallpaper schemes ignore it.
    val customStyle: CustomStyle = CustomStyle.STANDARD,
    // Non-wallpaper themes default to the Material 3 Expressive 2025 color specification.
    val colorSpec: ThemeColorSpec = ThemeColorSpec.MATERIAL_2025,
    val contrast: ThemeContrast = ThemeContrast.DEFAULT,
    // AMOLED affects dark schemes only; light themes keep their normal Material surfaces.
    val useAmoled: Boolean = false,
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
            colorSpec = prefs[KEY_COLOR_SPEC].toEnum(ThemeColorSpec.MATERIAL_2025),
            contrast = prefs[KEY_CONTRAST].toEnum(ThemeContrast.DEFAULT),
            useAmoled = prefs[KEY_USE_AMOLED] ?: false,
        )
    }

    /** Persists the complete editor draft atomically so no partial theme can reach the next Activity. */
    suspend fun setSettings(value: ThemeSettings) {
        dataStore.edit {
            it[KEY_SEED] = value.seed.name
            it[KEY_DARK_MODE] = value.darkMode.name
            it[KEY_USE_WALLPAPER] = value.useWallpaper
            if (value.customArgb == null) {
                it.remove(KEY_CUSTOM_ARGB)
            } else {
                it[KEY_CUSTOM_ARGB] = value.customArgb
            }
            it[KEY_USE_CUSTOM] = value.useCustom
            it[KEY_CUSTOM_STYLE] = value.customStyle.name
            it[KEY_COLOR_SPEC] = value.colorSpec.name
            it[KEY_CONTRAST] = value.contrast.name
            it[KEY_USE_AMOLED] = value.useAmoled
        }
    }

    companion object {
        private val KEY_SEED = stringPreferencesKey("theme_seed")
        private val KEY_DARK_MODE = stringPreferencesKey("dark_mode")
        private val KEY_USE_WALLPAPER = booleanPreferencesKey("use_wallpaper")
        private val KEY_CUSTOM_ARGB = longPreferencesKey("custom_argb")
        private val KEY_USE_CUSTOM = booleanPreferencesKey("use_custom")
        private val KEY_CUSTOM_STYLE = stringPreferencesKey("custom_style")
        private val KEY_COLOR_SPEC = stringPreferencesKey("color_spec")
        private val KEY_CONTRAST = stringPreferencesKey("contrast")
        private val KEY_USE_AMOLED = booleanPreferencesKey("use_amoled")
    }
}
