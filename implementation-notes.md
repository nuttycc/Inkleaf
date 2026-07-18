# Active AI Cache Implementation Notes

## Agreed Interaction Contract

- Starting a cache task schedules work immediately; reader visibility is not a prerequisite.
- Reader and bulk tasks coexist logically but share one physical inference slot.
- Priority is current page, then next-page prefetch, then bulk cache.
- A page job is unique by comic, source revision, model revision, and page identity. Requests attach
  to the same job and may raise its priority or persistence requirement; duplicate inference is not
  allowed.
- A running bulk inference is not interrupted. If the reader needs another page, the original page
  remains readable until the current bulk page completes, then reader work runs first.
- Bulk tasks keep their starting model. When the reader uses another model, bulk work may process
  one page only after two seconds without a foreground AI request.
- Range processing is ascending, while current or prefetched pages may complete out of order and
  count immediately toward persistent progress.
- Only one active bulk task is allowed globally. Replacing it requires explicit confirmation.
- Pause and cancel retain completed files. Only an explicit cache deletion removes them.
- Low storage pauses the task recoverably. Source or model revision changes expire it and require an
  explicit rebuild flow.
- Background execution continues across navigation, app backgrounding, lock screen, and process
  recreation. Notification permission is required on Android 13 and later.

## Scope

- Persist enhanced page results on disk and consult them before source decoding or inference.
- Keep transient reader/prefetch results separate from user-requested persistent results.
- Let the user start an inclusive global-page range from the reader UI.
- Make the range-cache entry visually distinct from model selection and expose its task state.
- Run the range as a user-triggered foreground WorkManager task with progress notification.
- Support pause, resume, and cancel while preserving completed page results.
- Keep foreground reading higher priority than background range caching.

## Decisions

- Transient results live under `cacheDir`; user-requested results live under `filesDir`.
- PNG is used for the first cache format because it is lossless and supported on every project API
  level.
- One active range task is allowed globally. Starting another task returns the active task without
  replacing it; replacement uses a separate confirmation-aware API and keeps cached files.
- Range endpoints are inclusive, zero-based global page indexes internally and one-based in UI text.
- WorkManager 2.11.2 is used because it is the latest stable release verified from Google's Maven
  metadata on 2026-07-17.
- Foreground work uses the `dataSync` service type because Android documents local file processing
  under that type.
- Database 8→9 uses an additive migration so introducing task persistence does not erase the
  existing library, favorites, or reading progress.
- Reproducible enhanced images are excluded from Auto Backup and device transfer to avoid consuming
  backup quota.
- Native inference is serialized by priority: current reader page, then reader prefetch, then bulk
  cache. A running native page is allowed to finish because the native call is not safely
  interruptible.
- Reader, prefetch, and bulk requests share one page-job coordinator and one physical inference
  slot. A queued page can be promoted without changing its identity, and a late pinned requester is
  finalized before the shared result is published.
- Bulk caching starts immediately while the reader remains open. Current-page and prefetch requests
  outrank queued bulk pages; a native page already running is allowed to finish.
- A bulk page using a different model waits outside the inference dispatcher until there have been
  two seconds without a foreground AI request. Its source bitmap is decoded only after the page
  owns the producer slot.
- Transient disk cache uses at most 5% of currently usable cache-volume space, capped at 1 GiB. If
  usable space cannot be queried, it falls back to 128 MiB. Budget scans are throttled to once per
  30 seconds; pinned user-requested files are never evicted automatically.
- Transient PNG persistence uses a single-slot writer queue. If reading or inference produces
  results faster than storage can persist them, older transient writes are skipped instead of
  retaining an unbounded bitmap backlog.
- Pinned writes reserve 1% of the storage volume, clamped to 256 MiB–1 GiB, plus the output bitmap's
  uncompressed size. A blocked pinned write pauses the range task in a recoverable low-storage
  state.
- The enhancement sheet uses a full-width tonal action row with an icon, explanatory copy, a
  navigation affordance, and inline progress for active cache tasks.
- Active-task recovery is initialized from `Application.onCreate`. Room and WorkManager scheduling
  are reconciled in a non-cancellable section so leaving the reader cannot mislabel scheduled work
  as failed.

## Verification

- `git diff --check` passed.
- `./gradlew.bat :app:compileDebugKotlin --offline --console=plain` passed.
- `./gradlew.bat testDebugUnitTest --offline --console=plain` passed: 86 tests, 0 failures, 0
  errors, 0 skipped.
- No assemble, install, connected test, or clean task was run.

## Deviations

- The initial UI entry is implemented in the reader rather than duplicated on the shelf. The reader
  already has the active model, current page, total pages, and source revision, which avoids a
  second inconsistent range-selection flow. A shelf entry can reuse the same manager later.
- User-requested cache files are stored in `filesDir`, not `cacheDir`. Android may reclaim
  `cacheDir` without user action, which would violate the expected download-like retention
  semantics.
- Native inference cannot be interrupted safely in the middle of a page. Pause, cancel, and
  foreground-reader priority therefore take effect at the next page boundary.
- Bulk source loading and the shared page result are owned by the coordinator once a page is
  submitted. The worker keeps its open book valid through that page boundary; shared output
  bitmaps are borrowed by callers and must not be recycled individually.
- On an uncached current page, an already preloaded original remains visible. If no original is
  retained yet, the reader temporarily shows the memory-bounded inference source instead of
  decoding a second full-size display bitmap. This keeps the page readable while avoiding a
  low-heap spike from full original, inference input, native buffers, and enhanced output at once.
- Old pinned source/model revisions are left intact but become unreachable through revisioned keys.
  Automatic stale-revision deletion is deferred because deleting files without an explicit user
  action could discard a range the user intentionally cached.
- Atomic cache replacement fails conservatively when the filesystem does not support atomic moves.
  The old entry and source data are kept; a non-atomic replacement fallback is intentionally not
  used.
- Database 9→10 resets only range-task metadata while preserving comics, favorites, reading
  progress, and pinned enhanced files. Version 9 stored only a contiguous cursor, so synthesizing
  normalized completion rows could incorrectly claim that out-of-order pages were durable.
