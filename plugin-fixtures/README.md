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
repository does not declare a license. It implements online search, browse
feeds, detail, chapter listing, reading, source settings, and route health checks.

Create its installable archive from the repository root:

```powershell
.\.scripts\package-copycomic-plugin.ps1
```

The script writes `plugin-fixtures/dist/copycomic-plugin.zip`. Import and
enable it from the debug plugin diagnostics activity, then search for a comic in
the normal online-source UI and open a chapter to exercise the complete chain.

To package, push, install, and activate the plugin on the only connected ADB
device:

```powershell
.\.scripts\deploy-plugin.ps1 -Plugin copycomic
```

For manual deployment, omit the parameters to choose the plugin, target app,
and (when needed) ADB device from an interactive menu:

```powershell
.\.scripts\deploy-plugin.ps1
```

Pass `-Serial <device-id>` when multiple devices are connected, or
`-PackageId com.exio.inkleaf.debug` to target the debug app. The versioned ZIP
is also retained under `/sdcard/Download/Inkleaf/` for manual inspection.

Its dependency-free transformation and host-body tests can be run separately:

```powershell
node plugin-fixtures/copycomic/main.test.js
```
