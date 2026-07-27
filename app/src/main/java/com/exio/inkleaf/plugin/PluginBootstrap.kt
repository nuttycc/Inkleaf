package com.exio.inkleaf.plugin

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Builds the single bootstrap evaluation used before all business RPC calls. */
object PluginBootstrap {
    private val json = Json { explicitNulls = false }

    fun script(mainScript: String, portName: String, requiredMethods: Set<String>): String {
        val encodedMainScript = json.encodeToString(String.serializer(), mainScript)
        val encodedPortName = json.encodeToString(String.serializer(), portName)
        val encodedRequiredMethods = json.encodeToString(requiredMethods.sorted())
        return """
            (async function() {
              const port = await android.getNamedPort($encodedPortName);
              const pending = new Map();
              const invocations = new Map();
              let nextId = 0;
              let registration = null;
              let registered = false;

              function send(message) {
                port.postMessage(JSON.stringify(message));
              }

              function errorPayload(error) {
                const code = error && typeof error.code === "string" ? error.code : "PLUGIN_ERROR";
                const message = error && error.message ? String(error.message) : String(error);
                return {
                  code: code,
                  message: message.slice(0, 4096),
                  retryable: Boolean(error && error.retryable),
                  details: { stack: error && error.stack ? String(error.stack).slice(0, 8192) : "" }
                };
              }

              function createAbortController() {
                if (typeof AbortController === "function") return new AbortController();
                let aborted = false;
                const listeners = new Set();
                const signal = Object.freeze({
                  get aborted() { return aborted; },
                  addEventListener: function(type, listener) {
                    if (type === "abort" && typeof listener === "function") listeners.add(listener);
                  },
                  removeEventListener: function(type, listener) {
                    if (type === "abort") listeners.delete(listener);
                  }
                });
                return Object.freeze({
                  signal: signal,
                  abort: function() {
                    if (aborted) return;
                    aborted = true;
                    const event = Object.freeze({ type: "abort", target: signal });
                    const callbacks = Array.from(listeners);
                    listeners.clear();
                    for (const listener of callbacks) {
                      try { listener.call(signal, event); } catch (_) {}
                    }
                  }
                });
              }

              function hostCall(method, params, signal) {
                const requestId = "p-" + (++nextId);
                return new Promise(function(resolve, reject) {
                  let abortListener = null;
                  const pendingCall = {
                    resolve: resolve,
                    reject: reject,
                    cleanup: function() {
                      if (signal && abortListener) signal.removeEventListener("abort", abortListener);
                    }
                  };
                  abortListener = function() {
                    if (!pending.has(requestId)) return;
                    pending.delete(requestId);
                    pendingCall.cleanup();
                    send({ kind: "cancel", requestId: requestId });
                    const error = new Error("Host call cancelled");
                    error.code = "CANCELLED";
                    reject(error);
                  };
                  pending.set(requestId, pendingCall);
                  if (signal) {
                    if (signal.aborted) {
                      abortListener();
                      return;
                    }
                    signal.addEventListener("abort", abortListener, { once: true });
                  }
                  send({ kind: "host_request", requestId: requestId, method: method, params: params || {} });
                });
              }

              const host = Object.freeze({
                call: hostCall,
                http: Object.freeze({
                  request: function(request, signal) { return hostCall("http.request", request, signal); },
                  read: function(request, signal) { return hostCall("http.read", request, signal); },
                  close: function(request, signal) { return hostCall("http.close", request, signal); }
                }),
                kv: Object.freeze({
                  get: function(request, signal) { return hostCall("kv.get", request, signal); },
                  set: function(request, signal) { return hostCall("kv.set", request, signal); },
                  delete: function(request, signal) { return hostCall("kv.delete", request, signal); },
                  keys: function(signal) { return hostCall("kv.keys", {}, signal); }
                }),
                // User-selected source settings are read-only to plugins. Plugin-owned kv storage
                // remains a separate read-write channel. Missing values resolve to null.
                settings: Object.freeze({
                  get: function(id, signal) { return hostCall("settings.get", { id: id }, signal); }
                }),
                cookie: Object.freeze({
                  list: function(signal) { return hostCall("cookie.list", {}, signal); },
                  set: function(request, signal) { return hostCall("cookie.set", request, signal); },
                  clear: function(signal) { return hostCall("cookie.clear", {}, signal); }
                }),
                clock: Object.freeze({
                  now: function(signal) { return hostCall("clock.now", {}, signal); },
                  sleep: function(durationMs, signal) {
                    return hostCall("clock.sleep", { durationMs: durationMs }, signal);
                  }
                }),
                log: function(level, message, fields, signal) {
                  return hostCall("log", { level: level, message: message, fields: fields || {} }, signal);
                }
              });

              const api = {
                host: host,
                register: function(value) {
                  if (registered) throw new Error("PLUGIN_ALREADY_REGISTERED");
                  if (!value || typeof value !== "object") throw new Error("PLUGIN_REGISTER_REQUIRES_OBJECT");
                  registered = true;
                  registration = Object.freeze(Object.assign({}, value));
                }
              };
              Object.defineProperty(globalThis, "inkleaf", {
                value: Object.freeze(api),
                writable: false,
                configurable: false,
                enumerable: false
              });

              port.onmessage = function(event) {
                let message;
                try {
                  message = JSON.parse(event.data);
                } catch (error) {
                  return;
                }
                if (message.kind === "host_response") {
                  const pendingCall = pending.get(message.requestId);
                  if (!pendingCall) return;
                  pending.delete(message.requestId);
                  pendingCall.cleanup();
                  if (message.error) pendingCall.reject(Object.assign(new Error(message.error.message), message.error));
                  else pendingCall.resolve(message.result);
                  return;
                }
                if (message.kind === "cancel") {
                  const controller = invocations.get(message.requestId);
                  if (controller) controller.abort();
                  return;
                }
                if (message.kind !== "request") return;
                const controller = createAbortController();
                invocations.set(message.requestId, controller);
                Promise.resolve().then(function() {
                  if (!registration || typeof registration[message.method] !== "function") {
                    const error = new Error("Plugin method is not registered: " + message.method);
                    error.code = "PLUGIN_PROTOCOL";
                    throw error;
                  }
                  return registration[message.method](message.params || {}, { signal: controller.signal });
                }).then(function(result) {
                  send({ kind: "response", requestId: message.requestId, result: result === undefined ? null : result });
                }).catch(function(error) {
                  send({ kind: "response", requestId: message.requestId, error: errorPayload(error) });
                }).finally(function() {
                  invocations.delete(message.requestId);
                });
              };

              const mainScript = $encodedMainScript;
              (0, eval)(mainScript);
              if (!registered) throw new Error("PLUGIN_NOT_REGISTERED");
              const requiredMethods = $encodedRequiredMethods;
              for (const method of requiredMethods) {
                if (typeof registration[method] !== "function") {
                  throw new Error("PLUGIN_MISSING_METHOD:" + method);
                }
              }
              send({ kind: "ready" });
              return "BOOTSTRAPPED";
            })()
        """
            .trimIndent()
    }
}
