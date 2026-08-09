# Implementation notes: Reader Progress Restore

## Scope

Implement the approved reader-progress recovery plan for local and online readers:

- make automatic resume prefer the durable reading position;
- keep history/bookmark/favorite opens as one-time explicit targets;
- persist pending progress at lifecycle/chapter boundaries;
- add focused JVM coverage for the recovery policy and flush behavior.

## Constraints

- Do not run any Gradle compile task.
- Run only the allowed JVM unit-test task for validation.
- Preserve existing navigation and explicit page-jump behavior.
- Reuse the existing Room/file-backed progress stores; do not add a schema or a second durable store.
- Commit tracked changes in small Conventional Commit batches; do not push.

## Plan

1. Add failing tests at the reader recovery and pending-progress seams.
2. Implement the smallest shared intent/recovery policy and integrate it into local/online routes.
3. Flush pending progress at lifecycle boundaries and chapter transitions without blocking the main thread.
4. Run focused JVM tests, then the full JVM unit-test task and static checks.
5. Review the diff against the approved plan and commit the result.

## Progress

- Investigation confirmed stale route snapshots and a trailing-write loss window in both reader paths.
- Added a navigation-entry restore marker so fresh history/bookmark targets win once, while a
  recreated reader prefers the durable progress record.
- Added one shared restore policy for local pages and online chapter/page selection.
- Replaced the duplicated local/online trailing jobs with a small latest-value write queue that can
  be flushed immediately at lifecycle and chapter boundaries.
- Online chapter commits now persist the committed chapter and start page before the transition is
  considered durable.
- Normal reader exit still performs an application-owned final position write.

## Decisions

- Automatic resume and explicit page jumps are separate intents. A persisted position may supersede a resume snapshot, but never an explicit history/bookmark target during its initial open.
- A lifecycle callback reduces the loss window but is not treated as a guaranteed process-death hook; durable writes remain the source of truth.
- The progress queue stays owned by the ViewModel main-thread scope so submit/flush state remains
  serialized. Repository writes themselves are non-cancellable once started.

## Deviations

- A one-time explicit target is consumed through `NavBackStackEntry.savedStateHandle` instead of a
  full `SavedStateHandle` page snapshot in each ViewModel. This keeps one durable source of truth
  and avoids a second progress store. If Android kills the process before the navigation entry has
  been saved, the explicit target may be replayed once; the lifecycle flush and durable-progress
  preference cover the normal stopped-process restoration path.
- `ProcessLifecycleOwner` remains a best-effort boundary: no callback can guarantee a write before
  an instantaneous OS process kill. The implementation flushes pending progress immediately on
  pause and writes online chapter commits eagerly, but does not claim zero-loss after an arbitrary
  kill between a page event and storage.
- FFF MCP returned `Transport closed` for one targeted `applicationScope` lookup. The indexed
  codebase-memory graph and exact `InkleafApplication.kt` source were used instead; scope and design
  decisions did not change.
- The required parallel Standards/Spec review agents were retried with the available model options,
  but the service returned unsupported-model 404s or temporary high-demand errors. The conservative
  fallback was a fixed-point (`6aa23f1...HEAD`) review in the main thread using the same two axes;
  it found no documented-standard violations, baseline smells requiring changes, missing spec
  requirements, or scope creep.

## Verification log

- Red phase: the focused JVM test initially failed with unresolved
  `ReaderProgressRestorePolicy`/`ReaderProgressWriteQueue` references.
- Focused policy/queue test passed after implementation:
  `:app:testDebugUnitTest --tests com.exio.inkleaf.ui.ReaderProgressRestorePolicyTest`.
- Related JVM regression group passed: `ReaderProgressRestorePolicyTest`,
  `OnlinePageResolutionTest`, `OnlineReaderChapterNavigationTest`,
  `ReadingPositionResolverTest`, and `OnlineContentRepositoryTest`.
- Full JVM unit-test task passed: `:app:testDebugUnitTest --console=plain` reported
  `BUILD SUCCESSFUL in 53s`; the XML results contained 55 suites / 335 tests / 0 failures /
  0 skipped tests.
- Resolved AndroidX Navigation 2.9.8 sources were checked with `library-insight` and the cached
  sources JAR. `NavBackStackEntry.savedStateHandle` is backed by the entry's SavedStateRegistry,
  and `NavBackStackEntryImpl.saveState()` delegates to `performSave`, validating the route marker
  for ordinary navigation state save/restore.
- Fixed-point review (`6aa23f1...HEAD`) result: Standards 0 findings; Spec 0 findings. The remaining
  instantaneous-process-kill limitation is already recorded under Deviations.
- `git diff --check` passed after the initial integration.
- No standalone Gradle compile task, APK build, device test, or push was run. The permitted JVM
  test task necessarily compiled the main and test Kotlin sources.
