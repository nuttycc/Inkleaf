package com.exio.comicreader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.exio.comicreader.data.DarkMode
import com.exio.comicreader.data.ThemeSettings
import com.materialkolor.rememberDynamicColorScheme

/**
 * 全 App 主题：由用户设置（种子色 + 深浅模式 + 壁纸取色）驱动。
 *
 * 整套 ColorScheme 是种子色的纯函数——存储里只有种子，30+ 个色彩角色
 * 由 material-kolor 在运行时按 M3 调色算法生成（自动满足对比度要求）。
 * 注意阅读页（ReaderScreen）基底不消费这里的颜色（沉浸阅读永远黑底白字），
 * 仅强调色（胶片高亮、滑杆填充）取自主题，并做了黑底亮度兜底。
 */
@Composable
fun ComicReaderTheme(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    val isDark = when (settings.darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }

    val colorScheme = if (settings.useWallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Material You 壁纸取色：系统算好的 scheme，API 31+
        val context = LocalContext.current
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        rememberDynamicColorScheme(
            seedColor = Color(settings.seed.argb),
            isDark = isDark,
            style = settings.seed.style,
        )
    }

    // 系统栏前景明暗要跟随"应用主题"而不是"系统深色模式"：
    // 用户强制深色而系统是浅色时，不设置这里会出现深色图标压深色背景
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
