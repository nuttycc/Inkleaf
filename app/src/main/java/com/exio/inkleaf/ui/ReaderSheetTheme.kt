package com.exio.inkleaf.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

@Composable
internal fun ReaderSheetTheme(
    accent: Color,
    content: @Composable () -> Unit,
) {
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    // 从 M3 默认暗色方案派生，保留 surfaceContainer* 色阶与 onSurfaceVariant 层级；
    // 仅覆盖强调色，让固定标题/选中行等容器色阶走标准语义配色。
    val readerScheme = remember(accent) {
        darkColorScheme().copy(
            primary = accent,
            onPrimary = Color.Black,
        )
    }

    MaterialTheme(
        colorScheme = readerScheme,
        typography = typography,
        shapes = shapes,
        content = content,
    )
}
