package com.exio.comicreader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
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
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import android.graphics.Color as AndroidColor

/**
 * 自定义种子色的实际调色风格。复用 INK 预设的经验：低饱和的颜色
 * （hex 输入进来的灰/米白等）必须走 Neutral，TonalSpot/Vibrant 会把
 * 灰"提纯"成紫灰；有彩度的照用户选的浓淡档。
 */
private fun customSeedStyle(argb: Long, preferred: PaletteStyle): PaletteStyle {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(argb.toInt(), hsv)
    return if (hsv[1] < 0.15f) PaletteStyle.Neutral else preferred
}

/**
 * 深浅模式设置 → 当前是否应用深色主题（SYSTEM 跟随系统）。
 * 主题生成和设置页的取色预览共用同一份判定，保证两处永远一致。
 */
@Composable
fun DarkMode.isDarkTheme(): Boolean = when (this) {
    DarkMode.SYSTEM -> isSystemInDarkTheme()
    DarkMode.LIGHT -> false
    DarkMode.DARK -> true
}

/**
 * 全 App 主题：由用户设置（种子色 + 深浅模式 + 壁纸取色）驱动。
 *
 * 整套 ColorScheme 是种子色的纯函数——存储里只有种子，30+ 个色彩角色
 * 由 material-kolor 在运行时按 M3 调色算法生成（自动满足对比度要求）。
 * 注意阅读页（ReaderScreen）基底不消费这里的颜色（沉浸阅读永远黑底白字），
 * 仅强调色（胶片高亮、滑杆填充）取自主题，并做了黑底亮度兜底。
 *
 * ## 换肤策略：全树同帧瞬切
 *
 * 成立的前提是窗口内没有任何组件保有"自己的、被内部动画接管的主题色"。
 * M3 的 TopAppBar 内部用关不掉的弹簧追 containerColor，瞬切时会比页面
 * 慢半拍（顶部割裂闪烁）——因此各屏顶栏的底色一律设为透明（顶栏色
 * surface 与背景 background 在 M3 配色里本就同值，透明不改变静止外观），
 * 顶部条带显示的就是页面背景本身，与主体物理同色，割裂无从发生。
 * 曾试过两条动画路线（整套 scheme 逐帧渐变 / 截图盖板交叉淡出），均
 * 无法彻底消除割裂且引入额外开销，复盘见
 * .agents/lessons/theme-switch-color-motion-desync.md
 */
@Composable
fun ComicReaderTheme(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    val isDark = settings.darkMode.isDarkTheme()

    val colorScheme = when {
        settings.useWallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // Material You 壁纸取色：系统算好的 scheme，API 31+
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        settings.useCustom && settings.customArgb != null -> rememberDynamicColorScheme(
            seedColor = Color(settings.customArgb),
            isDark = isDark,
            style = customSeedStyle(settings.customArgb, settings.customStyle.style),
        )

        else -> rememberDynamicColorScheme(
            seedColor = Color(settings.seed.argb),
            isDark = isDark,
            style = settings.seed.style,
        )
    }

    // 系统栏前景明暗要跟随"应用主题"而不是"系统深色模式"：
    // 用户强制深色而系统是浅色时，不设置这里会出现深色图标压深色背景。
    // 与内容同帧翻转；图标自身的过渡动画由 SystemUI 掌控，App 管不到
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }

    // MaterialExpressiveTheme：未显式传入的 token（形状/动效）取 expressive
    // 默认值——菜单等组件的圆角、弹簧动效由此而来；色彩和字体仍是我们自己的
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
