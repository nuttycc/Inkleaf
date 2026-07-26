# Implementation notes: plugin system v1

## Scope

Implement the first usable Inkleaf comic-source plugin slice on the approved
`feature/plugin-runtime-v1` branch. The implementation keeps the decisions from
#40 and its child issues: a `.inkleaf-plugin` ZIP, one plugin per source,
AndroidX JavaScriptEngine 1.1.0, one persistent MessagePort per plugin isolate,
fixed host DTOs, and no plugin-provided Android UI or native code.

## Plan

1. Validate and persist packages through staging, SHA-256 verification, atomic
   version activation, and rollback.
2. Add the shared JavaScriptEngine runtime with feature gating, bootstrap,
   MessagePort JSON-RPC, bounded calls, cancellation, timeout, and health state.
3. Expose the first host capabilities: HTTP, per-plugin KV/cookie state, clock,
   and structured logs.
4. Add fixed describe/action/content DTOs and a catalog facade for installed
   sources.
5. Cover pure protocol, storage, quota, and state-machine behavior with JVM
   tests. Android/WebView behavior remains a user-installed phone check.
6. Add an independently implemented CopyComic-compatible plugin fixture to
   validate the real search, detail, chapter, and page-image path.

## Decisions applied

- The release dependency is `androidx.javascriptengine:javascriptengine:1.1.0`.
- Missing sandbox or required MessagePort/Promise/heap/termination features is
  fail-closed.
- Plugin code is extracted into immutable private app-storage version folders;
  APK/DEX/native libraries and arbitrary root files are rejected.
- A failed invocation is not automatically replayed. A fatal isolate/sandbox
  failure fans out to pending calls and may be recovered on the next explicit
  call.
- UI details use the existing Material 3 Expressive host patterns and do not
  change the runtime or storage architecture.

## Deviations

- The v1 online-content snapshot remains the existing atomic JSON repository;
  no Room entity or schema migration was introduced so the first usable slice
  stays isolated from the reader database.
- Isolate eviction is opportunistic LRU when capacity is requested. There is no
  background time-based reaper; busy isolates are left running and the caller receives
  a retryable quota error when every slot is busy.
- Normal caller cancellation uses the MessagePort cancel envelope and the plugin's
  AbortSignal. Only a deadline or runtime termination force-closes the shared isolate,
  so routine Compose navigation cannot abort unrelated calls or count as a fatal crash.
- AndroidX JavaScriptEngine isolates do not guarantee browser DOM APIs such as
  `AbortController`. The bootstrap uses the native implementation when present and a
  small cooperative fallback otherwise, preserving cancellation without depending on
  a browser global.
- Image loading cannot automatically share the plugin HTTP client's OkHttp CookieJar.
  A plugin must provide any required Cookie/Referer values explicitly in PageImage or
  PageDescriptor headers.
- The v1 host UI exposes discovery and reading only. Declared plugin actions/settings
  are validated and available through the runtime/debug diagnostic entry point, but a
  full action/settings screen is deferred to a later slice.
- The real-source fixture uses the current upstream default API route and platform
  value directly. Endpoint selection, authentication settings, rankings, downloads,
  and background rate limiting remain outside the first online-reading slice.
- The reference CopyComic plugin repository has no declared license. The Inkleaf
  fixture is an independent implementation based on observed public API behavior;
  no source code is copied from that repository.

## Verification log

- Device feature probe completed before implementation: OnePlus PJF110,
  Android 16 / SDK 36, Google WebView 150.0.7871.124; all required features and
  recovery probes passed.
- Local Gradle tasks are intentionally not run, per the implementation
  constraint. GitHub Actions `Android Check (full)` run `30174930927` for
  commit `ff65862` passed in 4m29s, including compilation, JVM tests,
  androidTest compilation, lint, and debug APK packaging/upload.
- Post-fix device acceptance on the same OnePlus passed `describe`, `search fixture`,
  and `host-smoke`. The run covered JS RPC responses plus clock, KV, Cookie, HTTP,
  cancellation-signal propagation, and structured logging. The debug APK was built
  and installed with `install-debug.ps1` using the existing Gradle configuration
  cache; no remote CI was run for this follow-up at the user's request.
- The dependency-free CopyComic fixture unit test passes under Node. It covers
  inline and chunked host HTTP bodies, handle closure, content DTO mapping,
  chapter listing, page ordering, and the `chapter` to `chapter2` 404 fallback.
  Real API and image loading remain pending user-installed phone verification.
