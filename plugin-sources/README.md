# Inkleaf plugin fixtures

The cross-platform plugin tool requires Python 3.10 or newer. Non-interactive
commands use only the Python standard library. Install the interactive terminal
dependency once to enable arrow-key menus and checkbox selection:

```powershell
# Windows
python -m pip install -r .scripts/requirements.txt
```

```bash
# macOS and Linux
python3 -m pip install -r .scripts/requirements.txt
```

Run the tool without arguments for the interactive package and deployment menu.
Use the arrow keys to move, Space to toggle plugins, and Enter to confirm:

```powershell
# Windows
python .scripts/plugin.py
```

```bash
# macOS and Linux
python3 .scripts/plugin.py
```

For non-interactive use, select actions and plugins explicitly:

```powershell
python .scripts/plugin.py package copycomic
python .scripts/plugin.py package copycomic zaimanhua
python .scripts/plugin.py package --all
python .scripts/plugin.py package --all --output-dir plugin-sources/dist/release
python .scripts/plugin.py deploy copycomic
python .scripts/plugin.py deploy zaimanhua --serial emulator-5554 `
  --package-id com.exio.inkleaf.debug
python .scripts/plugin.py publish zaimanhua
python .scripts/plugin.py publish zaimanhua copycomic --push
```

Non-interactive commands never prompt. When several ADB devices are ready,
`deploy` requires `--serial`. Use `--help`, `package --help`, `deploy --help`, or
`publish --help` for the complete command reference. `--output` is available for
one plugin; `--output-dir` is available when packaging multiple plugins.

## Source and package layout

Every checked-in plugin uses the same source layout:

```text
plugin-sources/<plugin>/
|-- manifest.json
|-- src/
|   `-- main.js
|-- assets/                 # optional
`-- plugin.build.json       # optional, repository build settings
```

`plugin.build.json` is not part of the plugin distribution protocol. It can
declare a shared runtime that the tool prepends to `src/main.js`; `zaimanhua`
uses this to include `plugin-sources/shared/runtime.js` without committing a
generated file. The resulting ZIP always has the host-facing layout:

```text
manifest.json
main.js
assets/                     # optional
```

Packages are written below `plugin-sources/dist/` as
`<plugin>-plugin-v<manifest.version>.zip`. The tool rebuilds each staging
directory from scratch so removed assets cannot remain in a later ZIP. ZIP
entries carry a fixed 1980 timestamp, so rebuilding unchanged source produces
byte-identical packages and a published `sha256` stays checkable.

## Publishing a release

`publish` releases a plugin to the separate `nuttycc/inkleaf-plugins` repository,
which the app reads through its built-in index URL. Clone that repository once,
beside this one, or point `--repo-dir` (or `$INKLEAF_PLUGIN_REPO`) at an existing
clone:

```powershell
git clone https://github.com/nuttycc/inkleaf-plugins.git
```

Without `--push`, `publish` builds the package, merges the index entry, and
prints the resulting diff while changing nothing. It refuses to continue when the
plugin source has uncommitted changes, when the manifest version is not newer than
the version already advertised, when that version exists with different bytes, or
when the repository clone is dirty or behind `origin/main`. With `--push` it writes
the package and index, commits (recording the Inkleaf commit the package was built
from), pushes, then re-downloads each published package and compares its hash.
`--force` overrides the version checks when a re-release is genuinely intended.

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
node plugin-sources/copycomic/main.test.js
```

The versioned deployment ZIP is retained under `/sdcard/Download/Inkleaf/` for
manual inspection.
