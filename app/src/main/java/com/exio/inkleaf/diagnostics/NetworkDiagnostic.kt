package com.exio.inkleaf.diagnostics

import android.content.Context
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Request

internal data class NetworkDiagnosticMetadata(
    val title: String,
    val metadata: Map<String, String>,
)

internal fun networkDiagnosticMetadata(
    request: Request,
    source: String,
    pluginId: String? = null,
    statusCode: Int? = null,
    durationMs: Long,
    failure: Throwable? = null,
): NetworkDiagnosticMetadata {
    val url = request.url
    val metadata =
        buildMap {
            put("method", request.method)
            put("host", url.host)
            put("path", url.encodedPath)
            put("source", source)
            put("durationMs", durationMs.toString())
            pluginId?.let { put("pluginId", it) }
            statusCode?.let { put("status", it.toString()) }
            failure?.let { put("failureType", it.javaClass.simpleName.ifBlank { "Unknown" }) }
        }
    return NetworkDiagnosticMetadata(
        title = "HTTP ${request.method} ${url.host}${url.encodedPath}",
        metadata = metadata,
    )
}

/**
 * Observes only request metadata. Reporting is deliberately off the OkHttp call path, so a
 * diagnostic I/O failure cannot alter a plugin request or bypass its network policy.
 */
internal object NetworkDiagnosticReporter {
    private val reporterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reporterExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "inkleaf-network-diagnostics").apply { isDaemon = true }
    }

    fun interceptor(
        context: Context,
        source: String,
        pluginId: String? = null,
    ): Interceptor =
        Interceptor { chain ->
            val startedAt = System.nanoTime()
            var statusCode: Int? = null
            var failure: Throwable? = null
            try {
                chain.proceed(chain.request()).also { response -> statusCode = response.code }
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                val durationMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
                val metadata =
                    networkDiagnosticMetadata(
                        request = chain.request(),
                        source = source,
                        pluginId = pluginId,
                        statusCode = statusCode,
                        durationMs = durationMs,
                        failure = failure,
                    )
                runCatching {
                    reporterExecutor.execute {
                        reporterScope.launch {
                            runCatching {
                                DiagnosticRepository.get(context).record(
                                    type = DiagnosticEventType.NETWORK,
                                    title = metadata.title,
                                    metadata = metadata.metadata,
                                )
                            }
                        }
                    }
                }
            }
        }

    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
