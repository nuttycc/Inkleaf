package com.exio.inkleaf.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.materialsymbols.outlined.R as MaterialSymbolsOutlinedR
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginActionDescriptor
import com.exio.inkleaf.plugin.PluginActionKind
import com.exio.inkleaf.plugin.PluginHealth
import com.exio.inkleaf.plugin.PluginSessionState
import com.exio.inkleaf.plugin.PluginSettingDescriptor

/**
 * Shows one comic source's settings, actions, status, and lifecycle management controls.
 *
 * Implements Scheme A with protected setting editors and TopAppBar lifecycle menu.
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
    val session by viewModel.session.collectAsStateWithLifecycle()
    val values by viewModel.values.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val describeError by viewModel.describeError.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }
    var showUninstallConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var editingDescriptor by remember { mutableStateOf<PluginSettingDescriptor?>(null) }
    var choosingSettingId by remember { mutableStateOf<String?>(null) }
    var showAccountSheet by remember { mutableStateOf(false) }

    LaunchedEffect(pluginId) { viewModel.load(pluginId) }

    LaunchedEffect(message) {
        message?.let { feedback ->
            val result =
                snackbarHostState.showSnackbar(
                    message = feedback.text,
                    actionLabel = feedback.copyDetails?.let { "复制详情" },
                    duration =
                        if (feedback.copyDetails != null) SnackbarDuration.Long
                        else SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) {
                feedback.copyDetails?.let { details ->
                    context
                        .getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Inkleaf 错误详情", details))
                }
            }
            viewModel.consumeMessage()
        }
    }

    val leaveScreen = { viewModel.saveSettingsAndThen(onBack) }
    BackHandler { leaveScreen() }

    val displayName = plugin?.manifest?.name ?: pluginId

    // Settings and actions merge by their declared section so an action sits with the fields it
    // acts on. Sign-in and sign-out actions move into the account row and its sheet instead.
    val areas = remember(settings, actions) { sourceAreas(settings, actions) }
    val accountArea = remember(areas) { areas.firstOrNull { areaUsesAccountSheet(it) } }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(displayName) },
                navigationIcon = {
                    IconButton(onClick = leaveScreen, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }, enabled = !busy) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("恢复默认设置") },
                                onClick = {
                                    showMenu = false
                                    showResetConfirm = true
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text("卸载此漫画源", color = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    showMenu = false
                                    showUninstallConfirm = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (busy) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp)) }
            }

            item {
                SourceIdentityCard(
                    plugin = plugin,
                    pluginId = pluginId,
                    busy = busy,
                    onToggle = { enabled -> viewModel.setEnabled(enabled) },
                    onRecover = { viewModel.recover() },
                )
            }

            if (areas.isNotEmpty()) {
                areas.forEach { area ->
                    if (area.section != null) {
                        // A named section is a real area: one heading that carries the section's
                        // actions, then the fields the plugin grouped under it.
                        item(key = "area_header_${area.section}") {
                            AreaHeader(
                                area = area,
                                session = session,
                                enabled = !busy,
                                onActionClick = { action -> viewModel.invokeAction(action) },
                            )
                        }
                        if (area.settings.isNotEmpty()) {
                            item(key = "area_card_${area.section}") {
                                SettingGroupCard(
                                    descriptors = cardSettings(area),
                                    values = values,
                                    enabled = !busy,
                                    onValueChange = { id, value -> viewModel.setValue(id, value) },
                                    onEditProtectedSetting = { descriptor ->
                                        editingDescriptor = descriptor
                                    },
                                    onChooseSetting = { descriptor ->
                                        choosingSettingId = descriptor.id
                                    },
                                    header =
                                        if (areaUsesAccountSheet(area)) {
                                            {
                                                AccountSummaryRow(
                                                    session = session,
                                                    enabled = !busy,
                                                    onClick = { showAccountSheet = true },
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                    } else {
                        // Section-less content keeps the pre-section layout: ungrouped settings,
                        // then the generic action list.
                        if (area.settings.isNotEmpty()) {
                            item(key = "setting_header") { SectionHeader("设置") }
                            item(key = "setting_card") {
                                SettingGroupCard(
                                    descriptors = cardSettings(area),
                                    values = values,
                                    enabled = !busy,
                                    onValueChange = { id, value -> viewModel.setValue(id, value) },
                                    onEditProtectedSetting = { descriptor ->
                                        editingDescriptor = descriptor
                                    },
                                    onChooseSetting = { descriptor ->
                                        choosingSettingId = descriptor.id
                                    },
                                    header =
                                        if (areaUsesAccountSheet(area)) {
                                            {
                                                AccountSummaryRow(
                                                    session = session,
                                                    enabled = !busy,
                                                    onClick = { showAccountSheet = true },
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                        val visibleActions = headingActions(area, session.signedIn)
                        if (visibleActions.isNotEmpty()) {
                            item(key = "action_header") { SectionHeader(area.title) }
                            item(key = "action_card") {
                                ActionGroupCard(
                                    actions = visibleActions,
                                    enabled = !busy,
                                    onActionClick = { action -> viewModel.invokeAction(action) },
                                )
                            }
                        }
                    }
                }
            }

            describeError?.let { error ->
                item { SectionHeader("设置") }
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        colors =
                            CardDefaults.outlinedCardColors(
                                containerColor =
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                            ),
                    ) {
                        Text(
                            text = "无法读取该源的设置项：$error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    editingDescriptor?.let { descriptor ->
        val currentValue = values[descriptor.id] ?: descriptor.defaultValue.orEmpty()
        ProtectedSettingEditDialog(
            descriptor = descriptor,
            currentValue = currentValue,
            onDismiss = { editingDescriptor = null },
            onSave = { newValue ->
                viewModel.setValue(descriptor.id, newValue)
                editingDescriptor = null
            },
        )
    }

    // The picker follows the current descriptors rather than a captured copy, so an action that
    // refreshes the list (重新获取域名) updates the open sheet as well.
    val choosingDescriptor = choosingSettingId?.let { id -> settings.firstOrNull { it.id == id } }
    choosingDescriptor?.let { descriptor ->
        val currentValue = values[descriptor.id] ?: descriptor.defaultValue.orEmpty()
        val pickerActions = visiblePickerActions(actions, descriptor.id, session.signedIn)
        ModalBottomSheet(
            onDismissRequest = { choosingSettingId = null },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            SheetColumn(selectable = true, scrollable = true) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        StandardSheetTitle(descriptor.title)
                    }
                    pickerActions.forEach { action ->
                        SourceAreaActionButton(
                            action = action,
                            enabled = !busy && action.enabled,
                            onClick = { viewModel.invokeAction(action) },
                        )
                    }
                }
                descriptor.options.forEach { option ->
                    InkleafChoiceListItem(
                        headline = option.title,
                        selected = option.id == currentValue,
                        onClick = {
                            viewModel.setValue(descriptor.id, option.id)
                            choosingSettingId = null
                        },
                        enabled = !busy,
                    )
                }
            }
        }
    }

    if (showAccountSheet) {
        accountArea?.let { area ->
            AccountSheet(
                area = area,
                session = session,
                values = values,
                busy = busy,
                onSetValue = viewModel::setValue,
                onAction = viewModel::invokeAction,
                onDismiss = { showAccountSheet = false },
            )
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("恢复默认设置") },
            text = { Text("是否清除此漫画源的所有自定义配置并重置为插件默认状态？") },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirm = false
                        viewModel.resetToDefaults()
                    }
                ) {
                    Text("确认恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            },
        )
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
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 8.dp),
    )
}

/**
 * The heading of a section that carries actions. The section name rests on the left and the
 * actions the plugin attached to that section sit on the right. An area that owns an account row
 * carries its session state there instead, together with the sign-in and sign-out actions.
 */
@Composable
private fun AreaHeader(
    area: SourceArea,
    session: PluginSessionState,
    enabled: Boolean,
    onActionClick: (PluginActionDescriptor) -> Unit,
) {
    val status =
        if (!areaUsesAccountSheet(area) && areaShowsSessionStatus(area)) {
            accountStatusText(session)
        } else {
            null
        }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(
                text = area.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            headingActions(area, session.signedIn).forEach { action ->
                SourceAreaActionButton(
                    action = action,
                    enabled = enabled && action.enabled,
                    onClick = { onActionClick(action) },
                )
            }
        }
    }
}

/** Renders an in-area action at the weight its kind earns, from the single primary step down. */
@Composable
private fun SourceAreaActionButton(
    action: PluginActionDescriptor,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    when (sourceActionEmphasis(action)) {
        SourceActionEmphasis.PRIMARY -> {
            Button(onClick = onClick, enabled = enabled) { Text(action.title) }
        }
        SourceActionEmphasis.TONAL -> {
            FilledTonalButton(onClick = onClick, enabled = enabled) { Text(action.title) }
        }
        SourceActionEmphasis.TEXT -> {
            TextButton(
                onClick = onClick,
                enabled = enabled,
                colors =
                    if (action.destructive) {
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
            ) {
                Text(action.title)
            }
        }
    }
}

@Composable
private fun SourceIdentityCard(
    plugin: InstalledPlugin?,
    pluginId: String,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRecover: () -> Unit,
) {
    val state = plugin?.state
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp),
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
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = plugin?.manifest?.name ?: pluginId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "id: $pluginId · v${state?.activeVersion ?: "未启用"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state?.disabled == false && state.activeVersion != null,
                    onCheckedChange = onToggle,
                    enabled = !busy && state?.activeVersion != null,
                )
            }

            plugin
                ?.manifest
                ?.description
                ?.takeIf { it.isNotBlank() }
                ?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state?.let { HealthStatusBadge(pluginState = it) }
                if (state?.health == PluginHealth.RUNTIME_UNHEALTHY) {
                    FilledTonalButton(onClick = onRecover, enabled = !busy) { Text("恢复运行") }
                }
            }
        }
    }
}

@Composable
private fun SettingGroupCard(
    descriptors: List<PluginSettingDescriptor>,
    values: Map<String, String>,
    enabled: Boolean,
    onValueChange: (String, String) -> Unit,
    onEditProtectedSetting: (PluginSettingDescriptor) -> Unit,
    onChooseSetting: (PluginSettingDescriptor) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
    ) {
        Column {
            header?.let {
                it()
                if (descriptors.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
            descriptors.forEachIndexed { index, descriptor ->
                key(descriptor.id) {
                    SettingControl(
                        descriptor = descriptor,
                        value = values[descriptor.id] ?: descriptor.defaultValue.orEmpty(),
                        enabled = enabled,
                        onValueChange = { onValueChange(descriptor.id, it) },
                        onEditProtectedSetting = { onEditProtectedSetting(descriptor) },
                        onChooseSetting = { onChooseSetting(descriptor) },
                    )
                    if (index < descriptors.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionGroupCard(
    actions: List<PluginActionDescriptor>,
    enabled: Boolean,
    onActionClick: (PluginActionDescriptor) -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
    ) {
        Column {
            actions.forEachIndexed { index, action ->
                ActionRow(
                    action = action,
                    enabled = enabled && action.enabled,
                    onClick = { onActionClick(action) },
                )
                if (index < actions.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

/** The account row: the current session state and the entry to the account sheet. */
@Composable
private fun AccountSummaryRow(
    session: PluginSessionState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        supportingContent = {
            Text(
                text = accountStatusText(session),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier =
            Modifier.then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text("登录状态")
    }
}

/**
 * Collects the credentials and offers the sign-in or sign-out action. Both fields are shown in
 * either state, so the stored credentials stay visible and editable after signing in. Edits bind
 * straight to the screen values, which are persisted with the rest of the source settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSheet(
    area: SourceArea,
    session: PluginSessionState,
    values: Map<String, String>,
    busy: Boolean,
    onSetValue: (String, String) -> Unit,
    onAction: (PluginActionDescriptor) -> Unit,
    onDismiss: () -> Unit,
) {
    val actions = visibleAreaActions(area.actions, session.signedIn)
    val loginAction = actions.firstOrNull { it.kind in PluginActionKind.signInKinds }
    val signOutAction = actions.firstOrNull { it.isSignOut() }
    val fields = remember(area.settings) { area.settings.filter { it.isCredentialField() } }
    val canSubmit =
        fields.filter { it.required }.all { descriptor ->
            (values[descriptor.id] ?: descriptor.defaultValue.orEmpty()).isNotBlank()
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberExpandOnlySheetState(),
    ) {
        SheetColumn(scrollable = true) {
            StandardSheetTitle(if (session.signedIn) "账户" else loginAction?.title ?: "登录")
            Text(
                text = accountStatusText(session),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                fields.forEach { descriptor ->
                    CredentialField(
                        descriptor = descriptor,
                        value = values[descriptor.id] ?: descriptor.defaultValue.orEmpty(),
                        enabled = !busy,
                        onValueChange = { onSetValue(descriptor.id, it) },
                    )
                }
            }
            if (session.signedIn) {
                signOutAction?.let { action ->
                    Button(
                        onClick = {
                            onDismiss()
                            onAction(action)
                        },
                        enabled = !busy && action.enabled,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    ) {
                        Text(action.title)
                    }
                }
            } else {
                loginAction?.let { action ->
                    Button(
                        onClick = {
                            onDismiss()
                            onAction(action)
                        },
                        enabled = !busy && action.enabled && canSubmit,
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    ) {
                        Text(action.title)
                    }
                }
            }
        }
    }
}

/** One credential input in the account sheet. Secret values stay masked until revealed. */
@Composable
private fun CredentialField(
    descriptor: PluginSettingDescriptor,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val isSecret = descriptor.type == "secret" || descriptor.secret
    var revealed by remember(descriptor.id) { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(descriptor.title) },
        singleLine = true,
        enabled = enabled,
        isError = descriptor.required && value.isBlank(),
        visualTransformation =
            if (isSecret && !revealed) PasswordVisualTransformation()
            else VisualTransformation.None,
        trailingIcon =
            if (isSecret) {
                {
                    IconButton(onClick = { revealed = !revealed }, enabled = enabled) {
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
            } else {
                null
            },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun accountStatusText(session: PluginSessionState): String =
    if (session.signedIn) {
        session.account?.let { "已登录：$it" } ?: "已登录"
    } else {
        "未登录"
    }

/** Maps plugin-declared descriptors to controls without interpreting site-specific semantics. */
@Composable
private fun SettingControl(
    descriptor: PluginSettingDescriptor,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onEditProtectedSetting: () -> Unit,
    onChooseSetting: () -> Unit,
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
            val selectedTitle = descriptor.options.firstOrNull { it.id == value }?.title ?: "未选择"
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

        // A single-choice setting keeps its options behind a sheet: a source can discover a dozen
        // mirrors, and listing them all inline would dominate the card.
        "radio" -> {
            val selectedTitle =
                descriptor.options.firstOrNull { it.id == value }?.title ?: "未选择"
            ListItem(
                supportingContent = {
                    Text(
                        text = selectedTitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier =
                    Modifier.then(
                        if (enabled) Modifier.clickable(onClick = onChooseSetting) else Modifier
                    ),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            ) {
                Text(descriptor.title)
            }
        }

        // Text & Secret settings use protected read-only preview with modal editor to prevent
        // accidental edits.
        else -> {
            val isSecret = descriptor.type == "secret" || descriptor.secret
            val displayValue =
                if (value.isBlank()) {
                    descriptor.defaultValue?.takeIf { it.isNotBlank() }?.let { "默认：$it" } ?: "未设置"
                } else if (isSecret) {
                    "••••••••"
                } else {
                    value
                }
            ListItem(
                supportingContent = {
                    Text(
                        text = displayValue,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    IconButton(onClick = onEditProtectedSetting, enabled = enabled) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "编辑",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                modifier =
                    Modifier.then(
                        if (enabled) Modifier.clickable(onClick = onEditProtectedSetting)
                        else Modifier
                    ),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            ) {
                Text(descriptor.title)
            }
        }
    }
}

@Composable
private fun ProtectedSettingEditDialog(
    descriptor: PluginSettingDescriptor,
    currentValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val isSecret = descriptor.type == "secret" || descriptor.secret
    var draft by remember(descriptor.id, currentValue) { mutableStateOf(currentValue) }
    var revealed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑：${descriptor.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(descriptor.title) },
                    singleLine = true,
                    isError = descriptor.required && draft.isBlank(),
                    visualTransformation =
                        if (isSecret && !revealed) PasswordVisualTransformation()
                        else VisualTransformation.None,
                    trailingIcon =
                        if (isSecret) {
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
                    modifier = Modifier.fillMaxWidth(),
                )
                descriptor.defaultValue
                    ?.takeIf { it.isNotBlank() }
                    ?.let { defaultValue ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            AssistChip(
                                onClick = { draft = defaultValue },
                                label = { Text("恢复默认值") },
                            )
                        }
                    }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft) },
                enabled = !descriptor.required || draft.isNotBlank(),
            ) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ActionRow(
    action: PluginActionDescriptor,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
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
