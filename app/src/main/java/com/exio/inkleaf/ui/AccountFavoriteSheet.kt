package com.exio.inkleaf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.outlined.R as MaterialSymbolsOutlinedR

/**
 * Account favorites for one online comic: choose a folder and add, or remove the existing entry.
 * The folder list comes from the source at open time, because it belongs to the account rather
 * than to the comic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountFavoriteSheet(
    state: OnlineFavoriteSheetState,
    sourceName: String,
    comicTitle: String?,
    onSelectOption: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberExpandOnlySheetState(),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        SheetColumn(scrollable = true, selectable = state.needsOptions) {
            StandardSheetTitle("账户收藏")
            Text(
                text =
                    listOfNotNull(
                            sourceName.takeIf { it.isNotBlank() },
                            comicTitle?.takeIf { it.isNotBlank() },
                        )
                        .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            val error = state.errorMessage
            val optionsUnavailable =
                state.needsOptions && state.options.isEmpty() && !state.isLoading
            when {
                state.authRequired ->
                    AccountFavoriteSignIn(message = error, onSignIn = onSignIn)
                state.isLoading -> AccountFavoriteLoading()
                state.favorited -> AccountFavoriteApplied(state = state, onRemove = onRemove)
                optionsUnavailable && error != null ->
                    AccountFavoriteOptionsError(
                        message = error,
                        canRetry = state.canRetryOptions,
                        onRetry = onRetry,
                    )
                else ->
                    AccountFavoriteContent(
                        state = state,
                        onSelectOption = onSelectOption,
                        onAdd = onAdd,
                    )
            }
        }
    }
}

@Composable
private fun AccountFavoriteContent(
    state: OnlineFavoriteSheetState,
    onSelectOption: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (state.needsOptions) {
            SheetSectionLabel(state.addAction?.optionTitle ?: "收藏夹")
            state.options.forEach { option ->
                InkleafChoiceListItem(
                    headline = option.title,
                    selected = option.id == state.selectedOptionId,
                    onClick = { onSelectOption(option.id) },
                    enabled = !state.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val selectedTitle = state.options.firstOrNull { it.id == state.selectedOptionId }?.title
            if (selectedTitle != null) {
                Text(
                    text = "将保存到「$selectedTitle」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        state.addAction?.let {
            Button(
                onClick = onAdd,
                enabled =
                    !state.isRunning &&
                        (!state.needsOptions || state.selectedOptionId != null) &&
                        (!state.needsOptions || state.options.isNotEmpty()),
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .height(48.dp),
            ) {
                if (state.runningAction == FavoriteSheetAction.ADD) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在加入…")
                } else {
                    Text("加入收藏")
                }
            }
        }
    }
}

/** The comic is already on the account, so only its removal is offered. */
@Composable
private fun AccountFavoriteApplied(
    state: OnlineFavoriteSheetState,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter =
                    painterResource(
                        MaterialSymbolsOutlinedR.drawable
                            .materialsymbols_ic_bookmark_heart_outlined
                    ),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("已加入账户收藏", style = MaterialTheme.typography.bodyMedium)
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = onRemove,
                enabled = !state.isRunning,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                if (state.runningAction == FavoriteSheetAction.REMOVE) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在移出…")
                } else {
                    Icon(
                        painter =
                            painterResource(
                                MaterialSymbolsOutlinedR.drawable
                                    .materialsymbols_ic_bookmark_remove_outlined
                            ),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(state.removeAction?.title ?: "移出账户收藏")
                }
            }
        }
    }
}

@Composable
private fun AccountFavoriteSignIn(message: String?, onSignIn: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter =
                painterResource(
                    MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_login_outlined
                ),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = "登录后使用账户收藏",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message?.takeIf { it.isNotBlank() } ?: "登录后即可选择收藏夹保存漫画。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(
            onClick = onSignIn,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("前往登录")
        }
    }
}

@Composable
private fun AccountFavoriteLoading() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = "正在读取收藏夹…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccountFavoriteOptionsError(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (canRetry) {
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}
