# Active AI Cache Implementation Notes

## Scope

- Persist enhanced page results on disk and consult them before source decoding or inference.
- Keep transient reader/prefetch results separate from user-requested persistent results.
- Let the user start an inclusive global-page range from the reader UI.
- Run the range as a user-triggered foreground WorkManager task with progress notification.
- Support pause, resume, and cancel while preserving completed page results.
- Keep foreground reading higher priority than background range caching.

## Decisions

- Transient results live under `cacheDir`; user-requested results live under `filesDir`.
- PNG is used for the first cache format because it is lossless and supported on every project API
  level.
- One active range task is allowed per comic. Starting a new task replaces the previous task record;
  already cached pages remain reusable.
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
- Bulk caching yields between pages whenever a reader screen is visible. WorkManager retries with a
  short linear backoff and resumes automatically after the reader closes.
- Transient disk cache uses at most 5% of currently usable cache-volume space, capped at 1 GiB. If
  usable space cannot be queried, it falls back to 128 MiB. Budget scans are throttled to once per
  30 seconds; pinned user-requested files are never evicted automatically.
- Transient PNG persistence uses a single-slot writer queue. If reading or inference produces
  results faster than storage can persist them, older transient writes are skipped instead of
  retaining an unbounded bitmap backlog.
- Pinned writes reserve 1% of the storage volume, clamped to 256 MiB–1 GiB, plus the output bitmap's
  uncompressed size. A range task fails clearly instead of filling internal storage.

## Verification

- `git diff --check` passed.
- `./gradlew.bat :app:compileDebugKotlin --offline --console=plain` passed.
- `./gradlew.bat testDebugUnitTest --offline --console=plain` passed: 71 tests, 0 failures, 0
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
- Old pinned source/model revisions are left intact but become unreachable through revisioned keys.
  Automatic stale-revision deletion is deferred because deleting files without an explicit user
  action could discard a range the user intentionally cached.
- Atomic cache replacement fails conservatively when the filesystem does not support atomic moves.
  The old entry and source data are kept; a non-atomic replacement fallback is intentionally not
  used.
