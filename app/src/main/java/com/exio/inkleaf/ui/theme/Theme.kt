package com.exio.inkleaf.ui.theme

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.exio.inkleaf.data.ThemeSettings
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

/**
 * Applies the selected palette style unless the seed is nearly neutral. Material's chromatic
 * variants can turn gray seeds purple, so low-saturation colors always use Neutral regardless of
 * the advanced setting.
 */
internal fun resolvedPaletteStyle(argb: Long, preferred: PaletteStyle): PaletteStyle {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(argb.toInt(), hsv)
    return if (hsv[1] < 0.15f) PaletteStyle.Neutral else preferred
}

/**
 * 全 App 主题：由用户设置（种子色 + 壁纸取色）和 Activity 配置驱动。
 *
 * 整套 ColorScheme 是种子色的纯函数——存储里只有种子，30+ 个色彩角色 由 material-kolor 在运行时按 M3 调色算法生成（自动满足对比度要求）。
 * 注意阅读页（ReaderScreen）基底不消费这里的颜色（沉浸阅读永远黑底白字）， 仅强调色（胶片高亮、滑杆填充）取自主题，并做了黑底亮度兜底。
 *
 * 深浅模式通过 Android uiMode 应用并重建 Activity。每个 Activity 实例只消费 启动时读取的一份 ThemeSettings，因此 Material
 * 组件不会保留旧主题颜色再各自动画。
 */
@Composable
fun InkleafTheme(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val colorScheme = rememberInkleafColorScheme(settings, isDark)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

/** Generates the same scheme for the app root and the isolated editor specimen. */
@Composable
internal fun rememberInkleafColorScheme(
    settings: ThemeSettings,
    isDark: Boolean,
): ColorScheme =
    when {
        settings.useWallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // Material You 壁纸取色：系统算好的 scheme，API 31+
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        settings.useCustom && settings.customArgb != null ->
            rememberDynamicColorScheme(
                seedColor = Color(settings.customArgb),
                isDark = isDark,
                isAmoled = isDark && settings.useAmoled,
                style = resolvedPaletteStyle(settings.customArgb, settings.customStyle.style),
                contrastLevel = settings.contrast.contrast.value,
                specVersion = settings.colorSpec.specVersion,
            )

        else ->
            rememberDynamicColorScheme(
                seedColor = Color(settings.seed.argb),
                isDark = isDark,
                isAmoled = isDark && settings.useAmoled,
                style = resolvedPaletteStyle(settings.seed.argb, settings.customStyle.style),
                contrastLevel = settings.contrast.contrast.value,
                specVersion = settings.colorSpec.specVersion,
            )
    }
