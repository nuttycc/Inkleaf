# Theme Application Transaction

## Scope

- Replace in-place whole-app theme mutation with Android Night Mode plus Activity recreation.
- Keep Light, Dark, and Follow system as explicit user choices.
- Move theme controls into a dedicated editor with a saveable draft and isolated preview.
- Auto-save every settled draft as one DataStore transaction, then recreate at most once when the
  user leaves the editor.
- Restore navigation and editor state across configuration recreation.
- Keep local verification static; CI owns compilation, unit tests, lint, APK, and native checks.

## Root Cause

Material 3 components may retain theme-derived colors in internal animations. Replacing the root
`ColorScheme` inside a live Composition therefore mixes instant consumers with components that
animate toward the new colors. Android's normal night-mode path recreates the Activity and gives
every component the final theme as its initial state.

## Decisions

- DataStore remains the only business source of theme preferences.
- All supported Android versions apply night mode through AppCompat DayNight.
- The rendered app theme derives darkness from the Activity `Configuration`, not directly from the
  stored `DarkMode` enum.
- Theme editing uses an isolated, keyed theme for the entire editor so every visible control gives
  immediate coherent feedback without mutating the live app root.
- Draft changes are persisted after a 400 ms debounce. Back flushes the latest draft, exits the
  editor, and crosses the global Activity recreation boundary once.
- AppCompat uses the latest stable release rather than the newer alpha because this dependency is
  foundational lifecycle infrastructure and no alpha-only API is required.

## Progress

- [x] Add AppCompat and the night-mode adapter.
- [x] Freeze root theme settings for each Activity instance.
- [x] Add atomic theme persistence.
- [x] Add dedicated theme editor route and saveable draft.
- [x] Preserve navigation and skip cold-start-only work during recreation.
- [x] Add lightweight unit tests.
- [x] Complete static verification and review for the current uncommitted revision.

## Deviations

- The original plan split night-mode application between `UiModeManager` on API 31+ and
  AppCompat on older versions. Android's public `MODE_NIGHT_AUTO` means sensor/location-based
  automatic mode, not Follow system, and `setApplicationNightMode` persists a second app-level
  preference outside DataStore with no public Follow-system/reset value. The conservative solution
  is to use AppCompat DayNight on every supported version. It provides exact Follow system, Light,
  and Dark semantics while keeping DataStore as the only persistent business source.
- A cold-start night-mode synchronization can recreate the first Activity before its
  `lifecycleScope` jobs finish. Instead of merely skipping those jobs on every recreation,
  cold-start cleanup and shelf warmup now belong to the Application process scope. They run once,
  survive the synchronization recreation, and are not repeated by ordinary configuration changes.
- The synchronization recreation also exposed two startup assumptions outside the original theme
  plan. Pending external-open URIs are now saved across Activity recreation and cleared after an
  idempotent repository import attempt. Theme loading and the optional shelf warmup also have
  failure/timeout exits so the splash screen cannot remain indefinitely when startup I/O fails.
- The first editor design required an explicit Apply button and kept the surrounding editor on the
  previously applied theme. User testing showed that the fixed specimen alone did not provide
  enough immediacy. The editor now previews the draft across its whole keyed subtree and auto-saves
  it, while retaining a single global recreation on exit. This removes Apply/discard controls
  without restoring live root-theme mutation or repeated Activity recreation.
- Keying an open `ModalBottomSheet` would recreate its Dialog and lose scroll or text-input state.
  The conservative split keeps each Dialog shell stable, lifts advanced-sheet scrolling outside the
  keyed visual subtree, and closes the custom picker when a valid color is committed. This preserves
  coherent theme replacement without IME focus loss or Sheet position jumps.

## Verification

- `git diff --check`: passed.
- Static stale-path scan: no per-field theme setters, live root theme Flow collection,
  `UiModeManager.setApplicationNightMode`, or `MODE_NIGHT_AUTO` usage remains.
- AppCompat 1.7.1 confirmed as the latest stable release in Google Maven metadata; 1.8.0-alpha01
  is newer but pre-release.
- `git diff --check` passes, and read-only review confirmed that Back flushes the newest draft,
  Bottom Sheet Dialog shells and interaction state remain stable while theme-sensitive content is
  replaced atomically, and save errors close any covering Sheet before the Snackbar is shown.
- The prior explicit-Apply implementation passed `android-dev-check.yml`; the current auto-save
  editor revision has not yet run CI.
- Local Gradle compilation, unit tests, and lint were not run per repository policy. Run the
  `check` level of `.github/workflows/android-dev-check.yml` after committing and pushing the
  intended theme change set.
- Manual device/emulator validation remains required for repeated Light/Dark/Follow system changes,
  palette-only changes, system-mode changes while following the system, auto-save failure, and
  visual whole-window coherence during editor preview and exit recreation.
