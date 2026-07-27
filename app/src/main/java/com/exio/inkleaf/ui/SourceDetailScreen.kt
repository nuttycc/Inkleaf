package com.exio.inkleaf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.materialsymbols.outlined.R as MaterialSymbolsOutlinedR
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginActionDescriptor
import com.exio.inkleaf.plugin.PluginHealth
import com.exio.inkleaf.plugin.PluginSettingDescriptor

/**
 * Shows one comic source's settings, actions, status, and uninstall controls.
 *
 * Plugins declare settings through describe(); the host only maps descriptors to controls and
 * remains unaware of site-specific behavior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceDetailScreen(
    pluginId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SourceDetailViewModel = viewModel(),
) {
    val plugin by viewModel.plugin.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val actions by viewModel.actions.collectAsStateWithLifecycle()
    val values by viewModel.values.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val describeError by viewModel.describeError.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showUninstallConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(pluginId) { viewModel.load(pluginId) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // Rebuild the isolate once on exit so several edits take effect atomically.
    DisposableEffect(Unit) { onDispose { viewModel.flushSettingsChange() } }

    val displayName = plugin?.manifest?.name ?: pluginId

    // Group by first appearance rather than sorting: the plugin's declaration order is the only
    // ordering signal we have, and reordering it would scramble the author's intent.
    val settingSections =
        remember(settings) {
            val ordered = LinkedHashMap<String?, MutableList<PluginSettingDescriptor>>()
            settings.forEach { descriptor ->
                val section = descriptor.section?.takeIf { it.isNotBlank() }
                ordered.getOrPut(section) { mutableListOf() }.add(descriptor)
            }
            ordered.map { (section, group) -> section to group.toList() }
        }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            if (busy) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp)) }
            }

            item {
                SourceIdentityHeader(
                    plugin = plugin,
                    pluginId = pluginId,
                    busy = busy,
                    onToggle = { enabled -> viewModel.setEnabled(enabled) },
                    onRecover = { viewModel.recover() },
                )
            }

            if (settings.isNotEmpty()) {
                settingSections.forEach { (section, group) ->
                    item(key = "setting_section_${section.orEmpty()}") {
                        SectionHeader(section ?: "设置")
                    }
                    items(group.size, key = { "setting_${group[it].id}" }) { index ->
                        val descriptor = group[index]
                        SettingControl(
                            descriptor = descriptor,
                            value = values[descriptor.id] ?: descriptor.defaultValue.orEmpty(),
                            enabled = !busy,
                            onValueChange = { viewModel.setValue(descriptor.id, it) },
                        )
                    }
                }
            }

            describeError?.let { error ->
                item { SectionHeader("设置") }
                item {
                    Text(
                        text = "无法读取该源的设置项：$error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            if (actions.isNotEmpty()) {
                item { SectionHeader("操作") }
                items(actions.size, key = { "action_${actions[it].id}" }) { index ->
                    val action = actions[index]
                    ActionRow(
                        action = action,
                        enabled = !busy && action.enabled,
                        onClick = { viewModel.invokeAction(action) },
                    )
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    OutlinedButton(
                        onClick = { showUninstallConfirm = true },
                        enabled = !busy,
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                    ) {
                        Text("卸载此漫画源")
                    }
                }
            }
        }
    }

    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text("卸载漫画源") },
            text = { Text("卸载将移除插件文件、插件配置、Cookie 和日志。阅读历史、书签及已保存记录会保留，但该来源将暂时不可用。") },
            confirmButton = {
                Button(
                    onClick = {
                        showUninstallConfirm = false
                        viewModel.uninstall(onDone = onBack)
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                ) {
                    Text("确认卸载")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SourceIdentityHeader(
    plugin: InstalledPlugin?,
    pluginId: String,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRecover: () -> Unit,
) {
    val state = plugin?.state
    Column {
        ListItem(
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "id: $pluginId · v${state?.activeVersion ?: "未启用"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    plugin?.manifest?.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state?.let { HealthStatusBadge(pluginState = it) }
                }
            },
            leadingContent = {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter =
                                painterResource(
                                    MaterialSymbolsOutlinedR.drawable
                                        .materialsymbols_ic_extension_outlined
                                ),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            },
            trailingContent = {
                Switch(
                    checked = state?.disabled == false && state.activeVersion != null,
                    onCheckedChange = onToggle,
                    enabled = !busy && state?.activeVersion != null,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        ) {
            Text(plugin?.manifest?.name ?: pluginId)
        }

        if (state?.health == PluginHealth.RUNTIME_UNHEALTHY) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(onClick = onRecover, enabled = !busy) { Text("恢复运行") }
            }
        }
    }
}

/** Maps plugin-declared descriptors to controls without interpreting site-specific semantics. */
@Composable
private fun SettingControl(
    descriptor: PluginSettingDescriptor,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    when (descriptor.type) {
        "boolean" ->
            ListItem(
                trailingContent = {
                    Switch(
                        checked = value.toBooleanStrictOrNull() == true,
                        onCheckedChange = { onValueChange(it.toString()) },
                        enabled = enabled,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            ) {
                Text(descriptor.title)
            }

        "select" -> {
            var expanded by remember { mutableStateOf(false) }
            val selectedTitle =
                descriptor.options.firstOrNull { it.id == value }?.title
                    ?: descriptor.options.firstOrNull { it.id == descriptor.defaultValue }?.title
                    ?: descriptor.options.firstOrNull()?.title
                    ?: "未选择"
            Box {
                ListItem(
                    supportingContent = { Text(selectedTitle) },
                    modifier =
                        Modifier.then(
                            if (enabled) Modifier.clickable { expanded = true } else Modifier
                        ),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) {
                    Text(descriptor.title)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    descriptor.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.title) },
                            onClick = {
                                expanded = false
                                onValueChange(option.id)
                            },
                        )
                    }
                }
            }
        }

        // Text and secret settings share an input; secret values are masked.
        else -> {
            val masked = descriptor.type == "secret" || descriptor.secret
            // Persist on focus loss to avoid a DataStore write for every keystroke.
            var draft by remember(descriptor.id, value) { mutableStateOf(value) }
            var revealed by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(descriptor.title) },
                singleLine = true,
                enabled = enabled,
                isError = descriptor.required && draft.isBlank(),
                visualTransformation =
                    if (masked && !revealed) PasswordVisualTransformation()
                    else VisualTransformation.None,
                trailingIcon =
                    if (masked) {
                        {
                            IconButton(onClick = { revealed = !revealed }) {
                                Icon(
                                    painter =
                                        painterResource(
                                            if (revealed) {
                                                MaterialSymbolsOutlinedR.drawable
                                                    .materialsymbols_ic_visibility_off_outlined
                                            } else {
                                                MaterialSymbolsOutlinedR.drawable
                                                    .materialsymbols_ic_visibility_outlined
                                            }
                                        ),
                                    contentDescription = if (revealed) "隐藏" else "显示",
                                )
                            }
                        }
                    } else null,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .onFocusChanged { focus ->
                            if (!focus.isFocused && draft != value) onValueChange(draft)
                        },
            )
        }
    }
}

@Composable
private fun ActionRow(
    action: PluginActionDescriptor,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier =
            Modifier.then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(
            text = action.title,
            color =
                if (action.destructive) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
        )
    }
}
