package com.exio.inkleaf.debug

import android.content.Context
import android.os.Build
import android.webkit.WebView
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import timber.log.Timber

/**
 * Runs a small, bounded probe against the real WebView-provided JavaScript sandbox.
 *
 * This stays in the debug source set so the diagnostic dependency and entry point cannot leak into
 * release builds. The safe probe is suitable for every run; the heap probe is deliberately separate
 * because AndroidX documents that a heap exhaustion can kill the whole sandbox.
 */
class JavaScriptEngineDiagnosticRunner(
    private val context: Context,
    private val callbackExecutor: Executor,
) {
    private companion object {
        const val TAG = "[JS-DIAG]"
        const val FUTURE_TIMEOUT_SECONDS = 8L
        const val TERMINATION_WAIT_MILLIS = 300L
        const val HEAP_LIMIT_BYTES = 8L * 1024L * 1024L
    }

    private data class Feature(val name: String, val key: String)

    private val requiredFeatures =
        listOf(
            Feature("JS_FEATURE_MESSAGE_PORTS", JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS),
            Feature("JS_FEATURE_PROMISE_RETURN", JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN),
            Feature(
                "JS_FEATURE_ISOLATE_MAX_HEAP_SIZE",
                JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE,
            ),
            Feature(
                "JS_FEATURE_ISOLATE_TERMINATION",
                JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,
            ),
        )

    @Volatile private var activeSandbox: JavaScriptSandbox? = null
    @Volatile private var activeIsolate: JavaScriptIsolate? = null

    fun runSafeProbe(): List<String> =
        runProbe("safe") { lines ->
            appendDeviceInfo(lines)

            val supported = JavaScriptSandbox.isSupported()
            record(lines, "sandbox.isSupported", supported.toString())
            if (!supported) {
                record(
                    lines,
                    "safe.result",
                    "FAIL: WebView does not meet JavaScriptSandbox version floor",
                )
                return@runProbe
            }

            var sandbox: JavaScriptSandbox? = null
            var isolate: JavaScriptIsolate? = null
            var safePass = true
            try {
                val connectedSandbox =
                    await(
                        JavaScriptSandbox.createConnectedInstanceAsync(context),
                        "sandbox.connect",
                    )
                sandbox = connectedSandbox
                activeSandbox = connectedSandbox
                record(lines, "sandbox.connect", "PASS")

                val featureValues = requiredFeatures.associate { feature ->
                    val value = connectedSandbox.isFeatureSupported(feature.key)
                    record(lines, feature.name, value.toString())
                    feature.name to value
                }
                if (!featureValues.values.all { it }) {
                    record(lines, "safe.featureGate", "FAIL: required feature missing")
                    safePass = false
                }

                val connectedIsolate = connectedSandbox.createIsolate()
                isolate = connectedIsolate
                activeIsolate = connectedIsolate
                record(lines, "isolate.create", "PASS")

                safePass =
                    recordEvaluation(
                        lines,
                        "basic.evaluate",
                        connectedIsolate,
                        "'BASIC_OK'",
                        "BASIC_OK",
                    ) && safePass
                safePass =
                    recordEvaluation(
                        lines,
                        "persistent.evaluate.1",
                        connectedIsolate,
                        "globalThis.__inkleafDiag = (globalThis.__inkleafDiag || 0) + 1; String(globalThis.__inkleafDiag)",
                        "1",
                    ) && safePass
                safePass =
                    recordEvaluation(
                        lines,
                        "persistent.evaluate.2",
                        connectedIsolate,
                        "globalThis.__inkleafDiag = (globalThis.__inkleafDiag || 0) + 1; String(globalThis.__inkleafDiag)",
                        "2",
                    ) && safePass
                safePass =
                    recordExpectedJavaScriptFailure(lines, "syntax.error", connectedIsolate, "(") &&
                        safePass
                safePass =
                    recordExpectedJavaScriptFailure(
                        lines,
                        "throw.error",
                        connectedIsolate,
                        "throw new Error('DIAG_THROW')",
                    ) && safePass

                if (featureValues.getValue("JS_FEATURE_PROMISE_RETURN")) {
                    safePass =
                        recordEvaluation(
                            lines,
                            "promise.evaluate",
                            connectedIsolate,
                            "Promise.resolve('PROMISE_OK')",
                            "PROMISE_OK",
                        ) && safePass
                    safePass =
                        recordExpectedJavaScriptFailure(
                            lines,
                            "promise.reject",
                            connectedIsolate,
                            "Promise.reject(new Error('DIAG_REJECT'))",
                        ) && safePass
                } else {
                    record(lines, "promise.evaluate", "SKIP: feature unavailable")
                }

                safePass = recordIsolateParallelProbe(lines, connectedSandbox) && safePass

                if (featureValues.getValue("JS_FEATURE_MESSAGE_PORTS")) {
                    safePass = recordMessagePortProbe(lines, connectedIsolate) && safePass
                } else {
                    record(lines, "messagePort.probe", "SKIP: feature unavailable")
                }

                record(lines, "safe.result", if (safePass) "PASS" else "FAIL")
            } catch (error: Throwable) {
                safePass = false
                record(lines, "safe.result", "FAIL: ${describe(error)}")
            } finally {
                closeQuietly(isolate)
                closeQuietly(sandbox)
                activeIsolate = null
                activeSandbox = null
            }
        }

    fun runTerminationProbe(): List<String> =
        runProbe("termination") { lines ->
            appendDeviceInfo(lines)
            if (!JavaScriptSandbox.isSupported()) {
                record(lines, "termination.result", "SKIP: sandbox unsupported")
                return@runProbe
            }

            var sandbox: JavaScriptSandbox? = null
            var isolate: JavaScriptIsolate? = null
            var terminationPass = false
            try {
                val connectedSandbox =
                    await(
                        JavaScriptSandbox.createConnectedInstanceAsync(context),
                        "sandbox.connect",
                    )
                sandbox = connectedSandbox
                activeSandbox = connectedSandbox
                val supported =
                    connectedSandbox.isFeatureSupported(
                        JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION
                    )
                record(lines, "JS_FEATURE_ISOLATE_TERMINATION", supported.toString())
                if (!supported) {
                    record(lines, "termination.result", "SKIP: feature unavailable")
                    return@runProbe
                }

                val runningIsolate = connectedSandbox.createIsolate()
                isolate = runningIsolate
                activeIsolate = runningIsolate
                val future = runningIsolate.evaluateJavaScriptAsync("while (true) {}")
                Thread.sleep(TERMINATION_WAIT_MILLIS)
                closeQuietly(runningIsolate)
                isolate = null
                activeIsolate = null

                try {
                    await(future, "termination.evaluation")
                    record(lines, "termination.evaluation", "FAIL: infinite loop returned")
                } catch (error: Throwable) {
                    val cause = rootCause(error)
                    terminationPass = cause is IsolateTerminatedException
                    record(
                        lines,
                        "termination.evaluation",
                        if (terminationPass) {
                            "PASS: ${describe(error)}"
                        } else {
                            "FAIL: ${describe(error)}"
                        },
                    )
                }

                val recoveryIsolate = connectedSandbox.createIsolate()
                try {
                    terminationPass =
                        recordEvaluation(
                            lines,
                            "termination.recovery",
                            recoveryIsolate,
                            "'RECOVERY_OK'",
                            "RECOVERY_OK",
                        ) && terminationPass
                } finally {
                    closeQuietly(recoveryIsolate)
                }
                record(lines, "termination.result", if (terminationPass) "PASS" else "FAIL")
            } catch (error: Throwable) {
                terminationPass = false
                record(lines, "termination.result", "FAIL: ${describe(error)}")
            } finally {
                closeQuietly(isolate)
                closeQuietly(sandbox)
                activeIsolate = null
                activeSandbox = null
            }
        }

    fun runHeapProbe(): List<String> =
        runProbe("heap") { lines ->
            appendDeviceInfo(lines)
            if (!JavaScriptSandbox.isSupported()) {
                record(lines, "heap.result", "SKIP: sandbox unsupported")
                return@runProbe
            }

            var sandbox: JavaScriptSandbox? = null
            var isolate: JavaScriptIsolate? = null
            var terminationInfo: TerminationInfo? = null
            var heapEvaluationFailed = false
            var recoveryPass = false
            try {
                val connectedSandbox =
                    await(
                        JavaScriptSandbox.createConnectedInstanceAsync(context),
                        "sandbox.connect",
                    )
                sandbox = connectedSandbox
                activeSandbox = connectedSandbox
                val supported =
                    connectedSandbox.isFeatureSupported(
                        JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE
                    )
                record(lines, "JS_FEATURE_ISOLATE_MAX_HEAP_SIZE", supported.toString())
                if (!supported) {
                    record(lines, "heap.result", "SKIP: feature unavailable")
                    return@runProbe
                }

                val settings =
                    IsolateStartupParameters().apply {
                        setMaxHeapSizeBytes(HEAP_LIMIT_BYTES)
                    }
                val heapIsolate = connectedSandbox.createIsolate(settings)
                isolate = heapIsolate
                activeIsolate = heapIsolate
                val callbackLatch = CountDownLatch(1)
                val callbackRef = arrayOfNulls<TerminationInfo>(1)
                val callback =
                    Consumer<TerminationInfo> { info ->
                        callbackRef[0] = info
                        callbackLatch.countDown()
                    }
                heapIsolate.addOnTerminatedCallback(callbackExecutor, callback)

                val future =
                    heapIsolate.evaluateJavaScriptAsync(
                        """
                        (() => {
                            const chunks = [];
                            while (true) chunks.push(new Array(262144).fill("inkleaf"));
                        })()
                        """
                            .trimIndent()
                    )
                try {
                    await(future, "heap.evaluation")
                    record(lines, "heap.evaluation", "FAIL: allocation loop returned")
                } catch (error: Throwable) {
                    heapEvaluationFailed = rootCause(error) is IsolateTerminatedException
                    record(
                        lines,
                        "heap.evaluation",
                        if (heapEvaluationFailed) {
                            "PASS: ${describe(error)}"
                        } else {
                            "FAIL: ${describe(error)}"
                        },
                    )
                }

                callbackLatch.await(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                terminationInfo = callbackRef[0]
                record(lines, "heap.termination", terminationInfo?.toString() ?: "NO_CALLBACK")

                closeQuietly(heapIsolate)
                isolate = null
                activeIsolate = null
                closeQuietly(connectedSandbox)
                sandbox = null
                activeSandbox = null

                var recoverySandbox: JavaScriptSandbox? = null
                var recoveryIsolate: JavaScriptIsolate? = null
                try {
                    recoverySandbox =
                        await(
                            JavaScriptSandbox.createConnectedInstanceAsync(context),
                            "sandbox.recovery.connect",
                        )
                    recoveryIsolate = recoverySandbox.createIsolate()
                    recoveryPass =
                        recordEvaluation(
                            lines,
                            "heap.recovery",
                            recoveryIsolate,
                            "'RECOVERY_OK'",
                            "RECOVERY_OK",
                        )
                } catch (error: Throwable) {
                    record(lines, "heap.recovery", "FAIL: ${describe(error)}")
                } finally {
                    closeQuietly(recoveryIsolate)
                    closeQuietly(recoverySandbox)
                }

                val terminationPass =
                    terminationInfo?.getStatus() == TerminationInfo.STATUS_MEMORY_LIMIT_EXCEEDED ||
                        terminationInfo?.getStatus() == TerminationInfo.STATUS_SANDBOX_DEAD
                record(
                    lines,
                    "heap.result",
                    if (heapEvaluationFailed && terminationPass && recoveryPass) {
                        "PASS"
                    } else {
                        "FAIL"
                    },
                )
            } catch (error: Throwable) {
                record(lines, "heap.result", "FAIL: ${describe(error)}")
            } finally {
                closeQuietly(isolate)
                closeQuietly(sandbox)
                activeIsolate = null
                activeSandbox = null
            }
        }

    fun close() {
        closeQuietly(activeIsolate)
        closeQuietly(activeSandbox)
        activeIsolate = null
        activeSandbox = null
    }

    private fun runProbe(name: String, block: (MutableList<String>) -> Unit): List<String> {
        val lines = mutableListOf("probe=$name", "startedAt=${System.currentTimeMillis()}")
        try {
            block(lines)
        } catch (error: Throwable) {
            record(lines, "$name.unhandled", "FAIL: ${describe(error)}")
        }
        lines += "endedAt=${System.currentTimeMillis()}"
        return lines
    }

    private fun appendDeviceInfo(lines: MutableList<String>) {
        val webView = WebView.getCurrentWebViewPackage()
        record(lines, "device.model", "${Build.MANUFACTURER} ${Build.MODEL}")
        record(lines, "device.android", "${Build.VERSION.RELEASE} (sdk=${Build.VERSION.SDK_INT})")
        record(lines, "device.abis", Build.SUPPORTED_ABIS.joinToString(","))
        record(
            lines,
            "webview.package",
            webView?.packageName ?: "NONE",
        )
        record(
            lines,
            "webview.version",
            webView?.let { "${it.versionName} (${it.longVersionCode})" } ?: "NONE",
        )
        record(lines, "app.package", context.packageName)
    }

    private fun recordEvaluation(
        lines: MutableList<String>,
        name: String,
        isolate: JavaScriptIsolate,
        code: String,
        expected: String,
    ): Boolean {
        try {
            val actual = await(isolate.evaluateJavaScriptAsync(code), name)
            if (actual == expected) {
                record(lines, name, "PASS: $actual")
                return true
            } else {
                record(lines, name, "FAIL: expected=$expected actual=$actual")
                return false
            }
        } catch (error: Throwable) {
            record(lines, name, "FAIL: ${describe(error)}")
            return false
        }
    }

    private fun recordExpectedJavaScriptFailure(
        lines: MutableList<String>,
        name: String,
        isolate: JavaScriptIsolate,
        code: String,
    ): Boolean {
        try {
            await(isolate.evaluateJavaScriptAsync(code), name)
            record(lines, name, "FAIL: malformed script unexpectedly succeeded")
            return false
        } catch (error: Throwable) {
            val pass = rootCause(error) is JavaScriptException
            record(
                lines,
                name,
                if (pass) {
                    "PASS: ${describe(error)}"
                } else {
                    "FAIL: ${describe(error)}"
                },
            )
            return pass
        }
    }

    private fun recordMessagePortProbe(
        lines: MutableList<String>,
        isolate: JavaScriptIsolate,
    ): Boolean {
        val ready = CountDownLatch(1)
        val echoed = CountDownLatch(1)
        var received = ""
        val client =
            object : MessagePortClient {
                override fun onMessage(message: Message) {
                    if (message.type != Message.TYPE_STRING) return
                    received = message.string
                    when (received) {
                        "READY" -> ready.countDown()
                        "ECHO:PING" -> echoed.countDown()
                    }
                }
            }
        var port: MessagePort? = null
        try {
            port = isolate.createMessageChannel("inkleafDiagnosticPort", callbackExecutor, client)
            val init =
                await(
                    isolate.evaluateJavaScriptAsync(
                        """
                        (async () => {
                            const port = await android.getNamedPort("inkleafDiagnosticPort");
                            port.onmessage = event => port.postMessage("ECHO:" + event.data);
                            port.postMessage("READY");
                            return "PORT_INIT_OK";
                        })()
                        """
                            .trimIndent()
                    ),
                    "messagePort.init",
                )
            if (init != "PORT_INIT_OK" || !ready.await(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                record(lines, "messagePort.probe", "FAIL: init=$init received=$received")
                return false
            }
            port.postMessage(Message.createStringMessage("PING"))
            if (echoed.await(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                record(lines, "messagePort.probe", "PASS: $received")
                return true
            } else {
                record(lines, "messagePort.probe", "FAIL: echo timeout; last=$received")
                return false
            }
        } catch (error: Throwable) {
            record(lines, "messagePort.probe", "FAIL: ${describe(error)}")
            return false
        } finally {
            closeQuietly(port)
        }
    }

    private fun recordIsolateParallelProbe(
        lines: MutableList<String>,
        sandbox: JavaScriptSandbox,
    ): Boolean {
        var first: JavaScriptIsolate? = null
        var second: JavaScriptIsolate? = null
        return try {
            val firstIsolate = sandbox.createIsolate()
            val secondIsolate = sandbox.createIsolate()
            first = firstIsolate
            second = secondIsolate
            val firstFuture = firstIsolate.evaluateJavaScriptAsync("'ISO_ONE'")
            val secondFuture = secondIsolate.evaluateJavaScriptAsync("'ISO_TWO'")
            val firstResult = await(firstFuture, "isolate.parallel.one")
            val secondResult = await(secondFuture, "isolate.parallel.two")
            val firstState =
                await(
                    firstIsolate.evaluateJavaScriptAsync(
                        "globalThis.__inkleafOnly = 'ONE'; String(globalThis.__inkleafOnly)"
                    ),
                    "isolate.state.one",
                )
            val secondState =
                await(
                    secondIsolate.evaluateJavaScriptAsync(
                        "String(typeof globalThis.__inkleafOnly)"
                    ),
                    "isolate.state.two",
                )
            val pass =
                firstResult == "ISO_ONE" &&
                    secondResult == "ISO_TWO" &&
                    firstState == "ONE" &&
                    secondState == "undefined"
            record(
                lines,
                "isolate.parallel",
                if (pass) {
                    "PASS: first=$firstResult second=$secondResult state=$secondState"
                } else {
                    "FAIL: first=$firstResult second=$secondResult state=$secondState"
                },
            )
            pass
        } catch (error: Throwable) {
            record(lines, "isolate.parallel", "FAIL: ${describe(error)}")
            false
        } finally {
            closeQuietly(first)
            closeQuietly(second)
        }
    }

    private fun <T> await(future: Future<T>, name: String): T =
        try {
            future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            throw TimeoutException("$name timed out after ${FUTURE_TIMEOUT_SECONDS}s")
        }

    private fun record(lines: MutableList<String>, key: String, value: String) {
        val line = "$key=$value"
        lines += line
        Timber.tag(TAG).i(line)
    }

    private fun describe(error: Throwable): String {
        val current = rootCause(error)
        return "${current::class.java.simpleName}: ${current.message ?: ""}".trim()
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        while (
            (current is java.util.concurrent.ExecutionException ||
                current is java.util.concurrent.CompletionException) && current.cause != null
        ) {
            current = current.cause!!
        }
        return current
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (error: Throwable) {
            Timber.tag(TAG).w("close failed: %s", describe(error))
        }
    }
}
