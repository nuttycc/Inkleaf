package com.exio.inkleaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.data.ReaderPageDirection
import com.exio.inkleaf.data.ReaderPageStatusColor
import com.exio.inkleaf.data.ReaderPageStatusPosition
import com.exio.inkleaf.data.ReaderSettings
import com.exio.inkleaf.data.ReaderStageBackground

internal fun readerStageBackgroundColor(value: ReaderStageBackground): Color =
    when (value) {
        ReaderStageBackground.BLACK -> Color.Black
        ReaderStageBackground.DARK_GRAY -> Color(0xFF1F1F1F)
        ReaderStageBackground.BEIGE -> Color(0xFFF5EBDD)
    }

internal fun readerStageContentColor(value: ReaderStageBackground): Color =
    if (value == ReaderStageBackground.BEIGE) Color.Black else Color.White

internal fun readerPageStatusContentColor(settings: ReaderSettings): Color =
    when (readerPageStatusTone(settings)) {
        ReaderPageStatusTone.LIGHT_CONTENT -> Color.White
        ReaderPageStatusTone.DARK_CONTENT -> Color.Black
    }

internal fun readerPageStatusContainerColor(settings: ReaderSettings): Color =
    when (readerPageStatusTone(settings)) {
        ReaderPageStatusTone.LIGHT_CONTENT -> Color.Black.copy(alpha = 0.78f)
        ReaderPageStatusTone.DARK_CONTENT -> Color.White.copy(alpha = 0.88f)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSettingsPanelContent(
    settings: ReaderSettings,
    onPageDirectionChanged: (ReaderPageDirection) -> Unit,
    onStageBackgroundChanged: (ReaderStageBackground) -> Unit,
    onPageStatusPositionChanged: (ReaderPageStatusPosition) -> Unit,
    onPageStatusColorChanged: (ReaderPageStatusColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
    ) {
        ReaderAttachedPanelHeader(title = ReaderPanel.Settings.title())
        ReaderAttachedPanelDivider()

        ReaderSettingsSectionLabel("翻页方向")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            ReaderPageDirection.entries.forEachIndexed { index, direction ->
                SegmentedButton(
                    selected = settings.pageDirection == direction,
                    onClick = { onPageDirectionChanged(direction) },
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index,
                            ReaderPageDirection.entries.size,
                        ),
                ) {
                    Text(
                        when (direction) {
                            ReaderPageDirection.LEFT_TO_RIGHT -> "左 → 右"
                            ReaderPageDirection.RIGHT_TO_LEFT -> "右 → 左"
                        }
                    )
                }
            }
        }

        ReaderSettingsSectionLabel("Stage 背景")
        ReaderStageBackground.entries.forEach { background ->
            InkleafChoiceListItem(
                headline =
                    when (background) {
                        ReaderStageBackground.BLACK -> "黑色"
                        ReaderStageBackground.DARK_GRAY -> "深灰"
                        ReaderStageBackground.BEIGE -> "米白"
                    },
                selected = settings.stageBackground == background,
                onClick = { onStageBackgroundChanged(background) },
                trailingContent = {
                    ReaderColorSwatch(color = readerStageBackgroundColor(background))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ReaderSettingsSectionLabel("页码位置")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            ReaderPageStatusPosition.entries.forEachIndexed { index, position ->
                SegmentedButton(
                    selected = settings.pageStatusPosition == position,
                    onClick = { onPageStatusPositionChanged(position) },
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index,
                            ReaderPageStatusPosition.entries.size,
                        ),
                ) {
                    Text(
                        when (position) {
                            ReaderPageStatusPosition.START -> "左"
                            ReaderPageStatusPosition.CENTER -> "居中"
                            ReaderPageStatusPosition.END -> "右"
                        }
                    )
                }
            }
        }

        ReaderSettingsSectionLabel("页码字体颜色")
        ReaderPageStatusColor.entries.forEach { color ->
            InkleafChoiceListItem(
                headline =
                    when (color) {
                        ReaderPageStatusColor.AUTO -> "自动"
                        ReaderPageStatusColor.WHITE -> "白色"
                        ReaderPageStatusColor.BLACK -> "黑色"
                    },
                selected = settings.pageStatusColor == color,
                onClick = { onPageStatusColorChanged(color) },
                supportingContent =
                    if (color == ReaderPageStatusColor.AUTO) {
                        { Text("根据 Stage 背景自动保持对比度") }
                    } else {
                        null
                    },
                trailingContent = {
                    when (color) {
                        ReaderPageStatusColor.AUTO -> ReaderAutoColorSwatch()
                        ReaderPageStatusColor.WHITE -> ReaderColorSwatch(Color.White)
                        ReaderPageStatusColor.BLACK -> ReaderColorSwatch(Color.Black)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReaderSettingsSectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun ReaderColorSwatch(color: Color) {
    Box(
        modifier =
            Modifier.size(32.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    )
}

@Composable
private fun ReaderAutoColorSwatch() {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    ) {
        Text(
            text = "A",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
