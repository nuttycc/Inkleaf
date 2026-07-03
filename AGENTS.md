# Repository Guidelines

## Project Structure & Module Organization

Inkleaf is a single-module Android app. The `app/` module contains all app code and resources. Kotlin source lives in `app/src/main/java/com/exio/inkleaf/`: `data/` holds repositories, Room database types, cache and preference code; `ui/` holds Compose screens, view models, and theme files; `MainActivity.kt` is the entry point. Resources are under `app/src/main/res/`. Local JVM tests belong in `app/src/test/java/`, and Android device/emulator tests belong in `app/src/androidTest/java/`. Shared Gradle versions are in `gradle/libs.versions.toml`.

## Project Status

This is an unreleased personal hobby project under active development. Prefer simple, direct changes over public-release compatibility work. For example, database schema changes do not need long-term migration support unless current local data must be preserved.

## Build, Test, and Development Commands

Run from the repository root.

- `.\gradlew.bat assembleDebug` builds a debug APK.
- `.\gradlew.bat installDebug` installs the debug app on a connected device or emulator.
- `.\gradlew.bat testDebugUnitTest` runs local JVM unit tests.
- `.\gradlew.bat connectedDebugAndroidTest` runs instrumented tests on a connected device or emulator.
- `.\gradlew.bat assembleRelease` builds a minified APK. Signing uses debug signing unless all release signing environment variables are set.

## Coding Style & Naming Conventions

Use Kotlin with 4-space indentation and existing Compose style. Keep focused files grouped by layer: data logic in `data`, UI state and screens in `ui`, and theme code in `ui/theme`. Name Compose functions and screens in PascalCase, for example `ShelfScreen`; name view models as `FeatureViewModel`; name repositories and DAOs by responsibility, such as `ComicRepository` or `FavoriteDao`. Keep resource names lowercase with underscores, for example `ic_download.xml`.

## Testing Guidelines

Use JUnit for local unit tests in `app/src/test/java`. Use AndroidX test, Espresso, and Compose UI test APIs for instrumented tests in `app/src/androidTest/java`. Name test files after the class or feature being tested, and keep test method names descriptive. Run `.\gradlew.bat testDebugUnitTest` before opening a pull request; run `connectedDebugAndroidTest` when changing Compose UI, navigation, storage permissions, or Android framework behavior.

## Commit & Pull Request Guidelines

Recent history uses Conventional Commit-style prefixes such as `feat:`, `fix:`, `ci:`, and `chore:`. Keep commit subjects short and imperative, for example `feat: add folder import flow`. Pull requests should describe the user-facing change, list test commands run, link related issues, and include screenshots or screen recordings for visible UI changes.

## Security & Configuration Tips

Do not commit `local.properties`, keystores, passwords, or build outputs. Keep SDK paths and signing secrets local. Add dependency versions in `gradle/libs.versions.toml`.
