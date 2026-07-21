# Guidelines

## Tech Stack

- **Material 3 Expressive**: An evolution of Google's design system that focuses on emotion-driven
  user experiences through vibrant colors, intuitive motion physics, and adaptive components.

## Project Status

This is an **unreleased** personal hobby project under active development.

## Development Commands

Local: Prefer static checks, Do not run any gradle task unless use ask.

## Remote compile workflow (CircleCI)

Use cloud CI as the **compile machine**, not as a merge gate. Edit locally; build on CircleCI.

Prerequisites: CircleCI CLI logged in (`circleci auth me`), project followed, code **pushed** to
GitHub (cloud checkout is from the remote).

```text
edit → git push → wait for cloud build → read failures → fix → push again
```

Typical commands (repo root, current branch):

```bash
git add -A && git commit -m "wip" && git push
circleci run watch --sha "$(git rev-parse HEAD)" --failfast
# if nothing started from the push webhook, trigger explicitly:
# circleci run trigger && circleci run watch --failfast
```

On failure:

```bash
circleci run list --current-branch
circleci run get <run-id> --json          # find workflow / job ids
circleci job output list <job-id>         # then job output get for the failed step
circleci testresult list <job-id>         # unit test failures, if any
```

What the cloud job runs: `.circleci/config.yml` → `assembleDebug` + `testDebugUnitTest` +
`lintDebug` (resource-tuned for CircleCI `large`).

Do **not** use local `circleci` Docker execute for Android on this machine.

### CI surfaces

| Surface                   | Role                                                  |
|---------------------------|-------------------------------------------------------|
| CircleCI                  | Default remote compile / check (above)                |
| GHA `android-check.yml`   | Manual only (`workflow_dispatch`: check / apk / full) |
| GHA `android-release.yml` | Signed release (tag / manual)                         |
