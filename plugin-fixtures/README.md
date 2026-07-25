# Inkleaf fixture plugin

This checked-in source package exercises the v1 `describe`, `search`, `detail`,
`chapters`, `pages`, and `invokeAction` contracts. `invokeAction("host-smoke")`
also exercises clock, KV, Cookie, HTTP cancellation plumbing, and structured
logging.

Create an installable archive from the repository root:

```powershell
.\.scripts\package-plugin-fixture.ps1
```

The script writes `plugin-fixtures/dist/inkleaf-fixture.inkleaf-plugin`. Import
that file from the debug plugin diagnostics activity, enable it, then use
`describe`, `search fixture`, and `host-smoke`.
