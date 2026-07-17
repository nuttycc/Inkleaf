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
    onOpenCache: () -> Unit,
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

                item {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenCache()
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text("缓存增强页面")
                    }
                }

                item {
                    EnhancementOptionRow(
                        title = "原图",
                        selected = selectedId == EnhancementSelectionIds.ORIGINAL,
                        onSelect = { onSelect(EnhancementSelectionIds.ORIGINAL) },
                        supportingContent = {
                            Text("不使用 AI 增强")
                        },
                    )
                }

                if (installedModels.isEmpty()) {
                    item {
                        Text(
                            text = "没有已安装的 AI 模型，可前往管理模型下载。",
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
private fun EnhancementOptionRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    supportingContent: @Composable () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = supportingContent,
        leadingContent = { ReaderRadioButton(selected) },
        modifier = Modifier.selectable(
            selected = selected,
            onClick = onSelect,
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
    EnhancementOptionRow(
        title = model.displayName,
        selected = selected,
        onSelect = onSelect,
        supportingContent = {
            ModelSummaryContent(
                model = model,
                state = state,
                showDownloadStatus = false,
            )
        },
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
