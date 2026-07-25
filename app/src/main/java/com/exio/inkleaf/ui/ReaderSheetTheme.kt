package com.exio.inkleaf.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified

@Composable
internal fun ReaderSheetTheme(
    accent: Color,
    content: @Composable () -> Unit,
) {
    val currentScheme = MaterialTheme.colorScheme
    val readerScheme =
        remember(accent, currentScheme) {
            if (accent.isSpecified) {
                currentScheme.copy(primary = accent)
            } else {
                currentScheme
            }
        }

    MaterialTheme(
        colorScheme = readerScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
