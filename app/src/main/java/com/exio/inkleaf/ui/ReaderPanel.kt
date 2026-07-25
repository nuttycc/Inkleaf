package com.exio.inkleaf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
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

internal fun ReaderPanel.title(): String = when (this) {
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
        panel?.let { shownPanel ->
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .semantics { paneTitle = shownPanel.title() },
            ) {
                content(shownPanel)
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

@Composable
internal fun ReaderAttachedPanelDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}
