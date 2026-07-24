package com.exio.inkleaf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exio.inkleaf.data.ocr.OcrModelVariant
import com.exio.inkleaf.data.ocr.totalBytes

@Composable
fun OcrModelDownloadScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OcrModelDownloadViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected by viewModel.selectedVariant.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    var showCancelDialog by remember { mutableStateOf(false) }
    var variantToDelete by remember { mutableStateOf<OcrModelVariant?>(null) }
    val downloading = state is OcrDownloadUiState.Downloading

    BackHandler(enabled = downloading) { showCancelDialog = true }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("OCR 模型管理") },
                navigationIcon = {
                    IconButton(onClick = { if (downloading) showCancelDialog = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(Modifier.padding(bottom = 4.dp)) {
                    Text("漫画离线文字识别模型", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "提供 PP-OCRv6 小型与轻量两套模型，下载后即可在阅读器内进行离线识图。可随时启用或删除模型以释放空间。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(options, key = { it.variant.id }) { option ->
                ModelManagementCard(
                    option = option,
                    isSelectedVariant = selected == option.variant,
                    state = state,
                    viewModel = viewModel,
                    onDeleteClick = { variantToDelete = option.variant },
                    onCancelDownloadClick = { showCancelDialog = true },
                )
            }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("取消下载？") },
            text = { Text("已完成的文件会保留，下次可以继续下载。") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    viewModel.cancelDownload()
                    onBack()
                }) { Text("取消下载") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("继续下载") }
            },
        )
    }

    variantToDelete?.let { variant ->
        AlertDialog(
            onDismissRequest = { variantToDelete = null },
            title = { Text("删除 ${variant.displayName}？") },
            text = {
                Text(
                    if (variant == selected) {
                        "该模型当前正在使用中。删除后将清空本地模型文件并暂停 OCR 识别功能，后续如需使用需重新下载。"
                    } else {
                        "删除后将释放 ${formatModelBytes(variant.totalBytes)} 存储空间。需要使用时可随时重新下载。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteVariant(variant)
                        variantToDelete = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { variantToDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ModelManagementCard(
    option: OcrModelOption,
    isSelectedVariant: Boolean,
    state: OcrDownloadUiState?,
    viewModel: OcrModelDownloadViewModel,
    onDeleteClick: () -> Unit,
    onCancelDownloadClick: () -> Unit,
) {
    val isTargetingThis = isSelectedVariant && state != null

    val containerColor = if (option.active) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    } else if (option.installed) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }

    val border = if (option.active) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
        border = border,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = option.variant.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusBadge(option = option)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = option.variant.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${option.variant.languageSummary} · ${formatModelBytes(option.variant.totalBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(14.dp))

            // Inline Action Section inside the card
            if (isTargetingThis) {
                when (val current = state) {
                    is OcrDownloadUiState.SelectingSource -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("正在选择最快下载源…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is OcrDownloadUiState.ReadyToDownload -> {
                        Button(
                            onClick = viewModel::startDownload,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("下载模型 (${formatModelBytes(current.totalBytes)})")
                        }
                    }
                    is OcrDownloadUiState.Downloading -> {
                        val fraction = (current.downloadedBytes.toFloat() / current.totalBytes).coerceIn(0f, 1f)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "来源：${current.sourceName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${formatModelBytes(current.downloadedBytes)} / ${formatModelBytes(current.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = onCancelDownloadClick) { Text("取消") }
                            }
                        }
                    }
                    is OcrDownloadUiState.Error -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(current.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::retryDownload) { Text("重试") }
                        }
                    }
                    is OcrDownloadUiState.NoSourceAvailable -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("下载源不可用，请检查网络", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.selectSource(autoStart = true) }) { Text("重试") }
                        }
                    }
                    else -> Unit
                }
            } else {
                // Static / Idle actions per card
                when {
                    option.active -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilledTonalButton(
                                onClick = {},
                                enabled = false,
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("正在使用")
                            }
                            OutlinedButton(
                                onClick = onDeleteClick,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    option.installed -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = { viewModel.activateVariant(option.variant) },
                            ) {
                                Text("切换使用")
                            }
                            OutlinedButton(
                                onClick = onDeleteClick,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    else -> {
                        Button(
                            onClick = { viewModel.downloadVariant(option.variant) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("下载模型 (${formatModelBytes(option.variant.totalBytes)})")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(option: OcrModelOption) {
    when {
        option.active -> {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
            ) {
                Text(
                    text = "使用中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        option.installed -> {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = CircleShape,
            ) {
                Text(
                    text = "已下载",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        else -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
            ) {
                Text(
                    text = "未下载",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun formatModelBytes(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))
