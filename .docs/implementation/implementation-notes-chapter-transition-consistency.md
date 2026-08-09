# Implementation notes: Chapter Transition Consistency

## Summary

Remove chapter ordinals inferred from list positions and make the non-immersive chapter-transition page use the normal reader Chrome structure without exposing actions for an invisible page.

## Implementation

- Chapter-transition headings now show only `下一章` / `上一章` (or the content boundary message); the actual source title remains a separate line and blank titles are omitted.
- Local and online chapter lists keep source titles, use `未命名章节` only for blank titles, and no longer render list positions as chapter metadata.
- Reader bookmarks, saved bookmarks, and history locations fall back to page position only when the chapter title is blank.
- The existing animated bottom Chrome now switches between normal page controls and transition-only status/retry content. Transition mode keeps the same black surface, bottom anchoring, navigation-bar padding, and show/hide animation.
- Page-scoped controls are unavailable on transition pages: the bottom filmstrip/dock is replaced and the top bookmark action is removed.
- Added focused JVM tests for extras not changing displayed chapter titles, transition headings ignoring `chapterIndex`, and normal/transition bottom-control mode selection.

## Verification

- `git diff --check` passed.
- A fresh moderate codebase-memory index completed with `skipped_count = 0` and recognized the new transition Chrome functions.
- Targeted source scans found no remaining production UI expression that derives a chapter label from `chapterIndex + 1`, `chapter.index + 1`, or `index + 1`.
- Focused JVM regression tests were added but not executed locally; see `Deviations`.
- No Gradle compile, APK build, device test, or push will be run.

## Deviations

- FFF MCP returned `Transport closed` twice. The conservative fallback used the required moderate codebase-memory index plus targeted `rg` checks; implementation scope did not change.
- No standalone JVM runner is available (`kotlinc` is not installed and the repository has no non-Gradle Kotlin test script). The regression tests were added but are not run locally because the project prohibits compile tasks and this task did not authorize a Gradle test invocation.
