package com.exio.inkleaf.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exio.inkleaf.diagnostics.DiagnosticEvent
import com.exio.inkleaf.diagnostics.DiagnosticSeverity
import com.exio.inkleaf.diagnostics.DiagnosticEventType
import com.exio.inkleaf.diagnostics.DiagnosticRepository
import kotlinx.coroutines.launch

/** The workbench for reviewing, copying, and exporting local diagnostic evidence. */
@Composable
fun DiagnosticScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { DiagnosticRepository.get(context) }
    val events by repository.events.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var typeFilter by remember { mutableStateOf<DiagnosticEventType?>(null) }
    var severityFilter by remember { mutableStateOf<DiagnosticSeverity?>(null) }
    var selectedEvent by remember { mutableStateOf<DiagnosticEvent?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val outputLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                exporting = true
                runCatching {
                    // exportZip owns and closes this SAF stream.
                    val output = requireNotNull(context.contentResolver.openOutputStream(uri))
                    repository.exportZip(output)
                }.onSuccess {
                    snackbarHostState.showSnackbar("诊断包已导出")
                }.onFailure {
                    snackbarHostState.showSnackbar("导出失败")
                }
                exporting = false
            }
        }

    LaunchedEffect(Unit) { repository.markAllRead() }
    val visibleEvents = diagnosticEventsForFilter(events, typeFilter, severityFilter)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("诊断") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { outputLauncher.launch(diagnosticExportFileName(System.currentTimeMillis())) },
                        enabled = !exporting,
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "导出诊断包")
                    }
                    IconButton(
                        onClick = { showClearConfirmation = true },
                        enabled = events.isNotEmpty() && !exporting,
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空记录")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                DiagnosticFilters(
                    selectedType = typeFilter,
                    availableTypes = events.map { it.type }.distinct(),
                    selectedSeverity = severityFilter,
                    availableSeverities = events.map { it.severity }.distinct(),
                    onTypeSelected = { typeFilter = it },
                    onSeveritySelected = { severityFilter = it },
                )
            }
            if (visibleEvents.isEmpty()) {
                item {
                    Text(
                        text = if (events.isEmpty()) "还没有诊断记录" else "这个分类没有记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            } else {
                items(visibleEvents, key = { it.id }) { event ->
                    DiagnosticEventRow(event = event, onClick = { selectedEvent = event })
                }
            }
        }
    }

    selectedEvent?.let { event ->
        DiagnosticEventDialog(
            event = event,
            onDismiss = { selectedEvent = null },
            onCopy = {
                context.copyDiagnosticText(event.copyText())
                scope.launch { snackbarHostState.showSnackbar("已复制诊断记录") }
            },
        )
    }
    if (showClearConfirmation) {
        ConfirmDialog(
            title = "清空诊断记录？",
            text = "这会移除设备上的诊断事件、应急记录和插件日志。",
            confirmLabel = "清空",
            onConfirm = {
                showClearConfirmation = false
                selectedEvent = null
                scope.launch {
                    repository.clear()
                    snackbarHostState.showSnackbar("已清空诊断记录")
                }
            },
            onDismiss = { showClearConfirmation = false },
        )
    }
}

@Composable
private fun DiagnosticFilters(
    selectedType: DiagnosticEventType?,
    availableTypes: List<DiagnosticEventType>,
    selectedSeverity: DiagnosticSeverity?,
    availableSeverities: List<DiagnosticSeverity>,
    onTypeSelected: (DiagnosticEventType?) -> Unit,
    onSeveritySelected: (DiagnosticSeverity?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            DiagnosticFilterChip(
                label = "全部类型",
                selected = selectedType == null,
                onClick = { onTypeSelected(null) },
            )
            availableTypes.forEach { type ->
                DiagnosticFilterChip(
                    label = type.diagnosticLabel(),
                    selected = selectedType == type,
                    onClick = { onTypeSelected(type) },
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            DiagnosticFilterChip(
                label = "全部级别",
                selected = selectedSeverity == null,
                onClick = { onSeveritySelected(null) },
            )
            availableSeverities.sortedByDescending { it.ordinal }.forEach { severity ->
                DiagnosticFilterChip(
                    label = severity.diagnosticLabel(),
                    selected = selectedSeverity == severity,
                    onClick = { onSeveritySelected(severity) },
                )
            }
        }
    }
}

@Composable
private fun DiagnosticFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) ({ Text("✓") }) else null,
    )
}

@Composable
private fun DiagnosticEventRow(event: DiagnosticEvent, onClick: () -> Unit) {
    InkleafActionListItem(
        headline = event.displayTitle(),
        supporting = listOfNotNull(event.message, event.timestamp.substringBefore('.').replace('T', ' ')).joinToString("\n"),
        leadingContent = { DiagnosticSeverityBadge(event.severity) },
        onClick = onClick,
    )
}

@Composable
private fun DiagnosticSeverityBadge(severity: DiagnosticSeverity) {
    val containerColor =
        when (severity) {
            DiagnosticSeverity.FATAL,
            DiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.errorContainer
            DiagnosticSeverity.WARNING -> WARNING_YELLOW
            DiagnosticSeverity.INFO -> MaterialTheme.colorScheme.surfaceVariant
        }
    val contentColor =
        when (severity) {
            DiagnosticSeverity.FATAL,
            DiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.onErrorContainer
            DiagnosticSeverity.WARNING -> Color.Black
            DiagnosticSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = severity.diagnosticLabel(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DiagnosticEventDialog(
    event: DiagnosticEvent,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.displayTitle()) },
        text = {
            Text(
                text = event.copyText(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onCopy) { Text("复制") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun Context.copyDiagnosticText(value: String) {
    getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Inkleaf diagnostics", value))
}

private val WARNING_YELLOW = Color(0xFFFFC107)
