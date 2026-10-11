package com.exio.inkleaf.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * Runs one source install at a time for the snackbar screens. It owns the busy gate, refreshes the
 * caller's list afterwards, and turns each outcome into wording through the shared pure functions,
 * so a screen only decides which verb its list means.
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

    /** Updates a source the device list already holds, so a success reads as an update. */
    fun update(entry: PluginRepoEntry, sourceName: String) =
        runOperation {
            report(
                pluginManager.installUrl(entry.downloadSource(), activate = true),
                sourceName,
                SourceInstallVerb.Update,
            )
        }

    /** Installs a catalog entry, which may be a first install, so a success reads as an install. */
    fun install(entry: PluginRepoEntry) =
        runOperation {
            report(
                pluginManager.installUrl(entry.downloadSource(), activate = true),
                "未知插件",
                SourceInstallVerb.Install,
            )
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
