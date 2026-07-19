# Theme Application Transaction

## Scope

- Replace in-place whole-app theme mutation with Android Night Mode plus Activity recreation.
- Keep Light, Dark, and Follow system as explicit user choices.
- Move theme controls into a dedicated editor with a saveable draft and isolated preview.
- Apply every theme field in one DataStore transaction, then recreate at most once.
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
- Theme editing uses an isolated, keyed specimen; the surrounding editor keeps the applied theme.
- AppCompat uses the latest stable release rather than the newer alpha because this dependency is
  foundational lifecycle infrastructure and no alpha-only API is required.

## Progress

- [x] Add AppCompat and the night-mode adapter.
- [x] Freeze root theme settings for each Activity instance.
- [x] Add atomic theme persistence.
- [x] Add dedicated theme editor route and saveable draft.
- [x] Preserve navigation and skip cold-start-only work during recreation.
- [x] Add lightweight unit tests.
- [x] Complete static verification and review.

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

## Verification

- `git diff --check`: passed.
- Static stale-path scan: no per-field theme setters, live root theme Flow collection,
  `UiModeManager.setApplicationNightMode`, or `MODE_NIGHT_AUTO` usage remains.
- AppCompat 1.7.1 confirmed as the latest stable release in Google Maven metadata; 1.8.0-alpha01
  is newer but pre-release.
- Read-only code review: passed after fixing external-open recreation/concurrency handling and
  bounded splash startup gates.
- Local Gradle compilation, unit tests, and lint were not run per repository policy. Run the
  `check` level of `.github/workflows/android-dev-check.yml` after pushing the branch.
- Manual device/emulator validation remains required for repeated Light/Dark/Follow system changes,
  palette-only changes, system-mode changes while following the system, dirty-draft discard, and
  visual whole-window coherence during recreation.
