package com.exio.inkleaf.plugin

import android.content.Context
import androidx.core.util.Consumer
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.IsolateTerminatedException
import androidx.javascriptengine.JavaScriptException
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.Message
import androidx.javascriptengine.MessagePort
import androidx.javascriptengine.MessagePortClient
import androidx.javascriptengine.TerminationInfo
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface PluginRuntime : AutoCloseable {
    val pluginId: String
    val version: String

    suspend fun invoke(
        method: String,
        params: JsonElement = JsonObject(emptyMap()),
        timeoutMs: Long = PluginRuntimePolicy.NORMAL_DEADLINE_MS,
    ): JsonElement
}

class PluginRuntimeUnavailableException(message: String) : Exception(message)

/** AndroidX JavaScriptEngine adapter. No AndroidX type escapes this class. */
class AndroidJavaScriptPluginRuntime(
    override val pluginId: String,
    override val version: String,
    private val versionDirectory: File,
    private val sandbox: JavaScriptSandbox,
    private val callbackExecutor: Executor,
    private val hostSession: PluginHostSession,
    private val onTerminated: (TerminationInfo?) -> Unit = {},
) : PluginRuntime {
    private val closed = AtomicBoolean(false)
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isolate: JavaScriptIsolate? = null
    private var port: MessagePort? = null
    private var rpc: PluginRpcClient? = null
    private var terminationCallback: Consumer<TerminationInfo>? = null

    suspend fun start() {
        check(!closed.get()) { "Plugin runtime is closed" }
        ensureFeatureGate(sandbox)
        val startup =
            IsolateStartupParameters().apply {
                setMaxHeapSizeBytes(PluginRuntimePolicy.JS_HEAP_BYTES)
                setMaxEvaluationReturnSizeBytes(PluginRuntimePolicy.MAX_EVALUATION_RETURN_BYTES)
            }
        val createdIsolate = sandbox.createIsolate(startup)
        isolate = createdIsolate
        val callback =
            Consumer<TerminationInfo> { info ->
                rpc?.failAll(
                    PluginRpcException(
                        PluginRpcError(
                            PluginErrorCode.RUNTIME_TERMINATED,
                            "Plugin isolate terminated: ${info.statusString}",
                        )
                    )
                )
                onTerminated(info)
            }
        terminationCallback = callback
        createdIsolate.addOnTerminatedCallback(callbackExecutor, callback)

        val portName = "inkleaf-${pluginId.hashCode().toUInt().toString(16)}-${UUID.randomUUID()}"
        val createdPort =
            createdIsolate.createMessageChannel(
                portName,
                callbackExecutor,
                object : MessagePortClient {
                    override fun onMessage(message: Message) {
                        val client = rpc ?: return
                        if (message.type != Message.TYPE_STRING) {
                            client.failAll(
                                PluginRpcException(
                                    PluginRpcError(
                                        PluginErrorCode.PLUGIN_PROTOCOL,
                                        "Only string RPC messages are supported",
                                    )
                                )
                            )
                            return
                        }
                        client.onMessage(message.string)
                    }
                },
            )
        port = createdPort
        val transport = PluginRpcTransport { encoded ->
            createdPort.postMessage(Message.createStringMessage(encoded))
        }
        val client =
            PluginRpcClient(
                transport = transport,
                hostHandler = hostSession,
                scope = runtimeScope,
            )
        rpc = client
        try {
            val (script, manifest) =
                withContext(Dispatchers.IO) {
                    val loadedScript =
                        versionDirectory
                            .resolve(PluginContract.ENTRY_PATH)
                            .readText(StandardCharsets.UTF_8)
                    val loadedManifest =
                        runtimeJson.decodeFromString<PluginManifest>(
                            versionDirectory
                                .resolve(PluginContract.MANIFEST_PATH)
                                .readText(StandardCharsets.UTF_8)
                        )
                    loadedScript to loadedManifest
                }
            val requiredMethods = buildSet {
                add("describe")
                addAll(manifest.capabilities.filter { it in PluginCapabilities.declaredMethods })
            }
            val bootstrapResult =
                createdIsolate
                    .evaluateJavaScriptAsync(
                        PluginBootstrap.script(script, portName, requiredMethods)
                    )
                    .awaitJavaScript()
            if (bootstrapResult != "BOOTSTRAPPED") {
                throw PluginRpcException(
                    PluginRpcError(
                        PluginErrorCode.PLUGIN_PROTOCOL,
                        "Plugin bootstrap returned an unexpected value",
                    )
                )
            }
            client.awaitReady()
        } catch (error: Throwable) {
            closeOffMain()
            throw mapRuntimeError(error)
        }
    }

    override suspend fun invoke(
        method: String,
        params: JsonElement,
        timeoutMs: Long,
    ): JsonElement {
        val client =
            rpc
                ?: throw PluginRpcException(
                    PluginRpcError(
                        PluginErrorCode.RUNTIME_TERMINATED,
                        "Plugin runtime has not started",
                    )
                )
        return try {
            client.call(
                method,
                params,
                timeoutMs.coerceIn(1L, PluginRuntimePolicy.HARD_DEADLINE_MS),
            )
        } catch (error: PluginRpcException) {
            if (error.error.code == PluginErrorCode.TIMEOUT) {
                // A timed-out JS invocation may still be executing. Closing the isolate is the only
                // reliable cancellation boundary exposed by AndroidX JavaScriptEngine v1.
                isolate?.close()
            }
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw mapRuntimeError(error)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        rpc?.close()
        rpc = null
        runCatching { port?.close() }
        port = null
        runCatching {
            terminationCallback?.let { callback -> isolate?.removeOnTerminatedCallback(callback) }
        }
        runCatching { isolate?.close() }
        isolate = null
        hostSession.close()
        runtimeScope.cancel()
    }

    private fun ensureFeatureGate(sandbox: JavaScriptSandbox) {
        if (!JavaScriptSandbox.isSupported()) {
            throw PluginRuntimeUnavailableException("WebView does not support JavaScriptSandbox")
        }
        val missing = REQUIRED_FEATURES.filterNot(sandbox::isFeatureSupported)
        if (missing.isNotEmpty()) {
            throw PluginRuntimeUnavailableException(
                "Missing JavaScriptEngine features: ${missing.joinToString()}"
            )
        }
    }

    private fun mapRuntimeError(error: Throwable): Throwable {
        val root = rootCause(error)
        if (root is PluginRpcException) return root
        if (root is IsolateTerminatedException) {
            return PluginRpcException(
                PluginRpcError(
                    PluginErrorCode.RUNTIME_TERMINATED,
                    "Plugin isolate terminated",
                    retryable = true,
                ),
                root,
            )
        }
        if (root is JavaScriptException) {
            return PluginRpcException(
                PluginRpcError(
                    PluginErrorCode.PLUGIN_ERROR,
                    root.message ?: "Plugin JavaScript error",
                ),
                root,
            )
        }
        return error
    }

    private companion object {
        val runtimeJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val REQUIRED_FEATURES =
            listOf(
                JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS,
                JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN,
                JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE,
                JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,
            )
    }
}

/** Shared sandbox and plugin lifecycle coordinator. */
class PluginRuntimeManager(
    private val context: Context,
    private val store: PluginPackageStore = PluginPackageStore(File(context.filesDir, "plugins")),
    private val callbackExecutor: ExecutorService = Executors.newCachedThreadPool(),
    private val settingsRepository: PluginSettingsRepository? = null,
) : AutoCloseable {
    private val lock = Mutex()
    private val globalSemaphore = Semaphore(PluginRuntimePolicy.MAX_GLOBAL_CONCURRENCY)
    private val globalHttpSemaphore = Semaphore(PluginRuntimePolicy.MAX_GLOBAL_HTTP_CONCURRENCY)
    private val pluginSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val runtimes = ConcurrentHashMap<String, AndroidJavaScriptPluginRuntime>()
    private val activeInvocations = ConcurrentHashMap<String, AtomicInteger>()
    private val runtimeLastUsed = ConcurrentHashMap<String, Long>()
    private val accessCounter = AtomicLong(0L)
    @Volatile private var sandbox: JavaScriptSandbox? = null
    private val closed = AtomicBoolean(false)

    suspend fun invoke(
        pluginId: String,
        method: String,
        params: JsonElement = JsonObject(emptyMap()),
        timeoutMs: Long = PluginRuntimePolicy.NORMAL_DEADLINE_MS,
    ): JsonElement {
        val plugin =
            withContext(Dispatchers.IO) { store.get(pluginId) }
                ?: throw PluginRpcException(
                    PluginRpcError(PluginErrorCode.NOT_FOUND, "Plugin is not installed")
                )
        if (plugin.state.disabled) {
            throw PluginRpcException(
                PluginRpcError(PluginErrorCode.PLUGIN_DISABLED, "Plugin is disabled")
            )
        }
        if (plugin.state.health == PluginHealth.RUNTIME_UNHEALTHY) {
            throw PluginRpcException(
                PluginRpcError(
                    PluginErrorCode.RUNTIME_UNHEALTHY,
                    "Plugin requires explicit recovery",
                )
            )
        }
        if (plugin.state.activeVersion == null || plugin.activeDirectory == null) {
            throw PluginRpcException(
                PluginRpcError(PluginErrorCode.NOT_FOUND, "Plugin has no active version")
            )
        }
        var runtimeUsed: AndroidJavaScriptPluginRuntime? = null
        return try {
            val pluginSemaphore =
                pluginSemaphores.computeIfAbsent(pluginId) {
                    Semaphore(PluginRuntimePolicy.MAX_PLUGIN_CONCURRENCY)
                }
            globalSemaphore.withPermit {
                pluginSemaphore.withPermit {
                    val active = activeInvocations.computeIfAbsent(pluginId) { AtomicInteger(0) }
                    active.incrementAndGet()
                    try {
                        val runtime = runtimeFor(plugin)
                        runtimeUsed = runtime
                        runtime.invoke(method, params, timeoutMs)
                    } finally {
                        if (active.decrementAndGet() == 0) {
                            activeInvocations.remove(pluginId, active)
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PluginRpcException) {
            if (
                error.error.code == PluginErrorCode.RUNTIME_TERMINATED ||
                    error.error.code == PluginErrorCode.TIMEOUT
            ) {
                runtimeUsed?.let { runtime ->
                    withContext(Dispatchers.IO) { handleFatal(pluginId, runtime) }
                }
            }
            throw error
        }
    }

    suspend fun describe(pluginId: String): JsonElement =
        invoke(pluginId, "describe", timeoutMs = PluginRuntimePolicy.LIGHT_DEADLINE_MS)

    suspend fun install(packageFile: File, activate: Boolean = false): PluginInstallResult {
        val result = withContext(Dispatchers.IO) { store.install(packageFile, activate = false) }
        if (activate && result.status != PluginInstallStatus.REJECTED && result.activatable) {
            val pluginId = requireNotNull(result.pluginId)
            val version = requireNotNull(result.version)
            val alreadyActive =
                withContext(Dispatchers.IO) {
                    store.get(pluginId)?.state?.isActiveAndReady(version) == true
                }
            if (!alreadyActive) activate(pluginId, version)
        }
        return result
    }

    private suspend fun activate(pluginId: String, version: String): InstalledPlugin {
        val previousVersion =
            withContext(Dispatchers.IO) { store.get(pluginId)?.state?.activeVersion }
        val activated = withContext(Dispatchers.IO) { store.activate(pluginId, version) }
        closeRuntime(pluginId)
        return try {
            runtimeFor(activated)
            activated
        } catch (error: Throwable) {
            closeRuntime(pluginId)
            withContext(Dispatchers.IO) {
                if (previousVersion != null) store.activate(pluginId, previousVersion)
                else store.deactivate(pluginId, expectedVersion = version)
            }
            throw error
        }
    }

    suspend fun setEnabled(pluginId: String, enabled: Boolean): InstalledPlugin? {
        val updated = withContext(Dispatchers.IO) { store.setEnabled(pluginId, enabled) }
        if (!enabled) closeRuntime(pluginId)
        return updated
    }

    suspend fun recover(pluginId: String): InstalledPlugin? {
        val recovered = withContext(Dispatchers.IO) { store.clearHealth(pluginId) }
        closeRuntime(pluginId)
        return recovered
    }

    suspend fun uninstall(pluginId: String): Boolean {
        if (withContext(Dispatchers.IO) { store.setEnabled(pluginId, enabled = false) } == null)
            return false
        closeRuntime(pluginId)
        return withContext(Dispatchers.IO) { store.uninstall(pluginId) }
    }

    fun installedPlugins(): List<InstalledPlugin> = store.list()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val resources = runBlocking {
            lock.withLock {
                val runtimesToClose = runtimes.values.toList()
                runtimes.clear()
                runtimeLastUsed.clear()
                activeInvocations.clear()
                val sandboxToClose = sandbox
                sandbox = null
                runtimesToClose to sandboxToClose
            }
        }
        runBlocking { resources.first.forEach { it.closeOffMain() } }
        runCatching { resources.second?.close() }
        callbackExecutor.shutdownNow()
    }

    private suspend fun runtimeFor(plugin: InstalledPlugin): AndroidJavaScriptPluginRuntime {
        val (resolved, created) = lock.withLock {
            check(!closed.get()) { "Plugin runtime manager is closed" }
            val currentPlugin =
                withContext(Dispatchers.IO) { store.get(plugin.state.pluginId) }
                    ?: throw PluginRpcException(
                        PluginRpcError(PluginErrorCode.NOT_FOUND, "Plugin is not installed")
                    )
            if (currentPlugin.state.disabled) {
                throw PluginRpcException(
                    PluginRpcError(PluginErrorCode.PLUGIN_DISABLED, "Plugin is disabled")
                )
            }
            if (currentPlugin.state.health == PluginHealth.RUNTIME_UNHEALTHY) {
                throw PluginRpcException(
                    PluginRpcError(
                        PluginErrorCode.RUNTIME_UNHEALTHY,
                        "Plugin requires explicit recovery",
                    )
                )
            }
            if (currentPlugin.state.activeVersion != plugin.state.activeVersion) {
                throw PluginRpcException(
                    PluginRpcError(
                        PluginErrorCode.RUNTIME_TERMINATED,
                        "Plugin version changed while the invocation was waiting",
                        retryable = true,
                    )
                )
            }
            runtimes[plugin.state.pluginId]?.let {
                if (it.version == plugin.state.activeVersion) {
                    runtimeLastUsed[plugin.state.pluginId] = accessCounter.incrementAndGet()
                    return@withLock it to false
                }
                runtimes.remove(plugin.state.pluginId, it)
                runtimeLastUsed.remove(plugin.state.pluginId)
                it.closeOffMain()
            }
            evictIdleRuntimeLocked(plugin.state.pluginId)
            val currentSandbox = ensureSandboxLocked()
            val activeDirectory =
                plugin.activeDirectory
                    ?: throw PluginRpcException(
                        PluginRpcError(PluginErrorCode.NOT_FOUND, "Plugin has no active version")
                    )
            val hostSession =
                PluginHostSession(
                    plugin.state.pluginId,
                    plugin.directory,
                    globalHttpSemaphore = globalHttpSemaphore,
                    settingsReader = { pluginId, settingId ->
                        settingsRepository?.resolve(pluginId, settingId)
                    },
                )
            lateinit var runtime: AndroidJavaScriptPluginRuntime
            runtime =
                AndroidJavaScriptPluginRuntime(
                    pluginId = plugin.state.pluginId,
                    version = plugin.state.activeVersion!!,
                    versionDirectory = activeDirectory,
                    sandbox = currentSandbox,
                    callbackExecutor = callbackExecutor,
                    hostSession = hostSession,
                    onTerminated = { info ->
                        callbackExecutor.execute {
                            runBlocking {
                                if (info?.status == TerminationInfo.STATUS_SANDBOX_DEAD) {
                                    handleSandboxDeath()
                                } else {
                                    handleFatal(plugin.state.pluginId, runtime)
                                }
                            }
                        }
                    },
                )
            try {
                runtime.start()
            } catch (error: Throwable) {
                runtime.closeOffMain()
                throw error
            }
            runtimes[plugin.state.pluginId] = runtime
            runtimeLastUsed[plugin.state.pluginId] = accessCounter.incrementAndGet()
            runtime to true
        }
        // Prewarm outside the non-reentrant lock because describe performs a plugin RPC.
        if (created) prewarmSettingDescriptors(resolved)
        return resolved
    }

    /**
     * Caches plugin-declared settings after isolate startup so settings.get can resolve defaults.
     *
     * This is best effort: a missing or failing describe call leaves the settings panel empty but
     * must not prevent browse, search, or reading calls.
     */
    private suspend fun prewarmSettingDescriptors(runtime: AndroidJavaScriptPluginRuntime) {
        val repository = settingsRepository ?: return
        try {
            val described =
                runtime.invoke(
                    "describe",
                    timeoutMs = PluginRuntimePolicy.LIGHT_DEADLINE_MS,
                )
            repository.cacheDescriptors(
                runtime.pluginId,
                withContext(Dispatchers.Default) { PluginContentCodec.describe(described) }
                    .settings,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            repository.forgetDescriptors(runtime.pluginId)
        }
    }

    /**
     * Invalidates a source runtime so the next invocation rebuilds it.
     *
     * Long-lived isolates may cache settings at module scope, so setting changes require rebuild.
     */
    suspend fun reload(pluginId: String) {
        closeRuntime(pluginId)
        settingsRepository?.forgetDescriptors(pluginId)
    }

    /** Evict only a runtime with no active invocation; busy isolates are never force-closed. */
    private suspend fun evictIdleRuntimeLocked(requestedPluginId: String) {
        if (runtimes.size < PluginRuntimePolicy.MAX_ACTIVE_ISOLATES) return
        val candidateId =
            runtimes.keys
                .asSequence()
                .filter { it != requestedPluginId }
                .filter { (activeInvocations[it]?.get() ?: 0) == 0 }
                .minByOrNull { runtimeLastUsed[it] ?: Long.MIN_VALUE }
                ?: throw PluginRpcException(
                    PluginRpcError(
                        PluginErrorCode.QUOTA_EXCEEDED,
                        "All plugin runtime slots are busy; retry after an invocation completes",
                        retryable = true,
                    )
                )
        val candidate = runtimes.remove(candidateId)
        runtimeLastUsed.remove(candidateId)
        candidate?.closeOffMain()
    }

    private suspend fun ensureSandboxLocked(): JavaScriptSandbox {
        sandbox?.let {
            return it
        }
        if (!JavaScriptSandbox.isSupported()) {
            throw PluginRuntimeUnavailableException("WebView does not support JavaScriptSandbox")
        }
        val connected = createConnectedSandbox()
        val missing = REQUIRED_FEATURES.filterNot(connected::isFeatureSupported)
        if (missing.isNotEmpty()) {
            connected.close()
            throw PluginRuntimeUnavailableException(
                "Missing JavaScriptEngine features: ${missing.joinToString()}"
            )
        }
        sandbox = connected
        return connected
    }

    private suspend fun createConnectedSandbox(): JavaScriptSandbox =
        JavaScriptSandbox.createConnectedInstanceAsync(context).awaitJavaScript()

    private suspend fun handleFatal(
        pluginId: String,
        expectedRuntime: AndroidJavaScriptPluginRuntime? = null,
    ) {
        val runtime: AndroidJavaScriptPluginRuntime =
            lock.withLock {
                when {
                    expectedRuntime == null -> runtimes.remove(pluginId) ?: return@withLock null
                    runtimes.remove(pluginId, expectedRuntime) -> expectedRuntime
                    else -> return@withLock null
                }.also { runtimeLastUsed.remove(pluginId) }
            } ?: return
        runtime.closeOffMain()
        runCatching { store.recordFatalFailure(pluginId) }
    }

    private suspend fun handleSandboxDeath() {
        val resources = lock.withLock {
            val runtimesToClose = runtimes.values.toList()
            runtimes.clear()
            runtimeLastUsed.clear()
            val sandboxToClose = sandbox
            sandbox = null
            runtimesToClose to sandboxToClose
        }
        resources.first.forEach { it.closeOffMain() }
        runCatching { resources.second?.close() }
    }

    private suspend fun closeRuntime(pluginId: String) {
        val runtime = lock.withLock {
            val removed = runtimes.remove(pluginId)
            runtimeLastUsed.remove(pluginId)
            removed
        }
        runtime?.closeOffMain()
    }

    private companion object {
        val REQUIRED_FEATURES =
            listOf(
                JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS,
                JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN,
                JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE,
                JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,
            )
    }
}

internal suspend fun closeRuntimeOffMain(close: () -> Unit) {
    withContext(NonCancellable + Dispatchers.IO) { close() }
}

private suspend fun AndroidJavaScriptPluginRuntime.closeOffMain() {
    closeRuntimeOffMain(::close)
}

private suspend fun <T> ListenableFuture<T>.awaitJavaScript(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (error: Throwable) {
                    val cause = rootCause(error)
                    if (continuation.isActive) continuation.resumeWithException(cause)
                }
            },
            MoreExecutors.directExecutor(),
        )
        continuation.invokeOnCancellation { cancel(true) }
    }

private fun rootCause(error: Throwable): Throwable {
    var current = error
    while (current.cause != null && current.cause !== current) current = current.cause!!
    return current
}
