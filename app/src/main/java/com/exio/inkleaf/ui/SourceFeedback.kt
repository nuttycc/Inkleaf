package com.exio.inkleaf.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class SourceFeedbackKind {
    INFO,
    ERROR,
}

internal data class SourceSnackbarVisuals(
    override val message: String,
    val kind: SourceFeedbackKind,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
) : SnackbarVisuals

/**
 * Transient feedback shared by the source screens. Results that need reading stay on the screen that
 * raised them; this carries the outcomes a user watches land in a list they are already looking at.
 */
@Stable
internal class SourceFeedback(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    fun show(
        message: String,
        kind: SourceFeedbackKind = SourceFeedbackKind.INFO,
        actionLabel: String? = null,
        onAction: () -> Unit = {},
    ) {
        scope.launch {
            val result =
                hostState.showSnackbar(
                    SourceSnackbarVisuals(
                        message = message,
                        kind = kind,
                        actionLabel = actionLabel,
                        withDismissAction = actionLabel != null,
                    )
                )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

@Composable
internal fun rememberSourceFeedback(): SourceFeedback {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    return remember(hostState, scope) { SourceFeedback(hostState, scope) }
}

@Composable
internal fun SourceSnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(hostState) { data ->
        val isError =
            (data.visuals as? SourceSnackbarVisuals)?.kind == SourceFeedbackKind.ERROR
        if (isError) {
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                actionColor = MaterialTheme.colorScheme.error,
                dismissActionContentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                actionColor = MaterialTheme.colorScheme.inversePrimary,
            )
        }
    }
}
