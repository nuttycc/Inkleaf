# Implementation Notes: Discover Scroll Restoration

## Scope

Implement scroll restoration for the discovery surface without changing navigation structure.
The confirmed behavior is per-context stable comic key plus pixel offset, ViewModel-scoped,
configuration-safe, non-persistent, and bounded by a global LRU of 32 contexts.

## Plan

- Add pure models and resolver logic for discovery scroll contexts, comic grid indices,
  stable anchors, nearest surviving-item fallback, and the bounded LRU store.
- Extend `DiscoverViewModel` with the in-memory scroll store and browse readiness state.
- Replace the unconditional re-entry `scrollToItem(0)` effect with a one-shot restoration
  effect keyed only by the active context.
- Keep an anchored grid hidden until its data reaches a terminal/restorable state, then perform
  the non-animated correction before revealing it; new contexts without an anchor remain visible
  from the top.
- Bind the lazy-grid state to the active context and let the ViewModel anchor remain the only
  restore source; only replay browse alpha animation when its revision increases during the current
  composition.
- Build the actual lazy-grid index map while accounting for structural rows in browse/search.
- Record the first visible comic item continuously with its signed pixel offset.
- Add focused JVM tests for context identity, grid index mapping, anchor resolution, and LRU eviction.
- Verify statically. Gradle tasks require explicit project approval per `AGENTS.md`.

## Deviations

The visual restore intentionally keeps the anchored grid transparent until the target data is
ready, then performs `scrollToItem` while hidden. This is a small timing deviation from pure
constructor-time initialization, chosen because the stable comic anchor can carry a signed offset
when structural rows are above it; the user still sees the first visible frame at the saved position.

The anchored grid uses an in-memory `remember(scrollContext)` state rather than a saveable grid
state. This keeps the ViewModel anchor as the only restore source and preserves the no-cold-start
persistence contract; configuration/navigation restoration still comes from the ViewModel anchor.

Search now exposes an explicit ready state so successful empty results and other terminal states do
not leave an anchored grid hidden. A browse cache-generation conflict is surfaced as a retryable
first-page error and releases the restore gate instead of retrying indefinitely or leaving the page
transparent.

The structural-layout unification suggestion from PR review remains deferred. The existing
browse/search index mapping remains unchanged because it matches the current render branches and a
larger rewrite would expand this fix beyond the requested behavior change.

Beyond the timing deviation above, behavior and navigation stay unchanged. A review suggestion to
wait for the stale-cache refresh was not adopted because the confirmed contract explicitly requires
restoring the current in-memory list first and refreshing in the background without moving the
viewport.

## Verification

- `git diff --check`: passed before and after the visual restoration changes and this pagination fix.
- Read-only root-cause review: confirmed the stale `LazyGridState` capture and the keyed-state fix.
- Second read-only review: no blocking findings. Remaining risk is limited to unverified
  Compose compiler/API compatibility and runtime `LazyGridLayoutInfo.offset` behavior.
- Gradle unit tests and debug compilation: not run. The user chose not to run Gradle, and
  `AGENTS.md` requires explicit permission for every Gradle task.

## Summary

- Added `DiscoverScrollState.kt` with browse/search context keys, stable comic keys, structural
  grid-index mapping, exact/nearest anchor resolution, and a global access-ordered LRU capped at 32.
- Extended `DiscoverViewModel` with in-memory scroll anchors, browse readiness, and explicit
  search readiness for empty/error terminal states.
- Replaced visible post-layout re-entry jumps with a context-keyed, non-animated restore performed
  while anchored grids are hidden; new contexts without anchors still start at the top.
- Added a guard so returning to an existing browse context does not replay its old alpha animation.
- Released the restore gate on cache-generation conflicts with a retryable first-page error.
- Kept pagination, navigation, bottom-tab state saving, layout context, and existing refresh
  semantics unchanged, except that cache-generation conflicts now surface as retryable
  first-page errors.
- Keyed the prefetch derived state and effect by the context-bound `LazyGridState`; otherwise the
  first feed load can leave infinite scrolling observing the old empty grid.
- Added JVM tests for context identity, the generic structural-index mapper used by browse/search,
  exact and fallback anchor resolution, signed offset preservation, and LRU eviction/access order.
- Applied the PR performance cleanup: memoized `gridComicItems`, precomputed grid lookup data, and
  reduced anchor capture to a single pass over visible items without sorting or per-snapshot full-list
  allocations.
