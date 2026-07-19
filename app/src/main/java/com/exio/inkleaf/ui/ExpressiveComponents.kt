package com.exio.inkleaf.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Keeps Material 3 alpha list-item adoption behind an app-owned entry point. The new overloads own
 * click semantics and interaction shapes, so callers no longer attach a separate clickable
 * modifier that can drift from the component's visual state.
 */
@Composable
internal fun InkleafActionListItem(
    headline: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    ListItem(
        onClick = onClick,
        modifier = modifier,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        supportingContent = supporting?.let { { Text(it) } },
    ) {
        Text(headline)
    }
}

/** A non-interactive list item communicates information without implying that the whole row taps. */
@Composable
internal fun InkleafInfoListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    ListItem(
        modifier = modifier,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        supportingContent = supporting?.let { { Text(it) } },
    ) {
        Text(headline)
    }
}

/** Shared single-choice row backed by Material 3's selected list-item semantics. */
@Composable
internal fun InkleafChoiceListItem(
    headline: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        supportingContent = supportingContent,
        trailingContent = trailingContent,
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
            )
        },
    ) {
        Text(headline)
    }
}

/** Expressive filled input for human-readable names and labels. */
@Composable
internal fun InkleafNameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        isError = isError,
        enabled = enabled,
        singleLine = singleLine,
        shape = TextFieldDefaults.roundedShape,
        colors = TextFieldDefaults.tonalColors(),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier,
    )
}

/** Outlined cutout input for exact values such as hexadecimal colors and numeric limits. */
@Composable
internal fun InkleafPrecisionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        shape = OutlinedTextFieldDefaults.roundedShape,
        colors = OutlinedTextFieldDefaults.tonalColors(),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier,
    )
}
