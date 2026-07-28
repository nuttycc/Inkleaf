# Inkleaf plugin fixtures

The cross-platform plugin tool requires Python 3.10 or newer and uses only the
Python standard library. Run it without arguments for the interactive package
and deployment menu:

```powershell
# Windows
python .scripts/plugin.py
```

```bash
# macOS and Linux
python3 .scripts/plugin.py
```

For non-interactive use, select an action and plugin explicitly:

```powershell
python .scripts/plugin.py package copycomic
python .scripts/plugin.py deploy copycomic
python .scripts/plugin.py deploy zaimanhua --serial emulator-5554 `
  --package-id com.exio.inkleaf.debug
```

Non-interactive commands never prompt. When several ADB devices are ready,
`deploy` requires `--serial`. Use `--help`, `package --help`, or `deploy --help`
for the complete command reference.

## Source and package layout

Every checked-in plugin uses the same source layout:

```text
plugin-fixtures/<plugin>/
|-- manifest.json
|-- src/
|   `-- main.js
|-- assets/                 # optional
`-- plugin.build.json       # optional, repository build settings
```

`plugin.build.json` is not part of the plugin distribution protocol. It can
declare a shared runtime that the tool prepends to `src/main.js`; `zaimanhua`
uses this to include `plugin-fixtures/shared/runtime.js` without committing a
generated file. The resulting ZIP always has the host-facing layout:

```text
manifest.json
main.js
assets/                     # optional
```

Packages are written below `plugin-fixtures/dist/`. The tool rebuilds each
staging directory from scratch so removed assets cannot remain in a later ZIP.

## Deterministic fixture

`inkleaf-fixture` exercises the v1 `describe`, `search`, `detail`, `chapters`,
`pages`, and `invokeAction` contracts. `invokeAction("host-smoke")` also covers
clock, KV, Cookie, HTTP cancellation plumbing, and structured logging.

```powershell
python .scripts/plugin.py package inkleaf-fixture
python .scripts/plugin.py deploy inkleaf-fixture --package-id com.exio.inkleaf.debug
```

## CopyComic real-source fixture

`copycomic` is an independent implementation of the public CopyComic-compatible
API behavior. It does not copy source code from `Breeze-plugin-copyComic`, whose
repository does not declare a license. It implements online search, browse
feeds, detail, chapter listing, reading, source settings, and route health checks.

Its dependency-free transformation and host-body tests run separately:

```powershell
node plugin-fixtures/copycomic/main.test.js
```

The versioned deployment ZIP is retained under `/sdcard/Download/Inkleaf/` for
manual inspection.
