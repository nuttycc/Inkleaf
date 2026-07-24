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
    val readerScheme = remember(accent) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color.Black,
            surface = Color(0xFF121212),
            onSurface = Color.White,
            surfaceVariant = Color(0xFF242424),
            onSurfaceVariant = Color(0xFFD0D0D0),
        )
    }

    MaterialTheme(
        colorScheme = readerScheme,
        typography = typography,
        shapes = shapes,
        content = content,
    )
}
