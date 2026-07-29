package com.exio.inkleaf.ui

import com.exio.inkleaf.diagnostics.DiagnosticEvent
import com.exio.inkleaf.diagnostics.DiagnosticSeverity
import com.exio.inkleaf.diagnostics.DiagnosticEventType

internal fun diagnosticEventsForFilter(
    events: List<DiagnosticEvent>,
    typeFilter: DiagnosticEventType?,
    severityFilter: DiagnosticSeverity?,
): List<DiagnosticEvent> =
    events.filter { event ->
        (typeFilter == null || event.type == typeFilter) &&
            (severityFilter == null || event.severity == severityFilter)
    }

internal fun DiagnosticEvent.displayTitle(): String = "${type.diagnosticLabel()} · $title"

internal fun DiagnosticEvent.copyText(): String =
    buildString {
        appendLine(displayTitle())
        appendLine("级别: ${severity.diagnosticLabel()}")
        appendLine(timestamp)
        appendLine("会话: $sessionId")
        message?.let { appendLine(it) }
        if (metadata.isNotEmpty()) {
            appendLine()
            metadata.toSortedMap().forEach { (key, value) -> appendLine("$key: $value") }
        }
        stackTrace?.let {
            appendLine()
            append(it)
        }
    }.trimEnd()

internal fun DiagnosticEventType.diagnosticLabel(): String =
    when (this) {
        DiagnosticEventType.ERROR -> "错误"
        DiagnosticEventType.CRASH -> "崩溃"
        DiagnosticEventType.EXIT -> "进程退出"
        DiagnosticEventType.BREADCRUMB -> "操作轨迹"
        DiagnosticEventType.NETWORK -> "网络"
        DiagnosticEventType.PLUGIN -> "插件"
        DiagnosticEventType.STRICT_MODE -> "严格模式"
    }

internal fun DiagnosticSeverity.diagnosticLabel(): String =
    when (this) {
        DiagnosticSeverity.INFO -> "信息"
        DiagnosticSeverity.WARNING -> "警告"
        DiagnosticSeverity.ERROR -> "错误"
        DiagnosticSeverity.FATAL -> "致命"
    }

internal fun diagnosticExportFileName(epochMillis: Long): String = "inkleaf-diagnostics-$epochMillis.zip"
