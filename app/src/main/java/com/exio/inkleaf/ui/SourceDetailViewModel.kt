package com.exio.inkleaf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.diagnostics.AppErrorReport
import com.exio.inkleaf.diagnostics.AppErrorReporter
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginActionDescriptor
import com.exio.inkleaf.plugin.PluginActionKind
import com.exio.inkleaf.plugin.PluginActionRequest
import com.exio.inkleaf.plugin.PluginActionScope
import com.exio.inkleaf.plugin.PluginSessionState
import com.exio.inkleaf.plugin.PluginSettingDescriptor
import com.exio.inkleaf.plugin.actionsInScope
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Coordinates plugin-declared settings, actions, status, and uninstall state for one source. */
data class SourceDetailFeedback(val text: String, val copyDetails: String? = null)

class SourceDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val _plugin = MutableStateFlow<InstalledPlugin?>(null)
    val plugin: StateFlow<InstalledPlugin?> = _plugin.asStateFlow()

    private val _settings = MutableStateFlow<List<PluginSettingDescriptor>>(emptyList())
    val settings: StateFlow<List<PluginSettingDescriptor>> = _settings.asStateFlow()

    private val _actions = MutableStateFlow<List<PluginActionDescriptor>>(emptyList())
    val actions: StateFlow<List<PluginActionDescriptor>> = _actions.asStateFlow()

    /** Host-side view of the source's sign-in state, used to gate login and sign-out controls. */
    private val _session = MutableStateFlow(PluginSessionState())
    val session: StateFlow<PluginSessionState> = _session.asStateFlow()

    /** Persisted values; absent entries are displayed using their descriptor defaults. */
    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    val values: StateFlow<Map<String, String>> = _values.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** A describe failure does not prevent the rest of the source details from being used. */
    private val _describeError = MutableStateFlow<String?>(null)
    val describeError: StateFlow<String?> = _describeError.asStateFlow()

    private val _message = MutableStateFlow<SourceDetailFeedback?>(null)
    val message: StateFlow<SourceDetailFeedback?> = _message.asStateFlow()

    private var pluginId: String? = null
    private var loadedPluginId: String? = null
    private var loadJob: Job? = null
    private var authoritativeSettingIds: Set<String>? = null
    private val settingsRevision = AtomicLong(0L)
    private val persistedSettingsRevision = AtomicLong(0L)
    private val settingsSaveMutex = Mutex()

    fun load(pluginId: String) {
        if (loadedPluginId == pluginId) {
            if (_describeError.value != null && loadJob?.isActive != true) {
                loadJob = viewModelScope.launch { describe(pluginId) }
            }
            return
        }
        if (this.pluginId == pluginId && loadJob?.isActive == true) {
            return
        }
        if (this.pluginId != pluginId) {
            authoritativeSettingIds = null
            _settings.value = emptyList()
            _actions.value = emptyList()
            _values.value = emptyMap()
            _session.value = PluginSessionState()
        }
        this.pluginId = pluginId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val app = app()
                _plugin.value =
                    withContext(Dispatchers.IO) {
                        app.pluginManager.installed().firstOrNull { it.state.pluginId == pluginId }
                    }
                _values.value = app.pluginSettingsRepository.storedValues(pluginId)
                _session.value = app.pluginSessionStateRepository.state(pluginId)
                markSettingsClean()
                loadedPluginId = pluginId
                describe(pluginId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val report = reportError("加载漫画源详情", error)
                _describeError.value = report.summary
            }
        }
    }

    private suspend fun describe(pluginId: String) {
        val app = app()
        try {
            val described = app.pluginCatalog.describe(pluginId)
            _settings.value = described.settings
            // Comic-scoped actions are offered on the comic page, not here, where there is no comic.
            _actions.value = described.actionsInScope(PluginActionScope.SOURCE)
            authoritativeSettingIds = described.settings.mapTo(linkedSetOf()) { it.id }
            _describeError.value = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val report = reportError("读取漫画源描述", error)
            _describeError.value = report.summary
        }
    }

    fun setValue(settingId: String, value: String) {
        if (pluginId == null) return
        if (_values.value[settingId] == value) return
        // Update optimistically so controls do not bounce while DataStore catches up.
        _values.value = _values.value + (settingId to value)
        settingsRevision.incrementAndGet()
    }

    /** Waits for pending settings to persist before allowing normal navigation away. */
    fun saveSettingsAndThen(onSaved: () -> Unit) {
        if (_busy.value) return
        val id = pluginId
        if (id == null || !hasPendingSettings()) {
            onSaved()
            return
        }
        launchOperation("保存漫画源设置") {
            persistPendingSettings(app(), id)
            onSaved()
        }
    }

    /**
     * Saves pending settings before invoking a source action, then reports its result and
     * invalidates derived data.
     */
    fun invokeAction(action: PluginActionDescriptor) {
        val id = pluginId ?: return
        launchOperation("运行漫画源操作：${action.title}") {
            persistPendingSettings(app(), id)
            val result =
                app().pluginCatalog.invokeAction(id, PluginActionRequest(actionId = action.id))
            // Only a top-level message has a defined UI representation.
            _message.value = SourceDetailFeedback(sourceActionMessage(result, action.title))
            applySessionOutcome(action, result)
            // An action may alter plugin-owned state, so invalidate runtime-derived data now.
            app().pluginManager.invalidateDerivedData(id)
            // Descriptors can be derived from that state as well (the mirrors an action just
            // refreshed), so re-read them for the screen.
            describe(id)
        }
    }

    /**
     * Records what a login or sign-out action did to the host's session hint. A thrown plugin
     * error never reaches here, so a returned result is treated as success.
     */
    private suspend fun applySessionOutcome(action: PluginActionDescriptor, result: JsonElement) {
        val id = pluginId ?: return
        val next = sessionStateAfterAction(_session.value, action, result)
        if (next == _session.value) return
        if (next.signedIn) app().pluginSessionStateRepository.signedIn(id, next.account)
        else app().pluginSessionStateRepository.signedOut(id)
        _session.value = next
    }

    fun setEnabled(enabled: Boolean) {
        val id = pluginId ?: return
        launchOperation(if (enabled) "启用漫画源" else "停用漫画源") {
            app().pluginManager.setEnabled(id, enabled)
            reloadPlugin(id)
            if (enabled) describe(id)
        }
    }

    fun recover() {
        val id = pluginId ?: return
        launchOperation("恢复漫画源") {
            app().pluginManager.recover(id)
            reloadPlugin(id)
            describe(id)
        }
    }

    /**
     * Clears saved and displayed settings under the save mutex, then invalidates derived source
     * data.
     */
    fun resetToDefaults() {
        val id = pluginId ?: return
        launchOperation("重置漫画源设置") {
            settingsSaveMutex.withLock {
                app().pluginSettingsRepository.clear(id)
                app().pluginSessionStateRepository.clear(id)
                _values.value = emptyMap()
                _session.value = PluginSessionState()
                val revision = settingsRevision.incrementAndGet()
                app().pluginManager.invalidateDerivedData(id)
                persistedSettingsRevision.set(revision)
            }
            _message.value = SourceDetailFeedback("已重置为默认设置")
        }
    }

    fun uninstall(onDone: () -> Unit) {
        val id = pluginId ?: return
        launchOperation("卸载漫画源") {
            settingsSaveMutex.withLock {
                if (app().pluginManager.uninstall(id)) {
                    // Uninstall clears settings, so the exit path must not touch the removed
                    // source.
                    app().pluginSessionStateRepository.clear(id)
                    _session.value = PluginSessionState()
                    markSettingsClean()
                    onDone()
                }
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private suspend fun reloadPlugin(pluginId: String) {
        _plugin.value =
            withContext(Dispatchers.IO) {
                app().pluginManager.installed().firstOrNull { it.state.pluginId == pluginId }
            }
    }

    /**
     * Persists the supplied setting values and invalidates data derived from the previous
     * configuration.
     */
    private suspend fun applySettingsChange(
        app: InkleafApplication,
        pluginId: String,
        values: Map<String, String>,
        settingIds: Set<String>,
    ) {
        app.pluginSettingsRepository.setValues(pluginId, values, settingIds)
        app.pluginManager.invalidateDerivedData(pluginId)
    }

    private fun hasPendingSettings(): Boolean =
        settingsRevision.get() != persistedSettingsRevision.get()

    private fun markSettingsClean() {
        persistedSettingsRevision.set(settingsRevision.get())
    }

    private suspend fun persistPendingSettings(app: InkleafApplication, pluginId: String) {
        settingsSaveMutex.withLock {
            val revision = settingsRevision.get()
            if (revision == persistedSettingsRevision.get()) return@withLock
            val values = _values.value
            val settingIds =
                checkNotNull(authoritativeSettingIds) {
                    "Plugin setting descriptors are unavailable"
                }
            applySettingsChange(app, pluginId, values, settingIds)
            persistedSettingsRevision.set(revision)
        }
    }

    private fun launchOperation(operationName: String, operation: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportError(operationName, error)
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun reportError(
        operationName: String,
        error: Throwable,
        showFeedback: Boolean = true,
    ): AppErrorReport {
        val report =
            AppErrorReporter.report(
                context = getApplication(),
                operation = operationName,
                error = error,
                metadata = mapOf("Plugin ID" to (pluginId ?: "unknown")),
            )
        if (showFeedback) {
            _message.value = SourceDetailFeedback(report.summary, report.details)
        }
        return report
    }

    private fun app() = getApplication<InkleafApplication>()
}

internal fun sourceActionMessage(result: JsonElement, actionTitle: String): String =
    ((result as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
        ?: "$actionTitle：操作完成"

/**
 * Applies an action's outcome to the session hint. Only the protocol's kind enum drives the
 * decision; a sign-in kind marks the source signed in with whatever account the plugin reported,
 * and a sign-out kind clears it. Any other action leaves the state untouched.
 */
internal fun sessionStateAfterAction(
    current: PluginSessionState,
    action: PluginActionDescriptor,
    result: JsonElement,
): PluginSessionState =
    when {
        action.kind in PluginActionKind.signInKinds ->
            PluginSessionState(
                signedIn = true,
                account = actionResultAccount(result) ?: current.account,
            )
        action.isSignOut() -> PluginSessionState()
        else -> current
    }

/** The account a login action reports at the top level, when it reports one. */
private fun actionResultAccount(result: JsonElement): String? =
    ((result as? JsonObject)?.get("account") as? JsonPrimitive)
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
