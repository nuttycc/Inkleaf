package com.exio.inkleaf.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Persistence behind [SourceSessionHint]. */
internal interface SourceSessionStore {
    fun observe(pluginId: String): Flow<PluginSessionState>

    suspend fun read(pluginId: String): PluginSessionState

    suspend fun signedIn(pluginId: String, account: String?)

    suspend fun signedOut(pluginId: String)

    suspend fun clear(pluginId: String)
}

internal fun PluginSessionStateRepository.asSessionStore(): SourceSessionStore =
    object : SourceSessionStore {
        override fun observe(pluginId: String) = this@asSessionStore.observe(pluginId)

        override suspend fun read(pluginId: String) = state(pluginId)

        override suspend fun signedIn(pluginId: String, account: String?) =
            this@asSessionStore.signedIn(pluginId, account)

        override suspend fun signedOut(pluginId: String) = this@asSessionStore.signedOut(pluginId)

        override suspend fun clear(pluginId: String) = this@asSessionStore.clear(pluginId)
    }

/**
 * The app's one record of every source's sign-in state.
 *
 * The hint is recorded from the outcome of the plugin's own login and sign-out actions, cleared by
 * any `AUTH_REQUIRED` answer, and reconciled with the plugin's session check. The check runs both
 * ways, but never overwrites an outcome that landed while it was in flight.
 */
internal class SourceSessionHint(
    private val store: SourceSessionStore,
    private val checkSession: suspend (String) -> PluginSessionCheck,
) {
    private val writeLock = Mutex()

    fun observe(pluginId: String): Flow<PluginSessionState> = store.observe(pluginId)

    /** Records what a login or sign-out action did to the hint. */
    suspend fun applyOutcome(pluginId: String, action: PluginActionDescriptor, result: JsonElement) {
        writeLock.withLock {
            val current = store.read(pluginId)
            persist(pluginId, current, sessionStateAfterAction(current, action, result))
        }
    }

    /** The source answered that it needs a sign-in, so a recorded session is no longer live. */
    suspend fun authRequired(pluginId: String) {
        writeLock.withLock {
            persist(pluginId, store.read(pluginId), PluginSessionState())
        }
    }

    /**
     * Reconciles the hint with the source itself. A live session repairs a hint that was never
     * recorded, and an explicit "not signed in" answer clears a stale one. A missing method or a
     * network failure proves nothing, so the hint stays untouched.
     */
    suspend fun reconcile(pluginId: String) {
        val before = store.read(pluginId)
        val check =
            try {
                checkSession(pluginId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return
            }
        writeLock.withLock {
            val current = store.read(pluginId)
            // A login or sign-out may have landed while the check was in flight; its result is newer.
            if (current != before) return
            val next =
                if (check.signedIn) {
                    PluginSessionState(signedIn = true, account = check.account ?: current.account)
                } else {
                    PluginSessionState()
                }
            persist(pluginId, current, next)
        }
    }

    /** Clears the stored session as part of the source uninstall and reset contract. */
    suspend fun clear(pluginId: String) {
        writeLock.withLock { store.clear(pluginId) }
    }

    private suspend fun persist(
        pluginId: String,
        current: PluginSessionState,
        next: PluginSessionState,
    ) {
        if (next == current) return
        if (next.signedIn) store.signedIn(pluginId, next.account) else store.signedOut(pluginId)
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
    when (action.kind) {
        in PluginActionKind.signInKinds ->
            PluginSessionState(signedIn = true, account = actionResultAccount(result) ?: current.account)
        in PluginActionKind.signOutKinds -> PluginSessionState()
        else -> current
    }

/** The account a login action reports at the top level, when it reports one. */
private fun actionResultAccount(result: JsonElement): String? =
    ((result as? JsonObject)?.get("account") as? JsonPrimitive)
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
