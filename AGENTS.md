# Guidelines

## Project 
Status: This is an **unreleased** personal hobby project under active development.

## Tech Stack
- Use Material 3 Expressive

## Development
- Compile: Prefer static checks, Do not run any gradle task unless use ask.
- Research: Verify framework behavior against the resolved AndroidX/Compose sources (BOM-pinned; sources jar from gradle cache or Google Maven into $TMPDIR/<artifact>-<version>/). Never rm — OS temp cleanup owns the lifecycle.

## Remote compile
Use cloud CI as the **compile machine**, not as a merge gate. Edit locally; build on CI.
