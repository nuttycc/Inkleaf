package com.exio.inkleaf.plugin

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class PluginRpcError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val details: JsonElement = JsonObject(emptyMap()),
)

sealed interface PluginRpcMessage {
    data class Request(
        val requestId: String,
        val method: String,
        val params: JsonElement = JsonObject(emptyMap()),
    ) : PluginRpcMessage

    data class Response(
        val requestId: String,
        val result: JsonElement? = null,
        val error: PluginRpcError? = null,
        val hostResponse: Boolean = false,
    ) : PluginRpcMessage

    data class HostRequest(
        val requestId: String,
        val method: String,
        val params: JsonElement = JsonObject(emptyMap()),
    ) : PluginRpcMessage

    data class Cancel(val requestId: String) : PluginRpcMessage

    data object Ready : PluginRpcMessage
}

class PluginRpcProtocolException(message: String) : Exception(message)

class PluginRpcException(
    val error: PluginRpcError,
    cause: Throwable? = null,
) : Exception(error.message, cause)

/** JSON codec for the small, versioned MessagePort protocol. */
class PluginRpcCodec(
    private val maxMessageBytes: Int = PluginRuntimePolicy.MAX_MESSAGE_BYTES,
    private val json: Json = defaultJson,
) {
    fun encode(message: PluginRpcMessage): String {
        val objectValue =
            when (message) {
                is PluginRpcMessage.Request ->
                    buildJsonObject {
                        put("kind", "request")
                        put("requestId", message.requestId)
                        put("method", message.method)
                        put("params", message.params)
                    }
                is PluginRpcMessage.HostRequest ->
                    buildJsonObject {
                        put("kind", "host_request")
                        put("requestId", message.requestId)
                        put("method", message.method)
                        put("params", message.params)
                    }
                is PluginRpcMessage.Response ->
                    buildJsonObject {
                        put("kind", if (message.hostResponse) "host_response" else "response")
                        put("requestId", message.requestId)
                        message.result?.let { put("result", it) }
                        message.error?.let { error ->
                            put(
                                "error",
                                buildJsonObject {
                                    put("code", error.code)
                                    put("message", error.message)
                                    put("retryable", error.retryable)
                                    put("details", error.details)
                                },
                            )
                        }
                    }
                is PluginRpcMessage.Cancel ->
                    buildJsonObject {
                        put("kind", "cancel")
                        put("requestId", message.requestId)
                    }
                PluginRpcMessage.Ready -> buildJsonObject { put("kind", "ready") }
            }
        val encoded = json.encodeToString(JsonElement.serializer(), objectValue)
        checkSize(encoded)
        return encoded
    }

    fun decode(encoded: String): PluginRpcMessage {
        checkSize(encoded)
        val objectValue =
            try {
                json.parseToJsonElement(encoded).jsonObject
            } catch (error: Throwable) {
                throw PluginRpcProtocolException(
                    "RPC message is not a JSON object: ${error.message}"
                )
            }
        val kind = objectValue.requiredString("kind")
        return when (kind) {
            "request" ->
                PluginRpcMessage.Request(
                    requestId = objectValue.requiredRequestId(),
                    method = objectValue.requiredMethod(),
                    params = objectValue["params"] ?: JsonObject(emptyMap()),
                )
            "host_request" ->
                PluginRpcMessage.HostRequest(
                    requestId = objectValue.requiredRequestId(),
                    method = objectValue.requiredMethod(),
                    params = objectValue["params"] ?: JsonObject(emptyMap()),
                )
            "response" -> objectValue.response(hostResponse = false)
            "host_response" -> objectValue.response(hostResponse = true)
            "cancel" -> PluginRpcMessage.Cancel(objectValue.requiredRequestId())
            "ready" -> PluginRpcMessage.Ready
            else -> throw PluginRpcProtocolException("Unknown RPC message kind: $kind")
        }
    }

    private fun checkSize(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8).size
        if (bytes > maxMessageBytes) {
            throw PluginRpcException(
                PluginRpcError(
                    code = PluginErrorCode.QUOTA_EXCEEDED,
                    message = "RPC message exceeds $maxMessageBytes bytes",
                    retryable = false,
                )
            )
        }
    }

    private fun JsonObject.response(hostResponse: Boolean): PluginRpcMessage.Response {
        val result = this["result"]
        val error =
            this["error"]?.let { value ->
                val errorObject = value.jsonObject
                PluginRpcError(
                    code = errorObject.requiredString("code"),
                    message = errorObject.requiredString("message"),
                    retryable =
                        errorObject["retryable"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.toBooleanStrictOrNull() ?: false,
                    details = errorObject["details"] ?: JsonObject(emptyMap()),
                )
            }
        if (result == null && error == null) {
            throw PluginRpcProtocolException("RPC response must contain result or error")
        }
        if (result != null && error != null) {
            throw PluginRpcProtocolException("RPC response cannot contain both result and error")
        }
        return PluginRpcMessage.Response(
            requestId = requiredRequestId(),
            result = result,
            error = error,
            hostResponse = hostResponse,
        )
    }

    private fun JsonObject.requiredString(key: String): String {
        val value = this[key]?.jsonPrimitive?.contentOrNull
        if (value.isNullOrBlank()) throw PluginRpcProtocolException("RPC field '$key' is missing")
        return value
    }

    private fun JsonObject.requiredRequestId(): String {
        val value = requiredString("requestId")
        if (value.length > PluginRuntimePolicy.MAX_REQUEST_ID_LENGTH) {
            throw PluginRpcProtocolException("RPC requestId is too long")
        }
        return value
    }

    private fun JsonObject.requiredMethod(): String {
        val value = requiredString("method")
        if (value.length > PluginRuntimePolicy.MAX_METHOD_LENGTH) {
            throw PluginRpcProtocolException("RPC method is too long")
        }
        return value
    }

    private companion object {
        val defaultJson = Json {
            explicitNulls = false
            ignoreUnknownKeys = true
            isLenient = false
        }
    }
}

fun interface PluginRpcTransport {
    fun send(message: String)
}

fun interface PluginRpcHostHandler {
    suspend fun handle(method: String, params: JsonElement): JsonElement
}

/**
 * Pending-request state machine shared by the Android transport and JVM tests. It never retries a
 * request implicitly; a timeout or transport failure completes only that request.
 */
class PluginRpcClient(
    private val transport: PluginRpcTransport,
    private val hostHandler: PluginRpcHostHandler? = null,
    private val codec: PluginRpcCodec = PluginRpcCodec(),
    private val maxPending: Int = PluginRuntimePolicy.MAX_PENDING_RPC,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onCallerCancellation: () -> Unit = {},
) : AutoCloseable {
    private val idCounter = AtomicLong(0L)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val hostRequests = ConcurrentHashMap<String, Job>()
    private val ready = CompletableDeferred<Unit>()
    private val stateLock = Any()
    private val closed = AtomicBoolean(false)

    suspend fun awaitReady(timeoutMs: Long = PluginRuntimePolicy.LIGHT_DEADLINE_MS) {
        try {
            withTimeout(timeoutMs) { ready.await() }
        } catch (error: TimeoutCancellationException) {
            throw PluginRpcException(
                PluginRpcError(
                    PluginErrorCode.TIMEOUT,
                    "Plugin bootstrap timed out",
                    retryable = true,
                ),
                error,
            )
        }
    }

    suspend fun call(
        method: String,
        params: JsonElement = JsonObject(emptyMap()),
        timeoutMs: Long = PluginRuntimePolicy.NORMAL_DEADLINE_MS,
    ): JsonElement {
        val requestId = nextRequestId()
        val deferred = CompletableDeferred<JsonElement>()
        synchronized(stateLock) {
            ensureOpen()
            if (pending.size >= maxPending) {
                throw PluginRpcException(
                    PluginRpcError(
                        PluginErrorCode.QUOTA_EXCEEDED,
                        "Plugin pending RPC limit reached",
                    )
                )
            }
            pending[requestId] = deferred
        }
        try {
            send(PluginRpcMessage.Request(requestId, method, params))
        } catch (error: PluginRpcException) {
            pending.remove(requestId, deferred)
            deferred.completeExceptionally(error)
            throw error
        }
        val timeoutJob = scope.launch {
            delay(timeoutMs.coerceAtMost(PluginRuntimePolicy.HARD_DEADLINE_MS))
            if (pending.remove(requestId, deferred)) {
                runCatching { send(PluginRpcMessage.Cancel(requestId)) }
                deferred.completeExceptionally(
                    PluginRpcException(
                        PluginRpcError(
                            PluginErrorCode.TIMEOUT,
                            "Plugin call exceeded its deadline",
                            retryable = true,
                        )
                    )
                )
            }
        }
        try {
            return deferred.await()
        } catch (error: CancellationException) {
            onCallerCancellation()
            throw error
        } finally {
            timeoutJob.cancel()
            if (pending.remove(requestId, deferred)) {
                runCatching { send(PluginRpcMessage.Cancel(requestId)) }
                deferred.cancel()
            }
        }
    }

    fun onMessage(encoded: String) {
        if (closed.get()) return
        val message =
            try {
                codec.decode(encoded)
            } catch (error: Throwable) {
                failAll(
                    PluginRpcException(
                        PluginRpcError(
                            PluginErrorCode.PLUGIN_PROTOCOL,
                            "Malformed message from plugin",
                        ),
                        error,
                    )
                )
                return
            }
        when (message) {
            PluginRpcMessage.Ready -> if (!closed.get()) ready.complete(Unit)
            is PluginRpcMessage.Response -> {
                if (message.hostResponse) return
                val deferred = pending.remove(message.requestId) ?: return
                if (message.error != null) {
                    deferred.completeExceptionally(PluginRpcException(message.error))
                } else {
                    deferred.complete(message.result ?: JsonNull)
                }
            }
            is PluginRpcMessage.HostRequest -> handleHostRequest(message)
            is PluginRpcMessage.Request -> {
                failAll(
                    PluginRpcException(
                        PluginRpcError(
                            PluginErrorCode.PLUGIN_PROTOCOL,
                            "Unexpected request direction",
                        )
                    )
                )
            }
            is PluginRpcMessage.Cancel -> {
                synchronized(stateLock) { hostRequests.remove(message.requestId) }?.cancel()
            }
        }
    }

    fun failAll(error: PluginRpcException) {
        if (!closed.compareAndSet(false, true)) return
        val pendingCalls: List<CompletableDeferred<JsonElement>>
        val hostJobs: List<Job>
        synchronized(stateLock) {
            pendingCalls = pending.values.toList()
            pending.clear()
            hostJobs = hostRequests.values.toList()
            hostRequests.clear()
        }
        pendingCalls.forEach { it.completeExceptionally(error) }
        hostJobs.forEach { it.cancel() }
        ready.completeExceptionally(error)
        scope.cancel()
    }

    override fun close() {
        failAll(
            PluginRpcException(
                PluginRpcError(PluginErrorCode.RUNTIME_TERMINATED, "Plugin runtime was closed")
            )
        )
        scope.cancel()
    }

    private fun handleHostRequest(message: PluginRpcMessage.HostRequest) {
        val handler = hostHandler
        if (handler == null) {
            sendHostError(
                message.requestId,
                PluginRpcError(PluginErrorCode.HOST_UNAVAILABLE, "Host bridge is unavailable"),
            )
            return
        }
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = handler.handle(message.method, message.params)
                    send(PluginRpcMessage.Response(message.requestId, result, hostResponse = true))
                } catch (error: PluginRpcException) {
                    if (!closed.get()) sendHostError(message.requestId, error.error)
                } catch (error: CancellationException) {
                    sendHostError(
                        message.requestId,
                        PluginRpcError(PluginErrorCode.CANCELLED, "Host call cancelled", true),
                    )
                } catch (error: Throwable) {
                    sendHostError(
                        message.requestId,
                        PluginRpcError(
                            PluginErrorCode.HOST_UNAVAILABLE,
                            error.message ?: "Host call failed",
                            true,
                        ),
                    )
                } finally {
                    synchronized(stateLock) { hostRequests.remove(message.requestId) }
                }
            }
        var rejection: PluginRpcError? = null
        synchronized(stateLock) {
            when {
                closed.get() ->
                    rejection =
                        PluginRpcError(
                            PluginErrorCode.RUNTIME_TERMINATED,
                            "Plugin runtime was closed",
                        )
                hostRequests.size >= maxPending ->
                    rejection =
                        PluginRpcError(
                            PluginErrorCode.QUOTA_EXCEEDED,
                            "Plugin pending host RPC limit reached",
                        )
                hostRequests.containsKey(message.requestId) ->
                    rejection =
                        PluginRpcError(PluginErrorCode.PLUGIN_PROTOCOL, "Duplicate host request id")
                else -> hostRequests[message.requestId] = job
            }
        }
        if (rejection != null) {
            job.cancel()
            sendHostError(message.requestId, requireNotNull(rejection))
        } else {
            job.start()
        }
    }

    private fun sendHostError(requestId: String, error: PluginRpcError) {
        runCatching {
                transport.send(
                    codec.encode(
                        PluginRpcMessage.Response(requestId, error = error, hostResponse = true)
                    )
                )
            }
            .onFailure { sendFailure -> failAll(mapTransportError(sendFailure)) }
    }

    private fun send(message: PluginRpcMessage) {
        try {
            transport.send(codec.encode(message))
        } catch (error: Throwable) {
            val mapped = mapTransportError(error)
            failAll(mapped)
            throw mapped
        }
    }

    private fun mapTransportError(error: Throwable): PluginRpcException =
        if (error is PluginRpcException) {
            error
        } else {
            PluginRpcException(
                PluginRpcError(
                    PluginErrorCode.RUNTIME_TERMINATED,
                    "Plugin message transport failed",
                    retryable = true,
                ),
                error,
            )
        }

    private fun nextRequestId(): String = "h-${idCounter.incrementAndGet()}"

    private fun ensureOpen() {
        if (closed.get()) {
            throw PluginRpcException(
                PluginRpcError(PluginErrorCode.RUNTIME_TERMINATED, "Plugin RPC client is closed")
            )
        }
    }
}
