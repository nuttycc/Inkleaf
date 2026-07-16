# Image Enhancement Model UI — Implementation Notes

## Scope

- Add a data-driven catalog for optional image-enhancement model packages.
- Download, verify, install, retry, and delete model artifacts without modifying comic files.
- Persist the selected enhancement pipeline per comic.
- Add a reader toolbar entry and a compact model-selection bottom sheet.
- Add a settings entry and a full model-management screen.
- Keep the catalog and runtime boundary extensible without hard-coding UI branches per model.

## Initial Catalog

- Real-CUGAN 2x NoSE
- Real-CUGAN 2x Conservative
- Waifu2x UpConv7 Anime 2x

Only artifacts with verified direct download URLs and SHA-256 metadata are included.

## Decisions

- Model packages live under app-private storage and are installed atomically from `.part` files.
- Model packages are excluded from cloud backup and device-transfer backup because they are
  reproducible downloads.
- Downloads are globally shared; the active model selection is stored per comic.
- Catalog entries expose exact model names, family, version, scale, backend, license, source, size,
  capabilities, recommendations, and artifact metadata.
- The reader sheet stays dense and selection-focused; full technical metadata belongs to the model
  manager and detail sheet.
- Download and deletion state is owned by a repository shared by reader and settings view models.
- Upstream artifact URLs are pinned to verified commits so catalog metadata cannot drift when an
  upstream default branch changes.
- Interrupted install commits recover a valid backup on the next refresh; explicit deletion skips
  recovery so a stale backup cannot resurrect a deleted model.
- Cancelling or deleting an active download disconnects its network connection immediately instead
  of waiting for the blocking read timeout.
- Deleting a model resets all per-comic references to `original`; an open reader also falls back
  when it observes that its selected package is no longer installed.
- Download progress is throttled by byte threshold, installed summaries only recompute on installed
  state transitions, and verified hashes are reused across commit/recovery steps.
- Reader and manager surfaces share the same model summary, action-state renderer, and mandatory
  runtime notice so behavior and backend information cannot drift independently.
- The APK embeds the official ncnn `20260526` CPU/Vulkan runtime once and links it statically into
  `libinkleaf_enhancement.so`; downloaded `.param`/`.bin` packages remain external to the APK.
- Real-CUGAN and Waifu2x reuse their pinned upstream ncnn adapters. The current model IDs map to
  explicit preprocessing, blob, scale, padding, tiling, and Real-CUGAN gap-synchronization rules.
- Native sessions keep loaded networks alive across pages. Vulkan is preferred and session creation
  retries on CPU when Vulkan initialization is unavailable.
- Inference is serialized across the app, including input preparation and output allocation, and
  only the most recently selected native session is retained. This bounds peak memory when users
  turn pages or switch models quickly.
- Model eviction uses a process-local tombstone plus generation counter. A request that finishes
  after deletion cannot repopulate the bitmap cache or reuse a destroyed native session.
- Only the current page is enhanced. The original page is shown immediately while inference runs,
  then replaced in place; failures preserve the original page instead of interrupting reading.
- Compressed pages are bounds-decoded and sampled before allocating their inference bitmap. PDF
  pages are rendered directly at the target pixel budget rather than rendered full-size and scaled
  afterward.
- The inference pixel budget accounts for source/prepared overlap, native input/output matrices,
  Java output, and headroom. The bitmap LRU is separately capped and keyed by page identity plus
  model artifact hashes.
- Adapter failures from ncnn input/extract/submit operations are propagated to JNI instead of
  allowing empty tensors to reach later stages. Real-CUGAN gap synchronization remains intact.

## Deviations

- A native inference call cannot be forcibly interrupted after a CPU kernel or Vulkan command has
  started. Changing page/model cancels the coroutine and discards the eventual result, but native
  work may continue until that page call returns. Queued work is cancellable and app-wide inference
  is serialized; mid-tile cancellation would require maintaining a larger fork of both upstream
  adapters.
- The original draft allowed up to one third of the Java heap and a 96 MiB inference cap. Safety
  review showed that this remained aggressive once UI/Coil and native workspace were included, so
  the implementation conservatively uses at most one quarter of the Java heap, capped at 64 MiB,
  with the bitmap cache limited to roughly one twelfth of heap and 24 MiB.
- The vendored adapters retain upstream TTA-only branches, but Inkleaf constructs every session
  with TTA disabled and exposes no TTA setting. Allocation guards were hardened for every reachable
  non-TTA CPU/Vulkan path; the unused TTA-only branches remain outside the supported runtime path.
- Enhanced pages are cached in memory only. Persistent disk caching is deferred to avoid adding a
  second large cache budget and lossless-encoding work before device performance is measured.
- The initial catalog labels repository licensing separately from model-weight licensing. Weight
  licensing remains explicitly unverified rather than being inferred from the code repository.
- Real-ESRGAN AnimeVideo-v3 is omitted from the initial catalog because an official, directly
  downloadable Android-compatible ncnn artifact pair was not verified. Shipping the PyTorch
  checkpoint under an ncnn label would be misleading.

## Verification

- Latest safety pass: `./gradlew.bat :app:compileDebugKotlin --offline` passed.
- Latest safety pass: `./gradlew.bat testDebugUnitTest --offline` passed.
- Latest safety pass:
  `./gradlew.bat ':app:buildCMakeDebug[arm64-v8a][inkleaf_enhancement]' --offline` passed.
- Latest safety pass: `./gradlew.bat :app:compileDebugAndroidTestKotlin --offline` passed.
- Earlier integration validation also compiled all four configured ABIs with
  `:app:externalNativeBuildDebug`; it was intentionally not repeated after the low-spec-machine
  constraint.
- Added a JVM recovery test for the crash window between moving the prior installation to backup
  and committing the newly downloaded directory.
- JVM tests include sampled-decode and low-heap/cap budget coverage in addition to catalog,
  download metadata, and interrupted-install recovery tests.
- A conditional instrumented smoke test covers 32×32 Waifu2x CPU inference when a verified model
  package has been pushed into app-private storage.
- Full APK packaging and runtime execution were not run after the user requested avoiding heavy
  tasks on this low-spec machine. Native compilation and Kotlin/test-source compilation passed.
