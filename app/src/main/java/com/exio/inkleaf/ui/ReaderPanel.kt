package com.exio.inkleaf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
internal fun ReaderPanelHost(
    panel: ReaderPanel?,
    accent: Color,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(ReaderPanel) -> Unit,
) {
    var previousPanel by remember { mutableStateOf<ReaderPanel?>(null) }
    LaunchedEffect(panel) {
        if (panel != null) previousPanel = panel
    }
    val displayedPanel = panel ?: previousPanel
    val visible = panel != null

    BackHandler(enabled = visible, onBack = onDismiss)

    ReaderSheetTheme(accent = accent) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                )
            }

            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(tween(200)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(
                        if (displayedPanel == ReaderPanel.Tools) {
                            Modifier
                        } else {
                            Modifier.fillMaxHeight(0.72f)
                        },
                    ),
            ) {
                displayedPanel?.let { shownPanel ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        tonalElevation = 6.dp,
                        shadowElevation = 12.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (shownPanel == ReaderPanel.Tools) {
                                    Modifier
                                } else {
                                    Modifier.fillMaxSize()
                                },
                            )
                            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                            .semantics { paneTitle = shownPanel.title() },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .then(
                                    if (shownPanel == ReaderPanel.Tools) {
                                        Modifier
                                    } else {
                                        Modifier.fillMaxSize()
                                    },
                                ),
                        ) {
                            content(shownPanel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReaderPanelHeader(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
