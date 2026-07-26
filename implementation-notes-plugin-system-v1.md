# Implementation notes: plugin system v1

## Scope

Implement the first usable Inkleaf comic-source plugin slice on the approved
`feature/plugin-runtime-v1` branch. The implementation keeps the decisions from
#40 and its child issues: a standard `.zip` plugin package, one plugin per source,
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
   validate search, browse feeds, detail, chapter, and page-image paths.

## Decisions applied

- The release dependency is `androidx.javascriptengine:javascriptengine:1.1.0`.
- Missing sandbox or required MessagePort/Promise/heap/termination features is
  fail-closed.
- Plugin code is extracted into immutable private app-storage version folders;
  APK/DEX/native libraries and arbitrary root files are rejected.
- Published plugin artifacts use the standard `.zip` suffix. The root manifest,
  rather than a custom filename extension, identifies and validates a plugin package.
- A failed invocation is not automatically replayed. A fatal isolate/sandbox
  failure fans out to pending calls and may be recovered on the next explicit
  call.
- UI details use the existing Material 3 Expressive host patterns and do not
  change the runtime or storage architecture.
- Host API 1.1 adds the optional `browse` capability. Plugins declare named
  feeds and per-feed filters through `describe`; API 1.0 plugins remain compatible.
- Browse requests use an opaque cursor and return the existing `ComicSummary`
  page shape. Search and browse remain separate protocol operations.
- Browse feed first pages use a disposable memory-and-disk cache keyed by plugin
  ID, active version, feed ID, and canonical filters. Fresh entries are reused for
  15 minutes; stale entries remain visible while one background refresh runs.
- Only first pages are persisted under `cacheDir/plugin-browse`. Later pages stay
  in the navigation-scoped ViewModel and manual refresh always requests a new first page.
- API 1.1 feed filters intentionally expose only single-select descriptors in
  the first UI slice. Other existing filter descriptor types remain available
  to older top-level descriptor consumers but are not advertised as browse UI support.

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
- Plugin UI is split into a search-focused `DiscoverScreen` and a separate `SourcesScreen` (`SourcesRoute`). `InstalledPlugin` in-memory model populates `manifest: PluginManifest?` from active or newest compatible version manifest. User-initiated file/URL installs explicitly pass `activate = true` while `PluginManager` default `activate = false` remains untouched.
- `SourcesScreen` keeps installation secondary to lifecycle management: a top-app-bar add action
  opens a Material bottom sheet containing local ZIP selection and network URL installation.
- The real-source fixture uses the current upstream default API route directly.
  Its first browse slice exposes recommendation, newest, ranking, and category
  feeds with source-specific filters and explicit load-more pagination. Authentication
  settings, downloads, and background rate limiting remain deferred.
- Category browsing intentionally ships a compact useful theme list rather than
  mirroring every upstream theme. The protocol and plugin can add options later
  without changing persisted application data.
- The reference CopyComic plugin repository has no declared license. The Inkleaf
  fixture is an independent implementation based on observed public API behavior;
  no source code is copied from that repository.

## Verification log

- The first plugin-management UI review repaired the unavailable extended icon reference,
  filtered persisted search results against currently active healthy sources, moved plugin
  metadata reads off the main thread, distinguished installed-only packages from activated
  packages, and rejected duplicate search result IDs at the protocol boundary. The authorized
  `:app:compileDebugKotlin` check passed with the existing configuration cache. The new duplicate
  search ID unit test was added but not run because only the compile task was authorized.
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
  all four browse feeds, filters, cursor advancement, de-duplication, newest-route
  fallback, chapter listing, page ordering, and the `chapter` to `chapter2` 404 fallback.
  Real API and image loading remain pending user-installed phone verification.
- The first real-device search exposed an API boundary mismatch: Inkleaf requests
  40 items by default while the CopyComic endpoint rejects limits above 30. The
  source now caps search pages at the reference implementation's stable size of
  21, with a regression test that exercises a host request of 40 items.
- Feed/Browse implementation verification: `node plugin-fixtures/copycomic/main.test.js`
  passed, and the authorized `:app:compileDebugKotlin` task passed with the existing
  configuration cache. JVM unit tests were added for descriptor compatibility,
  feed/filter validation, browse page identity, and request encoding, but were not
  executed because no Gradle test task was authorized.
- Browse-cache verification remained local and lightweight: `git diff --check` passed,
  and the resolved Compose Material Icons Core 1.7.8 sources confirm the new `Refresh`
  icon is available. Repository JVM tests cover fresh/disk hits, key isolation, stale
  fallback, forced refresh, corrupt and unavailable disk storage, and single-flight
  cache misses; they were not executed because no Gradle task was authorized.
- PR #53 CI exposed a stale package-validator fixture that still treated API `1.1` as newer
  after browse support raised the host API to `1.1`. The incompatibility test now derives the
  next minor version from `PluginContract.HOST_API_VERSION` so future host bumps remain covered.
