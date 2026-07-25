package com.exio.inkleaf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class ReaderPanel {
    Chapters,
    Bookmarks,
    Tools,
}

private fun ReaderPanel.title(): String = when (this) {
    ReaderPanel.Chapters -> "章节列表"
    ReaderPanel.Bookmarks -> "本书书签"
    ReaderPanel.Tools -> "当前页工具"
}

@Composable
internal fun ReaderAttachedPanel(
    panel: ReaderPanel?,
    accent: Color,
    content: @Composable ColumnScope.(ReaderPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    ReaderSheetTheme(accent = accent) {
        AnimatedVisibility(
            visible = panel != null,
            enter = slideInVertically(tween(200)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
            modifier = modifier.fillMaxWidth(),
        ) {
            panel?.let { shownPanel ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { paneTitle = shownPanel.title() },
                ) {
                    content(shownPanel)
                }
            }
        }
    }
}

@Composable
internal fun ReaderAttachedPanelHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
