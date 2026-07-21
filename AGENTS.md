# Guidelines

## Tech Stack

- **Material 3 Expressive**: An evolution of Google's design system that focuses on emotion-driven
  user experiences through vibrant colors, intuitive motion physics, and adaptive components.

## Project Status

This is an **unreleased** personal hobby project under active development.

## Development

- **Gradle:** Do not run ./gradle tasks; prefer static checks. Skip this only when the user has
  declared the machine fast (or ok'd gradle for the session).
- **Test:** Plain JUnit on pure logic only; no instrumented/UI frameworks unless asked; verify UI
  manually.
- **CI**: CI (`.github/workflows/android-check.yml`) runs the full build, tests, and lint.
- **Research:** Verify framework behavior against the exact resolved AndroidX/Compose sources (
  BOM-pinned; sources jar from gradle cache or Google Maven into $TMPDIR/<artifact>-<version>/).
  Never rm — OS temp cleanup owns the lifecycle