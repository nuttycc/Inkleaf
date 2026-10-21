package com.exio.inkleaf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.diagnostics.AppErrorReport
import com.exio.inkleaf.diagnostics.AppErrorReporter
import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginActionDescriptor
import com.exio.inkleaf.plugin.PluginActionRequest
import com.exio.inkleaf.plugin.PluginActionScope
import com.exio.inkleaf.plugin.PluginSessionState
import com.exio.inkleaf.plugin.PluginSettingDescriptor
import com.exio.inkleaf.plugin.actionsInScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Coordinates plugin-declared settings, actions, status, and uninstall state for one source. */
data class SourceDetailFeedback(val text: String, val copyDetails: String? = null)

class SourceDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsDraft =
        SourceSettingsDraft(
            object : SourceSettingsStore {
                override suspend fun setValues(
                    pluginId: String,
                    values: Map<String, String>,
                    settingIds: Set<String>,
                ) = app().pluginSettingsRepository.setValues(pluginId, values, settingIds)

                override suspend fun clear(pluginId: String) =
                    app().pluginSettingsRepository.clear(pluginId)
            }
        )

    private val sessionHint =
        SourceSessionHint(
            store =
                object : SourceSessionStore {
                    override suspend fun read(pluginId: String) =
                        app().pluginSessionStateRepository.state(pluginId)

                    override suspend fun signedIn(pluginId: String, account: String?) =
                        app().pluginSessionStateRepository.signedIn(pluginId, account)

                    override suspend fun signedOut(pluginId: String) =
                        app().pluginSessionStateRepository.signedOut(pluginId)

                    override suspend fun clear(pluginId: String) =
                        app().pluginSessionStateRepository.clear(pluginId)
                },
            checkSession = { pluginId -> app().pluginCatalog.checkSession(pluginId) },
        )

    private val _plugin = MutableStateFlow<InstalledPlugin?>(null)
    val plugin: StateFlow<InstalledPlugin?> = _plugin.asStateFlow()

    private val _settings = MutableStateFlow<List<PluginSettingDescriptor>>(emptyList())
    val settings: StateFlow<List<PluginSettingDescriptor>> = _settings.asStateFlow()

    private val _actions = MutableStateFlow<List<PluginActionDescriptor>>(emptyList())
    val actions: StateFlow<List<PluginActionDescriptor>> = _actions.asStateFlow()

    /** Persisted values and pending edits, owned by the draft. */
    val values: StateFlow<Map<String, String>> = settingsDraft.values

    /** Host-side view of the source's sign-in state, owned by the session hint. */
    val session: StateFlow<PluginSessionState> = sessionHint.state

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
    private var reconcileJob: Job? = null

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
            reconcileJob?.cancel()
            _settings.value = emptyList()
            _actions.value = emptyList()
            settingsDraft.load(emptyMap())
            sessionHint.reset()
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
                settingsDraft.load(app.pluginSettingsRepository.storedValues(pluginId))
                sessionHint.load(pluginId)
                loadedPluginId = pluginId
                describe(pluginId)
                // The hint can outlive the site session; confirm it off the main path.
                reconcileJob?.cancel()
                reconcileJob = viewModelScope.launch { sessionHint.reconcile(pluginId) }
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
            settingsDraft.adoptDescriptorIds(described.settings.mapTo(linkedSetOf()) { it.id })
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
        settingsDraft.setValue(settingId, value)
    }

    /** Waits for pending settings to persist before allowing normal navigation away. */
    fun saveSettingsAndThen(onSaved: () -> Unit) {
        if (_busy.value) return
        val id = pluginId
        if (id == null || !settingsDraft.hasPending()) {
            onSaved()
            return
        }
        launchOperation("保存漫画源设置") {
            settingsDraft.flush(id)
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
            settingsDraft.flush(id)
            val result =
                app().pluginCatalog.invokeAction(id, PluginActionRequest(actionId = action.id))
            // Only a top-level message has a defined UI representation.
            _message.value = SourceDetailFeedback(sourceActionMessage(result, action.title))
            sessionHint.applyOutcome(id, action, result)
            // An action may alter plugin-owned state, so invalidate runtime-derived data now.
            app().pluginManager.invalidateDerivedData(id)
            // Descriptors can be derived from that state as well (the mirrors an action just
            // refreshed), so re-read them for the screen.
            describe(id)
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

    /** Clears saved and displayed settings, then invalidates derived source data. */
    fun resetToDefaults() {
        val id = pluginId ?: return
        launchOperation("重置漫画源设置") {
            settingsDraft.clear(id)
            sessionHint.clear(id)
            app().pluginManager.invalidateDerivedData(id)
            _message.value = SourceDetailFeedback("已重置为默认设置")
        }
    }

    fun uninstall(onDone: () -> Unit) {
        val id = pluginId ?: return
        launchOperation("卸载漫画源") {
            if (app().pluginManager.uninstall(id)) {
                // Uninstall clears settings, so the exit path must not touch the removed source.
                sessionHint.clear(id)
                settingsDraft.markClean()
                onDone()
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
