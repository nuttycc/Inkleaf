# Inkleaf plugin fixtures

## Deterministic fixture

This checked-in source package exercises the v1 `describe`, `search`, `detail`,
`chapters`, `pages`, and `invokeAction` contracts. `invokeAction("host-smoke")`
also exercises clock, KV, Cookie, HTTP cancellation plumbing, and structured
logging.

Create an installable archive from the repository root:

```powershell
.\.scripts\package-plugin-fixture.ps1
```

The script writes `plugin-fixtures/dist/inkleaf-fixture-plugin.zip`. Import
that file from the debug plugin diagnostics activity, enable it, then use
`describe`, `search fixture`, and `host-smoke`.

## CopyComic real-source fixture

`copycomic` is an independent implementation of the public CopyComic-compatible
API behavior. It does not copy source code from `Breeze-plugin-copyComic`, whose
repository does not declare a license. The first slice intentionally implements
only online search, detail, chapter listing, and reading.

Create its installable archive from the repository root:

```powershell
.\.scripts\package-copycomic-plugin.ps1
```

The script writes `plugin-fixtures/dist/copycomic-plugin.zip`. Import and
enable it from the debug plugin diagnostics activity, then search for a comic in
the normal online-source UI and open a chapter to exercise the complete chain.

Its dependency-free transformation and host-body tests can be run separately:

```powershell
node plugin-fixtures/copycomic/main.test.js
```
