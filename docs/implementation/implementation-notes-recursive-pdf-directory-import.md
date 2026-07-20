# Recursive PDF Directory Import — Implementation Notes

## Goal

Treat one user-selected directory as one PDF comic. Discover every PDF below that root,
regardless of how the user split chapters across nested folders.

## Confirmed Behavior

- Recursively flatten all PDF files into one comic.
- Sort globally by PDF display name using natural ordering. Use relative path only as a stable
  tie-breaker.
- Preserve duplicate display names. Give duplicates the shortest unique relative-path title and
  report them after import.
- Accept a file when either its MIME type is `application/pdf` or its display name ends in `.pdf`.
- First import is atomic: scan and validate before creating the comic and chapters.
- A partial scan updates metadata only for currently readable known chapters.
- Remove a missing chapter only after two complete scans fail to find it. Missing chapters stay
  out of the readable chapter list while retained as tombstones.
- Reject an import when any discovered PDF already belongs to another PDF-series comic.
- Keep foreground auto-sync, but make enumeration metadata-only, serial, cancellable, and quiet on
  success. Deduplicate repeated warnings.
- Soft limits: 500 PDFs, 1,000 directories, or 10,000 returned entries. Ask before continuing.
- Hard limits: 2,000 PDFs, 5,000 directories, or 50,000 returned entries. Never continue past them.
- Maximum depth remains 15.
- Rebuilding the local Room database is acceptable.
- PDF page-count probing must open unknown chapters one at a time and close them immediately.
- Rendering keeps only the current/nearby chapter documents in a small LRU and uses the existing
  process-wide Pdfium lock.

## Implementation Tasks

1. Recursive scanner, scan budgets, completeness reporting, stable SAF identity, and path-aware
   metadata.
2. Room entities and repository sync semantics, including overlap detection and two-complete-scan
   deletion.
3. Import confirmation/progress/error UI and approved-budget persistence.
4. PDF page-count probing, bounded open-document LRU, and source-revision metadata cleanup.
5. Focused unit tests and lightweight Kotlin compilation.

## Deviations

### Confirmation restarts the scan

SAF cursors and provider query state cannot be safely retained across a Compose confirmation dialog
or process recreation. Continuing a large scan therefore repeats the metadata-only traversal with
soft limits disabled. This is conservative: no partial database state is committed, and the second
scan observes one coherent provider snapshot.

### Partial scans do not reorder existing chapters

A failed subtree can contain chapters that sort before files already discovered. Reordering from an
incomplete list would make chapter indices unstable. Partial scans therefore do not add, restore,
reorder, or delete chapters until the next complete scan. This is stricter than the original
"only add, never remove" idea, but it keeps persisted indices aligned with the reader's contiguous
chapter positions.

### First import requires a complete scan

The original discussion allowed committing accessible chapters from an incomplete first scan. That
cannot safely validate cross-comic overlap, and the conservative partial-sync rule intentionally
does not insert new chapters. A first import with any inaccessible/loading subtree now commits
nothing and asks the user to retry. Partial scans remain useful only for already-imported series.

## Progress

- [x] Research and product decisions completed.
- [x] Implementation branch created.
- [x] Scanner and data sync implemented and verified.
- [x] UI confirmation and diagnostics implemented and verified.
- [x] PDF resource lifecycle implemented and verified.
- [x] Tests and verification.
- [x] Final review.

## Verification Log

- `./gradlew.bat :app:compileDebugKotlin --offline` — passed after fixing three compile-time API
  mistakes found by the first run.
- `./gradlew.bat testDebugUnitTest --offline` — passed.
- Simplify reviews completed for reuse, code quality, and efficiency. Important findings were fixed:
  partial-scan index safety, whole-sync transactions, confirmation queuing, delayed URI persistence,
  duplicate-title complexity, and shared batching/grouping helpers.
- Final whole-feature review initially found that an incomplete first scan could create an empty
  comic. First import now requires a complete scan, the fix was re-reviewed, and the final
  assessment
  is `READY`.
