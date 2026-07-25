package com.exio.inkleaf.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** 一次性消息 → Snackbar：展示完立即消费，防止旋转屏幕后重复弹出。 各屏的"ViewModel 消息 + 展示后清空"契约收在这一处。 */
@Composable
internal fun SnackbarMessageEffect(
    message: String?,
    hostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(message) {
        message?.let {
            hostState.showSnackbar(it)
            onConsumed()
        }
    }
}

/** 全 App 删除/移除类确认对话框的共用结构：标题 + 说明 + 确认/取消 */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
