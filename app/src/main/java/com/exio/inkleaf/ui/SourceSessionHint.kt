package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.PluginActionDescriptor
import com.exio.inkleaf.plugin.PluginActionKind
import com.exio.inkleaf.plugin.PluginSessionCheck
import com.exio.inkleaf.plugin.PluginSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Persistence behind [SourceSessionHint]. */
internal interface SourceSessionStore {
    suspend fun read(pluginId: String): PluginSessionState

    suspend fun signedIn(pluginId: String, account: String?)

    suspend fun signedOut(pluginId: String)

    suspend fun clear(pluginId: String)
}

/**
 * Host-side memory of one source's sign-in state.
 *
 * The hint is recorded from the outcome of the plugin's own login and sign-out actions, then
 * reconciled with the plugin's session check. The check runs both ways, but never overwrites an
 * outcome that landed while it was in flight.
 */
internal class SourceSessionHint(
    private val store: SourceSessionStore,
    private val checkSession: suspend (String) -> PluginSessionCheck,
) {
    private val _state = MutableStateFlow(PluginSessionState())
    val state: StateFlow<PluginSessionState> = _state.asStateFlow()

    /** Replaces the hint with the stored snapshot for [pluginId]. */
    suspend fun load(pluginId: String) {
        _state.value = store.read(pluginId)
    }

    /** Forgets the displayed hint before a different plugin is loaded. */
    fun reset() {
        _state.value = PluginSessionState()
    }

    /** Records what a login or sign-out action did to the hint. */
    suspend fun applyOutcome(
        pluginId: String,
        action: PluginActionDescriptor,
        result: JsonElement,
    ) {
        val next = sessionStateAfterAction(_state.value, action, result)
        if (next == _state.value) return
        persist(pluginId, next)
    }

    /**
     * Reconciles the hint with the source itself. A live session repairs a hint that was never
     * recorded, and an explicit "not signed in" answer clears a stale one. A missing method or a
     * network failure proves nothing, so the hint stays untouched.
     */
    suspend fun reconcile(pluginId: String) {
        val current = _state.value
        val check =
            try {
                checkSession(pluginId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return
            }
        // A login or sign-out may have landed while the check was in flight; its result is newer.
        if (_state.value != current) return
        val next =
            if (check.signedIn) {
                PluginSessionState(signedIn = true, account = check.account ?: current.account)
            } else {
                PluginSessionState()
            }
        if (next == current) return
        persist(pluginId, next)
    }

    /** Clears the stored session as part of the source uninstall and reset contract. */
    suspend fun clear(pluginId: String) {
        store.clear(pluginId)
        _state.value = PluginSessionState()
    }

    private suspend fun persist(pluginId: String, next: PluginSessionState) {
        if (next.signedIn) store.signedIn(pluginId, next.account)
        else store.signedOut(pluginId)
        _state.value = next
    }
}

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
