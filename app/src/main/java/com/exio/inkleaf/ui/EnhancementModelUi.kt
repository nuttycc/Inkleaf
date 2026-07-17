package com.exio.inkleaf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.R
import com.exio.inkleaf.data.enhancement.EnhancementModelDescriptor
import com.exio.inkleaf.data.enhancement.EnhancementModelInstallState
import com.exio.inkleaf.data.enhancement.ModelOperation

internal const val MODEL_RUNTIME_MESSAGE =
    "AI 增强在设备上完成。"

internal fun formatFileSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024f * 1024f * 1024f))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}.replace(".0 ", " ")

internal fun EnhancementModelDescriptor.summaryLine(): String =
    "${formatFileSize(downloadSize)} · ${speed.label}"

@Composable
internal fun ModelRuntimeNotice(modifier: Modifier = Modifier) {
    Text(
        text = MODEL_RUNTIME_MESSAGE,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
internal fun ModelSummaryContent(
    model: EnhancementModelDescriptor,
    state: EnhancementModelInstallState,
    showDownloadStatus: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(model.summaryLine())
        Text(
            text = "适合${model.recommendedUse}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showDownloadStatus) {
            ModelDownloadStatus(model, state)
        }
    }
}

@Composable
internal fun ModelPackageAction(
    model: EnhancementModelDescriptor,
    state: EnhancementModelInstallState,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRetryDownload: () -> Unit,
    onRetryDelete: () -> Unit,
    installedAction: @Composable () -> Unit,
) {
    when (state) {
        EnhancementModelInstallState.Checking -> CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
        )

        EnhancementModelInstallState.NotInstalled -> if (model.isBundled) {
            Text(
                text = "不可用",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
            )
        } else {
            FilledTonalButton(onClick = onInstall) {
                Text("下载")
            }
        }

        is EnhancementModelInstallState.Downloading -> IconButton(onClick = onCancel) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = "取消下载 ${model.displayName}",
            )
        }

        is EnhancementModelInstallState.Installed -> if (model.isBundled) {
            Text(
                text = "内置",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        } else {
            installedAction()
        }

        is EnhancementModelInstallState.Failed -> if (model.isBundled) {
            Text(
                text = "不可用",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
            )
        } else {
            TextButton(
                onClick = if (state.operation == ModelOperation.DOWNLOAD) {
                    onRetryDownload
                } else {
                    onRetryDelete
                },
            ) {
                Text(if (state.operation == ModelOperation.DOWNLOAD) "重试下载" else "重试删除")
            }
        }
    }
}

@Composable
internal fun ModelDownloadStatus(
    model: EnhancementModelDescriptor,
    state: EnhancementModelInstallState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        EnhancementModelInstallState.Checking -> Text(
            text = "正在检查模型文件…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )

        EnhancementModelInstallState.NotInstalled -> Unit

        is EnhancementModelInstallState.Downloading -> Column(modifier = modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "已下载 ${formatFileSize(state.downloadedBytes)} / " +
                        formatFileSize(state.totalBytes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        is EnhancementModelInstallState.Installed -> Text(
            text = if (model.isBundled) {
                "应用内置"
            } else {
                "已下载"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )

        is EnhancementModelInstallState.Failed -> Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}
