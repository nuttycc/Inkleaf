package com.exio.inkleaf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exio.inkleaf.data.ocr.OcrModelVariant
import com.exio.inkleaf.data.ocr.totalBytes

@Composable
fun OcrModelDownloadScreen(
    onBack: () -> Unit,
    onDownloadComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OcrModelDownloadViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected by viewModel.selectedVariant.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    var showCancelDialog by remember { mutableStateOf(false) }
    val downloading = state is OcrDownloadUiState.Downloading

    BackHandler(enabled = downloading) { showCancelDialog = true }
    LaunchedEffect(state) { if (state is OcrDownloadUiState.Completed) onDownloadComplete() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("OCR 模型") },
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("选择用于漫画文字识别的模型", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("两套模型可以同时保留，但只有当前模型会用于识别。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(options, key = { it.variant.id }) { option ->
                ModelOptionRow(option, selected == option.variant, !downloading) { viewModel.selectVariant(option.variant) }
            }
            item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(selected.description, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(selected.languageSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    when (val current = state) {
                        null -> ModelActionButton(options.firstOrNull { it.variant == selected }, viewModel)
                        is OcrDownloadUiState.SelectingSource -> {
                            Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.size(12.dp)); Text("正在选择最快下载源…") }
                        }
                        is OcrDownloadUiState.ReadyToDownload -> Button(onClick = viewModel::startDownload, modifier = Modifier.fillMaxWidth()) { Text("下载并使用 · ${formatModelBytes(current.totalBytes)}") }
                        is OcrDownloadUiState.Downloading -> {
                            val fraction = (current.downloadedBytes.toFloat() / current.totalBytes).coerceIn(0f, 1f)
                            LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text("${formatModelBytes(current.downloadedBytes)} / ${formatModelBytes(current.totalBytes)}", style = MaterialTheme.typography.bodyMedium)
                            Text(current.currentFileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { showCancelDialog = true }) { Text("取消下载") }
                        }
                        is OcrDownloadUiState.Error -> {
                            Text(current.message, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = viewModel::retryDownload) { Text("重试") }
                        }
                        is OcrDownloadUiState.NoSourceAvailable -> {
                            Text("所有下载源均不可用，请检查网络连接后重试。", color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = viewModel::selectSource) { Text("重试") }
                        }
                        OcrDownloadUiState.Completed -> Unit
                    }
                }
            }
        }
    }

    if (showCancelDialog) AlertDialog(
        onDismissRequest = { showCancelDialog = false },
        title = { Text("取消下载？") },
        text = { Text("已完成的文件会保留，下次可以继续下载。") },
        confirmButton = { TextButton(onClick = { showCancelDialog = false; viewModel.cancelDownload(); onBack() }) { Text("取消下载") } },
        dismissButton = { TextButton(onClick = { showCancelDialog = false }) { Text("继续下载") } },
    )
}

@Composable
private fun ModelOptionRow(option: OcrModelOption, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(option.variant.displayName, style = MaterialTheme.typography.titleMedium)
            Text(option.variant.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${formatModelBytes(option.variant.totalBytes)} · ${if (option.installed) "已安装" else "未安装"}${if (option.active) " · 当前使用" else ""}", style = MaterialTheme.typography.labelMedium, color = if (option.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (option.active) Icon(Icons.Filled.CheckCircle, contentDescription = "当前使用", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ModelActionButton(option: OcrModelOption?, viewModel: OcrModelDownloadViewModel) {
    if (option == null || option.active && option.installed) return
    Button(onClick = { if (option.installed) viewModel.activateSelected() else viewModel.selectSource() }, modifier = Modifier.fillMaxWidth()) {
        Text(if (option.installed) "设为当前使用" else "下载并使用")
    }
}

private fun formatModelBytes(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))
