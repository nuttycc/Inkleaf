package com.exio.inkleaf.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.plugin.PluginDownloadSource
import com.exio.inkleaf.plugin.PluginInstallResult
import com.exio.inkleaf.plugin.PluginManager
import com.exio.inkleaf.plugin.PluginRepoEntry
import com.exio.inkleaf.plugin.PluginUpdateCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The one gate every source install goes through.
 *
 * It owns the busy gate, the list refresh afterwards, and the wording of each outcome, so the source
 * list, the repository screen and both import screens cannot disagree about what "installing a
 * source" means. Install outcomes are either announced (the list screens, where the user is already
 * looking at the result land) or placed on the screen that raised them (the import screens, which the
 * user is about to leave).
 */
@Stable
internal class SourceInstallCoordinator(
    private val pluginManager: PluginManager,
    private val feedback: SourceFeedback,
    private val scope: CoroutineScope,
    private val refresh: suspend () -> Unit,
    private val onOpenSource: (String) -> Unit,
) {
    private var _busy by mutableStateOf(false)

    /** True while a gated operation is in flight. */
    var busy: Boolean
        get() = _busy
        private set(value) {
            _busy = value
        }

    /**
     * Runs [operation] under the busy gate, refreshing afterwards and reporting failures. Installs
     * run one at a time: each activates (and, on failure, rolls back) a plugin runtime, so
     * overlapping them would only add contention.
     */
    fun runOperation(operation: suspend () -> Unit) =
        runGuarded {
            try {
                operation()
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                feedback.show("操作失败: ${error.message ?: "未知错误"}", SourceFeedbackKind.ERROR)
                runCatching { refresh() }
            }
        }

    /**
     * Runs [operation] under the same gate without refreshing or reporting, so the caller owns how
     * a failure reads. Used by work that shares the gate but is not an install, like loading the
     * repository index.
     */
    fun runGuarded(operation: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            _busy = true
            try {
                operation()
            } finally {
                if (currentCoroutineContext().isActive) _busy = false
            }
        }
    }

    /** Installs a repository entry, which may be a first install, so it reads as an install. */
    fun install(entry: PluginRepoEntry) {
        installEntry(entry, entry.name, SourceInstallVerb.Install)
    }

    /** Updates a source the device list already holds, so a success reads as an update. */
    fun update(entry: PluginRepoEntry, sourceName: String) {
        installEntry(entry, sourceName, SourceInstallVerb.Update)
    }

    /** Installs every pending update in sequence and reports the tally. */
    fun updateAll(candidates: List<PluginUpdateCandidate>) =
        runOperation {
            val outcomes =
                candidates.map { pluginManager.installUrl(it.entry.downloadSource(), activate = true) }
            val summary = summarizeUpdateAll(outcomes)
            feedback.show(
                summary.text,
                if (summary.isError) SourceFeedbackKind.ERROR else SourceFeedbackKind.INFO,
            )
        }

    /**
     * Installs a package from a download URL under the shared gate. The outcome is placed on the
     * screen that asked for it rather than announced, because that screen is about to be left.
     */
    fun installUrl(source: PluginDownloadSource, onOutcome: (PluginInstallResult?, String?) -> Unit) {
        place(onOutcome) { pluginManager.installUrl(source, activate = true) }
    }

    /** Installs a package the user already has on the device, under the same gate and rules. */
    fun installFile(uri: Uri, onOutcome: (PluginInstallResult?, String?) -> Unit) {
        place(onOutcome) { pluginManager.installUri(uri, activate = true) }
    }

    private fun installEntry(entry: PluginRepoEntry, sourceName: String, verb: SourceInstallVerb) =
        runOperation {
            report(
                pluginManager.installUrl(entry.downloadSource(), activate = true),
                sourceName,
                verb,
            )
        }

    private fun place(
        onOutcome: (PluginInstallResult?, String?) -> Unit,
        call: suspend () -> PluginInstallResult,
    ) = runGuarded {
        try {
            onOutcome(call(), null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onOutcome(null, error.message ?: "安装失败")
        } finally {
            runCatching { refresh() }
        }
    }

    /**
     * Shows the outcome with the requested verb and offers navigation only when a plugin ID is
     * available.
     */
    private fun report(outcome: PluginInstallResult, sourceName: String, verb: SourceInstallVerb) {
        val message = sourceInstallMessage(outcome, verb, sourceName)
        feedback.show(
            message.text,
            if (message.isError) SourceFeedbackKind.ERROR else SourceFeedbackKind.INFO,
            actionLabel = message.openPluginId?.let { "打开" },
        ) {
            message.openPluginId?.let(onOpenSource)
        }
    }
}

/**
 * The shared gate for screens that place their outcome instead of announcing it. The list screens
 * build their own instance because their refresh has to write to their own state.
 */
@Composable
internal fun rememberSourceInstallCoordinator(
    onOpenSource: (String) -> Unit = {},
): SourceInstallCoordinator {
    val application = LocalContext.current.applicationContext as InkleafApplication
    val feedback = rememberSourceFeedback()
    val scope = rememberCoroutineScope()
    val manager = application.pluginManager
    return remember(manager, feedback, scope, onOpenSource) {
        SourceInstallCoordinator(
            pluginManager = manager,
            feedback = feedback,
            scope = scope,
            refresh = {},
            onOpenSource = onOpenSource,
        )
    }
}
