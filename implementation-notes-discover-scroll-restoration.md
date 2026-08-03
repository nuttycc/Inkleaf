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
- Build the actual lazy-grid index map while accounting for structural rows in browse/search.
- Record the first visible comic item continuously with its signed pixel offset.
- Add focused JVM tests for context identity, grid index mapping, anchor resolution, and LRU eviction.
- Verify statically. Gradle tasks require explicit project approval per `AGENTS.md`.

## Deviations

None in behavior or navigation. A review suggestion to wait for stale-cache refresh was not adopted
because the confirmed contract explicitly requires restoring the current in-memory list first and
refreshing in the background without moving the viewport.

## Verification

- `git diff --check`: passed.
- Gradle unit tests and debug compilation: not run. The user chose not to run Gradle, and
  `AGENTS.md` requires explicit permission for every Gradle task.
- Second read-only review: no blocking findings. Remaining risk is limited to unverified
  Compose compiler/API compatibility and runtime `LazyGridLayoutInfo.offset` behavior.

## Summary

- Added `DiscoverScrollState.kt` with browse/search context keys, stable comic keys, structural
  grid-index mapping, exact/nearest anchor resolution, and a global access-ordered LRU capped at 32.
- Extended `DiscoverViewModel` with in-memory scroll anchors and browse readiness state.
- Replaced the unconditional discovery re-entry `scrollToItem(0)` behavior with a context-keyed,
  one-shot, non-animated restore and continuous first-visible-comic anchor recording.
- Kept pagination, navigation, bottom-tab state saving, refresh behavior, and layout context
  semantics unchanged.
- Added JVM tests for context identity, the generic structural-index mapper used by browse/search,
  exact and fallback anchor resolution, signed offset preservation, and LRU eviction/access order.
