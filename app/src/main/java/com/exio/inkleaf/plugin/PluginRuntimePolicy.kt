package com.exio.inkleaf.plugin

/** Host-enforced v1 resource and lifecycle policy. */
object PluginRuntimePolicy {
    const val JS_HEAP_BYTES = 32L * 1024L * 1024L
    const val MAX_EVALUATION_RETURN_BYTES = 1 * 1024 * 1024
    const val MAX_MESSAGE_BYTES = 1 * 1024 * 1024
    const val MAX_HTTP_RESPONSE_BYTES = 8 * 1024 * 1024
    const val MAX_IMAGE_BYTES = 32L * 1024L * 1024L
    const val MAX_KV_VALUE_BYTES = 256 * 1024
    const val MAX_KV_NAMESPACE_BYTES = 8 * 1024 * 1024
    const val MAX_PENDING_RPC = 32
    const val MAX_PLUGIN_CONCURRENCY = 2
    const val MAX_PLUGIN_HTTP_CONCURRENCY = 4
    const val MAX_GLOBAL_CONCURRENCY = 4
    const val MAX_GLOBAL_HTTP_CONCURRENCY = 8
    const val MAX_ACTIVE_ISOLATES = 3
    const val LIGHT_DEADLINE_MS = 10_000L
    const val NORMAL_DEADLINE_MS = 30_000L
    const val HARD_DEADLINE_MS = 60_000L
    const val MAX_REQUEST_ID_LENGTH = 128
    const val MAX_METHOD_LENGTH = 128
}

object PluginErrorCode {
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val NETWORK = "NETWORK"
    const val HTTP = "HTTP"
    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    const val TIMEOUT = "TIMEOUT"
    const val CANCELLED = "CANCELLED"
    const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
    const val RUNTIME_TERMINATED = "RUNTIME_TERMINATED"
    const val HOST_UNAVAILABLE = "HOST_UNAVAILABLE"
    const val PLUGIN_PROTOCOL = "PLUGIN_PROTOCOL"
    const val PLUGIN_ERROR = "PLUGIN_ERROR"
    const val PLUGIN_DISABLED = "PLUGIN_DISABLED"
    const val RUNTIME_UNHEALTHY = "RUNTIME_UNHEALTHY"
    const val NOT_FOUND = "NOT_FOUND"
}
