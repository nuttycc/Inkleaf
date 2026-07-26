# Implementation notes: unified reader

## Scope

Unify local and comic-source reading behind one host-owned reader experience while
keeping source loading and persistence source-aware. The first version covers the
existing reader interactions, OCR, page bookmarks, page favorites, progress, and
reading history. It does not add offline-book downloads or cross-source deduplication.

## Plan

1. Add source-aware content, chapter, and page identities plus online user records.
2. Extract a source-neutral reader presentation boundary and preserve local behavior.
3. Adapt plugin pages to the shared reader, preserving request headers and referer.
4. Aggregate online records into the existing Saved and History destinations.
5. Perform static checks and code review; device/Gradle validation remains manual.
6. Add source-neutral chapter navigation so cached plugin chapters can switch inside the shared
   reader without constructing a multi-chapter online volume.

## Decisions applied

- Plugin comics are not represented by synthetic `ComicEntity` rows.
- Local and online routes may bootstrap differently but converge on one reader core.
- Online pages load one chapter at a time; opening a chapter never fetches every page
  from every chapter.
- Page identity never uses a remote image URL. It uses `pageId` when available and a
  revision-bound page index fallback otherwise.
- Comic follow, page bookmark, and page favorite remain distinct user concepts.
- Content snapshots stay in the plugin snapshot repository; durable user records are
  exposed through reader record adapters.
- Local reader identity is the stable file key. Online content, chapter, and page
  identities add only source IDs, with revision-bound page-index fallback.
- Online page bookmarks, page favorite metadata, progress, and completed reading
  sessions are retained in the atomic online content state across plugin availability
  changes.
- Page favorite paths are repository-relative under app-private `filesDir`; metadata is
  published only after the snapshot file exists.
- The reader composable now consumes source-neutral presentation state, feature data,
  and actions. Local reading is adapted at the route boundary without changing its
  existing persistence behavior.
- Plugin chapters are adapted one chapter at a time to `ComicVolume`; full pages,
  thumbnails, OCR, and favorite snapshots all use the descriptor's headers and referer.
- Online reader progress, page bookmarks, durable page favorites, and qualifying reading
  sessions are written by a route-scoped ViewModel and survive plugin unavailability.
- Saved now combines local and online bookmarks and favorites without creating synthetic local
  comic rows. Online favorite cards render the repository-owned durable snapshot files.
- History keeps the existing local Paging stream and prepends persisted online sessions. Online
  history supports exact-page reopen, unavailable-source retention, delete/undo, and clear.
- Chapter navigation is independent of the active `ComicVolume`. Local reading derives chapter
  rows from its multi-chapter volume; online reading supplies cached `ChapterSummary` rows while
  retaining a single lazily loaded `OnlineChapterVolume`.
- Selecting an online chapter stays in the same reader route and reading session. The current
  position is flushed before the old volume is closed, and only the target chapter's page list is
  requested. Per-chapter bookmarks, favorites, thumbnails, and remembered page position are then
  restored for the new chapter.
- Online chapter page counts remain unknown until that chapter is opened. The chapter list omits
  the page-count badge instead of presenting unknown counts as zero or disabling the chapter.

## Deviations

- Task 1 allocates and validates the durable page-favorite path but leaves atomic image
  byte writing to the later online source adapter before metadata publication. The adapter now
  performs that write with a synced temporary file and atomic rename.
- Online records remain in the existing atomic JSON snapshot store for this first
  integration instead of adding a second Room schema and migration.
- A plugin chapter with any missing `pageId` must provide a non-blank chapter revision.
  The first version rejects an unstable chapter instead of using a remote image URL as identity.
- Online history is a separate leading section because local history remains paged. A fully
  interleaved cross-source chronological Paging stream is deferred until both stores share a
  paging boundary.

## Verification log

- `git diff --check` passed for Task 1.
- Identity composition, repository path containment, JSON defaults, and availability
  retention were inspected statically.
- Focused JVM tests were added but not run. Local Gradle execution remains intentionally
  excluded unless explicitly authorized by the user.
- `git diff --check` passed for the shared reader presentation extraction.
- Legacy local-reader model references were checked at the presentation boundary; the
  shared composable no longer depends on Room bookmark or favorite entities.
- The resolved coroutines 1.11.0 `CancellableContinuation` source was checked before using
  the stable cancellation-aware resume API for OkHttp page delivery.
- `git diff --check` passed for the online volume, reader adapter, snapshot removal, and
  focused unit-test additions. Tests were not run.
- Saved/History aggregation, online route reconstruction, repository history actions, and public
  API visibility were inspected statically. Focused tests were added but not run.
- `git diff --check` passed for Saved/History aggregation. Gradle tasks were not run.
- Source-neutral chapter selection now returns a chapter index. Local navigation resolves that
  index through its volume, while online navigation lazily replaces the active chapter volume.
- Focused unit coverage was added for unknown online page counts and invalid/current/unavailable
  chapter selection. Tests were not run because Gradle execution was not authorized.
