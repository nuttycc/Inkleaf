package com.exio.inkleaf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.data.db.EnhancementCacheTaskEntity
import com.exio.inkleaf.data.db.EnhancementCacheTaskStatus
import com.exio.inkleaf.data.enhancement.EnhancementModelCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnhancementCacheSheet(
    currentPage: Int,
    pageCount: Int,
    modelName: String?,
    task: EnhancementCacheTaskEntity?,
    onDismiss: () -> Unit,
    onStart: (Int, Int) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    var startText by remember(
        currentPage,
        pageCount
    ) { mutableStateOf((currentPage + 1).toString()) }
    var endText by remember(pageCount) { mutableStateOf(pageCount.toString()) }
    val startPage = startText.toIntOrNull()?.minus(1)
    val endPage = endText.toIntOrNull()?.minus(1)
    val rangeValid = startPage != null && endPage != null &&
            startPage in 0 until pageCount && endPage in startPage until pageCount
    val active = task?.status?.let { it in EnhancementCacheTaskStatus.active } == true

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberExpandOnlySheetState(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text("缓存 AI 增强页面", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "选择页码范围并在后台处理，之后翻页直接显示",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (task != null) {
                EnhancementCacheTaskContent(
                    task = task,
                    onPause = onPause,
                    onResume = onResume,
                    onCancel = onCancel,
                )
            }

            if (!active) {
                Text(
                    text = modelName?.let { "模型：$it" } ?: "请先选择已安装的 AI 模型",
                    color = if (modelName == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { value -> startText = value.filter(Char::isDigit) },
                        label = { Text("起始页") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { value -> endText = value.filter(Char::isDigit) },
                        label = { Text("结束页") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            startText = (currentPage + 1).toString()
                            endText = pageCount.toString()
                        },
                    ) {
                        Text("当前页到末页")
                    }
                    TextButton(
                        onClick = {
                            startText = "1"
                            endText = pageCount.toString()
                        },
                    ) {
                        Text("全书")
                    }
                }
                Button(
                    onClick = {
                        onStart(requireNotNull(startPage), requireNotNull(endPage))
                        onDismiss()
                    },
                    enabled = modelName != null && rangeValid,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("开始缓存")
                }
            }
        }
    }
}

@Composable
internal fun EnhancementCacheReplacementDialog(
    replacement: PendingEnhancementCacheReplacement,
    currentComicId: Long,
    replacementInProgress: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val activeTask = replacement.activeTask
    val request = replacement.request
    val activeModelName = EnhancementModelCatalog
        .find(activeTask.modelId)
        ?.displayName
        ?: activeTask.modelId
    val requestedModelName = EnhancementModelCatalog
        .find(request.modelId)
        ?.displayName
        ?: request.modelId
    val activeScope = if (activeTask.comicId == currentComicId) "本书" else "其他漫画"

    AlertDialog(
        onDismissRequest = {
            if (!replacementInProgress) onDismiss()
        },
        title = { Text("替换正在进行的缓存任务？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (replacement.activeTaskChanged) {
                    Text(
                        text = "缓存任务在确认期间已发生变化。下面显示的是当前任务，请重新确认。",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = "同一时间只能执行一个 AI 增强缓存任务。开始新任务会停止当前任务。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "当前任务（$activeScope）\n" +
                            "$activeModelName · ${activeTask.completedPages} / " +
                            "${activeTask.totalPages} 页 · " +
                            "范围 ${activeTask.startPageInclusive + 1}～" +
                            "${activeTask.endPageInclusive + 1}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "新任务\n$requestedModelName · " +
                            "范围 ${request.startPageInclusive + 1}～" +
                            "${request.endPageInclusive + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !replacementInProgress,
            ) {
                Text(if (replacementInProgress) "正在替换…" else "停止并开始新任务")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !replacementInProgress,
            ) {
                Text("保留当前任务")
            }
        },
    )
}

@Composable
private fun EnhancementCacheTaskContent(
    task: EnhancementCacheTaskEntity,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    val progress = if (task.totalPages <= 0) 0f else {
        task.completedPages.toFloat() / task.totalPages
    }
    Text(taskStatusLabel(task), style = MaterialTheme.typography.titleMedium)
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "${task.completedPages} / ${task.totalPages} 页 · " +
                "范围 ${task.startPageInclusive + 1}～${task.endPageInclusive + 1}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    task.lastError?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            task.status in EnhancementCacheTaskStatus.pausable -> TextButton(
                onClick = { onPause(task.id) }
            ) { Text("暂停") }

            task.status in EnhancementCacheTaskStatus.resumable -> TextButton(
                onClick = { onResume(task.id) }
            ) { Text("继续") }
        }
        if (task.status in EnhancementCacheTaskStatus.active) {
            TextButton(onClick = { onCancel(task.id) }) { Text("取消") }
        }
    }
}

private fun taskStatusLabel(task: EnhancementCacheTaskEntity): String = when (task.status) {
    EnhancementCacheTaskStatus.QUEUED -> "等待开始"
    EnhancementCacheTaskStatus.RUNNING -> "正在缓存"
    EnhancementCacheTaskStatus.WAITING_FOR_READER -> "正在缓存"
    EnhancementCacheTaskStatus.PAUSED -> "已暂停"
    EnhancementCacheTaskStatus.PAUSED_LOW_STORAGE -> "存储空间不足，已暂停"
    EnhancementCacheTaskStatus.COMPLETED -> "缓存完成"
    EnhancementCacheTaskStatus.CANCELLED -> "已取消"
    EnhancementCacheTaskStatus.EXPIRED -> "源文件或模型已变化"
    EnhancementCacheTaskStatus.FAILED -> "缓存失败"
    else -> task.status
}
