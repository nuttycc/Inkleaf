package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.PluginInstallResult
import com.exio.inkleaf.plugin.PluginInstallStatus

/**
 * How one source install names itself. The device list only ever updates a source it already has,
 * while the repository can install one for the first time, so the same outcome reads differently
 * per screen.
 */
internal enum class SourceInstallVerb(val pastText: String, val failureText: String) {
    Install("已安装", "安装失败"),
    Update("已更新", "更新失败"),
}

/** What one install outcome should say, how it should look, and what it can offer to open. */
internal data class SourceInstallMessage(
    val text: String,
    val isError: Boolean,
    val openPluginId: String?,
)

/** The snackbar wording for one install outcome, in the verb of the screen that raised it. */
internal fun sourceInstallMessage(
    outcome: PluginInstallResult,
    verb: SourceInstallVerb,
    fallbackName: String,
): SourceInstallMessage {
    val name = outcome.pluginId ?: fallbackName
    val version = outcome.version?.let { " v$it" } ?: ""
    return when (outcome.status) {
        PluginInstallStatus.INSTALLED,
        PluginInstallStatus.ALREADY_INSTALLED ->
            if (outcome.activatable) {
                SourceInstallMessage(
                    text = "${verb.pastText} $name$version",
                    isError = false,
                    openPluginId = outcome.pluginId,
                )
            } else {
                SourceInstallMessage(
                    text = "已安装 $name$version，但未激活：与当前版本不兼容",
                    isError = true,
                    openPluginId = outcome.pluginId,
                )
            }
        PluginInstallStatus.REJECTED ->
            SourceInstallMessage(
                text =
                    "${verb.failureText}: " +
                        (outcome.errorMessage ?: outcome.errorCode?.name ?: "安装被拒绝"),
                isError = true,
                openPluginId = null,
            )
    }
}

/** The in-page card wording for one install outcome, which never reads as a failure. */
internal fun installCardMessage(outcome: PluginInstallResult, fallbackName: String): String {
    val name = outcome.pluginId ?: fallbackName
    val version = outcome.version?.let { " v$it" } ?: ""
    return if (outcome.activatable) {
        "已安装 $name$version"
    } else {
        "已安装 $name$version，但未激活：与当前版本不兼容"
    }
}

/** Tally of one update sweep. Only a rejection is not counted as updated. */
internal data class UpdateAllSummary(val updated: Int, val failed: Int) {
    val text: String
        get() =
            if (failed == 0) "已更新 $updated 个漫画源"
            else "已更新 $updated 个漫画源，$failed 个失败"

    val isError: Boolean get() = failed != 0
}

internal fun summarizeUpdateAll(outcomes: List<PluginInstallResult>): UpdateAllSummary =
    UpdateAllSummary(
        updated = outcomes.count { it.status != PluginInstallStatus.REJECTED },
        failed = outcomes.count { it.status == PluginInstallStatus.REJECTED },
    )
