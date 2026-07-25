package com.exio.inkleaf.plugin

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRpcTest {
    private val codec = PluginRpcCodec()

    @Test
    fun `codec round trips request and error response`() {
        val request = PluginRpcMessage.Request("h-1", "search", JsonPrimitive("query"))
        assertEquals(request, codec.decode(codec.encode(request)))

        val response = PluginRpcMessage.Response(
            requestId = "h-1",
            error = PluginRpcError(PluginErrorCode.AUTH_REQUIRED, "Login required", retryable = true),
        )
        assertEquals(response, codec.decode(codec.encode(response)))
    }

    @Test
    fun `pending request completes exactly once and late response is ignored`() = runBlocking {
        val transport = QueueTransport()
        val client = PluginRpcClient(
            transport = transport,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        try {
            val result = async(Dispatchers.Default) { client.call("describe", timeoutMs = 1_000L) }
            val request = codec.decode(transport.receive()) as PluginRpcMessage.Request
            client.onMessage(codec.encode(PluginRpcMessage.Response(request.requestId, JsonPrimitive("ok"))))
            assertEquals(JsonPrimitive("ok"), result.await())

            client.onMessage(codec.encode(PluginRpcMessage.Response(request.requestId, JsonPrimitive("late"))))
            assertTrue(true)
        } finally {
            client.close()
        }
    }

    @Test
    fun `host request is dispatched and receives host response`() = runBlocking {
        val transport = QueueTransport()
        val client = PluginRpcClient(
            transport = transport,
            hostHandler = PluginRpcHostHandler { method, _ -> JsonPrimitive("handled:$method") },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        try {
            client.onMessage(codec.encode(PluginRpcMessage.HostRequest("p-1", "clock.now")))
            val response = codec.decode(transport.receive()) as PluginRpcMessage.Response
            assertTrue(response.hostResponse)
            assertEquals(JsonPrimitive("handled:clock.now"), response.result)
        } finally {
            client.close()
        }
    }

    @Test
    fun `timeout clears request and sends cancel without retry`() = runBlocking {
        val transport = QueueTransport()
        val client = PluginRpcClient(
            transport = transport,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        try {
            val result = runCatching { client.call("search", timeoutMs = 25L) }
            val error = result.exceptionOrNull() as PluginRpcException
            assertEquals(PluginErrorCode.TIMEOUT, error.error.code)
            val first = codec.decode(transport.receive())
            assertTrue(first is PluginRpcMessage.Request)
            val cancel = codec.decode(transport.receive())
            assertTrue(cancel is PluginRpcMessage.Cancel)
            assertEquals((first as PluginRpcMessage.Request).requestId, (cancel as PluginRpcMessage.Cancel).requestId)
            delay(20L)
            assertTrue(transport.values.none { codec.decode(it) is PluginRpcMessage.Request })
        } finally {
            client.close()
        }
    }

    @Test
    fun `caller cancellation sends one matching cancel and ignores late response`() = runBlocking {
        val transport = QueueTransport()
        val client = PluginRpcClient(
            transport = transport,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        try {
            val call = launch { client.call("pages", timeoutMs = 1_000L) }
            val request = codec.decode(transport.receive()) as PluginRpcMessage.Request
            call.cancelAndJoin()
            val cancel = codec.decode(transport.receive()) as PluginRpcMessage.Cancel
            assertEquals(request.requestId, cancel.requestId)
            assertTrue(transport.values.none { codec.decode(it) is PluginRpcMessage.Cancel && (codec.decode(it) as PluginRpcMessage.Cancel).requestId != request.requestId })
            client.onMessage(codec.encode(PluginRpcMessage.Response(request.requestId, JsonPrimitive("late"))))
        } finally {
            client.close()
        }
    }

    @Test
    fun `host cancellation cancels handler and returns one cancelled envelope`() = runBlocking {
        val transport = QueueTransport()
        val started = CompletableDeferred<Unit>()
        val client = PluginRpcClient(
            transport = transport,
            hostHandler = PluginRpcHostHandler { _, _ ->
                started.complete(Unit)
                awaitCancellation()
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        try {
            client.onMessage(codec.encode(PluginRpcMessage.HostRequest("p-cancel", "clock.sleep")))
            started.await()
            client.onMessage(codec.encode(PluginRpcMessage.Cancel("p-cancel")))
            val response = codec.decode(transport.receive()) as PluginRpcMessage.Response
            assertTrue(response.hostResponse)
            assertEquals(PluginErrorCode.CANCELLED, response.error?.code)
            delay(20L)
            assertEquals(1, transport.values.count { codec.decode(it) is PluginRpcMessage.Response })
        } finally {
            client.close()
        }
    }

    @Test
    fun `closed client rejects new calls`() = runBlocking {
        val client = PluginRpcClient(QueueTransport())
        client.close()
        val error = runCatching { client.call("describe") }.exceptionOrNull() as PluginRpcException
        assertEquals(PluginErrorCode.RUNTIME_TERMINATED, error.error.code)
    }

    @Test
    fun `transport failure closes client with retryable runtime error`() = runBlocking {
        val client = PluginRpcClient(PluginRpcTransport { throw IllegalStateException("port closed") })

        val first = runCatching { client.call("describe") }.exceptionOrNull() as PluginRpcException
        assertEquals(PluginErrorCode.RUNTIME_TERMINATED, first.error.code)
        assertTrue(first.error.retryable)

        val second = runCatching { client.call("describe") }.exceptionOrNull() as PluginRpcException
        assertEquals(PluginErrorCode.RUNTIME_TERMINATED, second.error.code)
    }

    @Test
    fun `oversized message fails with quota error`() {
        val smallCodec = PluginRpcCodec(maxMessageBytes = 64)
        val result = runCatching {
            smallCodec.encode(PluginRpcMessage.Request("h-1", "search", JsonPrimitive("x".repeat(128))))
        }
        val error = result.exceptionOrNull() as PluginRpcException
        assertEquals(PluginErrorCode.QUOTA_EXCEEDED, error.error.code)
    }

    private class QueueTransport : PluginRpcTransport {
        private val messages = Channel<String>(Channel.UNLIMITED)
        private val sent = CopyOnWriteArrayList<String>()
        val values: List<String> get() = sent.toList()

        override fun send(message: String) {
            sent += message
            messages.trySend(message)
        }

        suspend fun receive(): String = withTimeout(10_000L) { messages.receive() }
    }
}
