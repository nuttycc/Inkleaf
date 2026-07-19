# Expressive Dependency Upgrade

## Scope

- Target Android phones only.
- Adopt useful Kotlin, Compose Material 3, Material Kolor, and Coroutines APIs.
- Modernize existing theme, settings, sheets, app bars, and reader chrome.
- Do not add search, tablet layouts, keyboard/remote features, or unrelated product features.
- Keep reader paging, zoom, loading, and gesture behavior unchanged.

## Decisions

- Use Material 2025 as the recommended color specification while keeping Material 2021 selectable.
- Expose palette style, contrast, AMOLED dark surfaces, and reset controls as advanced theme options.
- Keep whole-app theme changes instantaneous. Animate previews only.
- Route new alpha UI APIs through Inkleaf-owned components where practical.
- Use new Coroutines APIs only where they simplify an existing ownership or state boundary.
- Do not run Gradle locally; verification is delegated to the repository's GitHub Actions workflow.

## Source Checks

- Material Kolor 5.0.0 source: local Gradle cache `material-kolor-android-sources.jar` and
  `material-color-utilities-android-sources.jar`.
- Material 3 1.5.0-alpha24 source: local Gradle cache `material3-android-1.5.0-alpha24-sources.jar`.
- Coroutines 1.11.0 source: local Gradle cache `kotlinx-coroutines-core-jvm-1.11.0-sources.jar`.
- The app module declares Coroutines directly because it imports Coroutines APIs itself. The
  ppocr-sdk module's `implementation` dependency is intentionally not part of the app compile API.
- `TopAppBarDefaults.topAppBarColors` keeps expanded and scrolled container colors independent;
  both are explicitly transparent to preserve instant theme synchronization.
- The new Material 3 `ListItem` overloads own click, selected, and checked semantics. Migrated rows
  no longer layer `Modifier.clickable` or `Modifier.selectable` over the visual component.
- Material Kolor `DynamicMaterialExpressiveTheme` remains scoped to the nested preview with
  `animate = true`; the app root keeps explicit color-scheme generation and instant application.

## Progress

- [x] Dependency versions updated and synced by the developer.
- [x] Theme settings model and persistence.
- [x] Material 2025 color generation and advanced controls.
- [x] Shared Expressive components.
- [x] Screen migrations for settings, shelf sheets, flexible app bars, and form inputs.
- [x] Reader enhancement controls migrated to the new selected list-item API.
- [x] Coroutines API review and read-only deferred ownership boundary.
- [x] Static verification and CI handoff notes.

## Review

- Reuse review consolidated action, information, and single-choice rows behind app-owned helpers.
- API review checked the exact local source signatures for Material Kolor, Material 3, and
  Coroutines and found no definite compile-time mismatch.
- The hue grid was corrected to represent canonical seeds rather than pretending to preview the
  final primary color after spec, style, and contrast processing.
- The advanced reset action now changes only controls shown in its sheet.
- `git diff --check` passes, and changed Kotlin files were scanned for stale imports and obsolete
  comments. No Gradle task was run on the local machine.

## Deviations

- Compose UI test v2 APIs were not added. The project has no Compose UI test harness today; adding
  a new instrumented test architecture would broaden this dependency experiment and consume CI
  quota. Existing unit and instrumented suites remain the conservative verification path.
- Wallpaper-derived schemes cannot accept Material Kolor's spec, contrast, or AMOLED parameters.
  Advanced values are retained but ignored while wallpaper color is active, and the UI states this
  explicitly instead of silently replacing Android's dynamic scheme.
- Low-saturation seeds ignore the selected chromatic palette style and use Neutral. This preserves
  the established ink-gray appearance and avoids purple drift; the code and preview share the same
  resolver so the exception is visible before leaving settings.
