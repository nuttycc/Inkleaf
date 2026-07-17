package com.exio.inkleaf.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.data.enhancement.EnhancementModelCatalog
import com.exio.inkleaf.data.enhancement.EnhancementModelDescriptor
import com.exio.inkleaf.data.enhancement.EnhancementModelInstallState
import com.exio.inkleaf.data.enhancement.EnhancementSelectionIds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderEnhancementSheet(
    selectedId: String,
    modelStates: Map<String, EnhancementModelInstallState>,
    accent: Color,
    onDismiss: () -> Unit,
    onOpenManager: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    val installedModels = remember(modelStates) {
        EnhancementModelCatalog.models.filter { model ->
            modelStates[model.id] is EnhancementModelInstallState.Installed
        }
    }
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

    MaterialTheme(colorScheme = readerScheme, typography = typography, shapes = shapes) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberExpandOnlySheetState(),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .selectableGroup(),
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "图像增强",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                onDismiss()
                                onOpenManager()
                            },
                        ) {
                            Text("管理模型")
                        }
                    }
                }

                item { ReaderSheetSectionLabel("显示方式") }
                item {
                    BuiltInEnhancementRow(
                        title = "原图",
                        description = "不进行显示处理",
                        selected = selectedId == EnhancementSelectionIds.ORIGINAL,
                        onClick = { onSelect(EnhancementSelectionIds.ORIGINAL) },
                    )
                }

                item {
                    ModelRuntimeNotice(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
                item { ReaderSheetSectionLabel("AI 模型") }
                if (installedModels.isEmpty()) {
                    item {
                        Text(
                            text = "暂无已安装模型，可前往管理模型页面下载。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    items(installedModels, key = EnhancementModelDescriptor::id) { model ->
                        ReaderModelRow(
                            model = model,
                            state = modelStates.getValue(model.id),
                            selected = selectedId == model.id,
                            onSelect = { onSelect(model.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltInEnhancementRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(description) },
        leadingContent = { ReaderRadioButton(selected) },
        modifier = Modifier.selectable(
            selected = selected,
            onClick = onClick,
            role = Role.RadioButton,
        ),
    )
}

@Composable
private fun ReaderModelRow(
    model: EnhancementModelDescriptor,
    state: EnhancementModelInstallState,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(model.displayName, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            ModelSummaryContent(model, state)
        },
        leadingContent = { ReaderRadioButton(selected) },
        modifier = Modifier.selectable(
            selected = selected,
            onClick = onSelect,
            role = Role.RadioButton,
        ),
    )
}

@Composable
private fun ReaderRadioButton(selected: Boolean) {
    RadioButton(
        selected = selected,
        onClick = null,
        colors = RadioButtonDefaults.colors(
            selectedColor = MaterialTheme.colorScheme.primary,
            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun ReaderSheetSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}
