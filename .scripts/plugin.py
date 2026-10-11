#!/usr/bin/env python3
"""Package and deploy Inkleaf plugins from one cross-platform entry point.

Plugin source lives at ``plugin-fixtures/<name>/src/main.js``. Optional
``plugin.build.json`` data affects only the build; every ZIP exposes the same
root-level ``manifest.json`` and ``main.js`` contract to the Android host.
"""

from __future__ import annotations

import argparse
import base64
import difflib
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

MINIMUM_PYTHON = (3, 10)
DEFAULT_PACKAGE_ID = "com.exio.inkleaf"
DEBUG_PACKAGE_ID = "com.exio.inkleaf.debug"
DEFAULT_DEVICE_DIRECTORY = "/sdcard/Download/Inkleaf"
PLUGIN_NAME_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
PACKAGE_ID_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_.]*$")
PACKAGE_VERSION_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]*$")
BROADCAST_RESULT_PATTERN = re.compile(
    r'Broadcast completed: result=(?P<code>-?\d+)(?:, data="(?P<data>[^"]*)")?'
)

SCRIPT_DIRECTORY = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIRECTORY.parent
FIXTURES_ROOT = REPOSITORY_ROOT / "plugin-fixtures"
DIST_ROOT = FIXTURES_ROOT / "dist"
INTERACTIVE_REQUIREMENTS = SCRIPT_DIRECTORY / "requirements.txt"
# ZIP cannot store a date before 1980; pinning it keeps builds reproducible.
PACKAGE_TIMESTAMP = (1980, 1, 1, 0, 0, 0)

# Releases live in a separate repository, and the app reads only its main branch
# through raw.githubusercontent.com, so publishing must land on origin/main.
PLUGIN_REPOSITORY_SLUG = "nuttycc/inkleaf-plugins"
PLUGIN_REPOSITORY_BRANCH = "main"
PLUGIN_REPOSITORY_RAW_BASE = f"https://raw.githubusercontent.com/{PLUGIN_REPOSITORY_SLUG}"
PLUGIN_REPOSITORY_INDEX = "index.json"
PLUGIN_REPOSITORY_PACKAGES = "plugins"
SOURCE_PLUGIN_PREFIX = "io.inkleaf.source."
PACKAGE_NAME_PATTERN = re.compile(r"^[A-Za-z0-9._+-]+\.zip$")
# Mirrors PluginRepoContract.SUPPORTED_SCHEMA_VERSION in PluginRepoModels.kt.
PLUGIN_REPOSITORY_SCHEMA_VERSION = 1
INDEX_ROOT_FIELDS = ("schemaVersion", "repoName", "apiVersion")
INDEX_ENTRY_FIELDS = (
    "id",
    "name",
    "version",
    "apiVersion",
    "capabilities",
    "description",
    "author",
    "iconUrl",
    "pkgUrl",
    "sha256",
    "sizeBytes",
)


class PluginToolError(RuntimeError):
    """Reports an expected validation, packaging, or deployment failure."""


def load_questionary():
    """Keeps non-interactive commands free of third-party dependencies."""
    try:
        import questionary
    except ImportError as error:
        raise PluginToolError(
            "Interactive mode requires questionary. Install it with:\n"
            f"  {sys.executable} -m pip install -r {INTERACTIVE_REQUIREMENTS}"
        ) from error
    return questionary


@dataclass(frozen=True)
class Plugin:
    name: str
    root: Path
    source: Path
    manifest_path: Path
    manifest: dict[str, object]
    runtime: Path | None

    @property
    def plugin_id(self) -> str:
        return str(self.manifest["id"])

    @property
    def version(self) -> str:
        return str(self.manifest["version"])

    @property
    def build_strategy(self) -> str:
        return "shared runtime + source" if self.runtime else "standalone source"


@dataclass(frozen=True)
class AdbDevice:
    serial: str
    state: str


def is_link(path: Path) -> bool:
    """Treat Windows junctions and symlinks alike at writable boundaries."""
    if path.is_symlink():
        return True
    try:
        attributes = getattr(path.lstat(), "st_file_attributes", 0)
    except FileNotFoundError:
        return False
    return bool(attributes & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400))


def require_plain_directory(path: Path) -> None:
    if not path.is_dir() or is_link(path):
        raise PluginToolError(f"Directory must exist and must not be a link: {path}")


def require_safe_input(path: Path, root: Path, *, directory: bool = False) -> None:
    root_absolute = Path(os.path.abspath(root))
    path_absolute = Path(os.path.abspath(path))
    try:
        relative = path_absolute.relative_to(root_absolute)
    except ValueError as error:
        raise PluginToolError(f"Input path must remain below {root}: {path}") from error
    current = root_absolute
    for segment in relative.parts:
        current /= segment
        if is_link(current):
            raise PluginToolError(
                f"Plugin input path must not contain a link: {current}"
            )
    if directory and not path_absolute.is_dir():
        raise PluginToolError(f"Plugin input directory does not exist: {path}")
    if not directory and not path_absolute.is_file():
        raise PluginToolError(f"Plugin input file does not exist: {path}")


def read_json_object(path: Path, label: str) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise PluginToolError(f"Missing {label}: {path}") from error
    except (OSError, json.JSONDecodeError) as error:
        raise PluginToolError(f"Invalid {label} at {path}: {error}") from error
    if not isinstance(value, dict):
        raise PluginToolError(f"{label} must contain a JSON object: {path}")
    return value


def load_plugin(directory: Path) -> Plugin:
    name = directory.name
    if not PLUGIN_NAME_PATTERN.fullmatch(name):
        raise PluginToolError(f"Plugin directory has an unsupported name: {name}")
    require_plain_directory(directory)

    manifest_path = directory / "manifest.json"
    source = directory / "src" / "main.js"
    require_safe_input(manifest_path, FIXTURES_ROOT)
    require_safe_input(source, FIXTURES_ROOT)
    manifest = read_json_object(manifest_path, "plugin manifest")
    for field in ("id", "version"):
        if not isinstance(manifest.get(field), str) or not str(manifest[field]).strip():
            raise PluginToolError(
                f"Manifest must declare a non-empty string '{field}': {manifest_path}"
            )
    if not PACKAGE_VERSION_PATTERN.fullmatch(str(manifest["version"])):
        raise PluginToolError(
            f"Manifest version contains characters that are unsafe in a package filename: {manifest_path}"
        )
    build_path = directory / "plugin.build.json"
    runtime: Path | None = None
    if build_path.exists():
        require_safe_input(build_path, FIXTURES_ROOT)
        build = read_json_object(build_path, "plugin build configuration")
        unknown = set(build) - {"runtime"}
        if unknown:
            fields = ", ".join(sorted(unknown))
            raise PluginToolError(
                f"Unsupported plugin.build.json fields for {name}: {fields}"
            )
        runtime_value = build.get("runtime")
        if not isinstance(runtime_value, str) or not runtime_value.strip():
            raise PluginToolError(
                f"plugin.build.json must declare a non-empty 'runtime': {build_path}"
            )
        runtime = directory / runtime_value
        require_safe_input(runtime, FIXTURES_ROOT)
        runtime = Path(os.path.abspath(runtime))

    return Plugin(name, directory, source, manifest_path, manifest, runtime)


def discover_plugins() -> list[Plugin]:
    require_plain_directory(FIXTURES_ROOT)
    plugins: list[Plugin] = []
    failures: list[str] = []
    for directory in sorted(
        FIXTURES_ROOT.iterdir(), key=lambda item: item.name.casefold()
    ):
        if not directory.is_dir() or not (directory / "manifest.json").exists():
            continue
        try:
            plugins.append(load_plugin(directory))
        except (OSError, UnicodeError, PluginToolError, zipfile.BadZipFile) as error:
            failures.append(str(error))
    if failures:
        raise PluginToolError(
            "Invalid plugin directories:\n  - " + "\n  - ".join(failures)
        )
    if not plugins:
        raise PluginToolError(f"No plugins found below {FIXTURES_ROOT}")
    return plugins


def find_plugin(name: str, plugins: list[Plugin] | None = None) -> Plugin:
    if not PLUGIN_NAME_PATTERN.fullmatch(name):
        raise PluginToolError(f"Plugin must be a simple directory name: {name}")
    available = plugins if plugins is not None else discover_plugins()
    match = next((plugin for plugin in available if plugin.name == name), None)
    if match:
        return match
    names = ", ".join(plugin.name for plugin in available)
    raise PluginToolError(f"Unknown plugin '{name}'. Available plugins: {names}")


def ensure_safe_directory(path: Path, root: Path) -> None:
    root.mkdir(parents=True, exist_ok=True)
    if not root.is_dir() or is_link(root):
        raise PluginToolError(f"Output root must be a plain directory: {root}")
    root_absolute = Path(os.path.abspath(root))
    path_absolute = Path(os.path.abspath(path))
    try:
        relative = path_absolute.relative_to(root_absolute)
    except ValueError as error:
        raise PluginToolError(f"Path must remain below {root}: {path}") from error
    current = root_absolute
    for segment in relative.parts:
        current /= segment
        if current.exists() and (not current.is_dir() or is_link(current)):
            raise PluginToolError(
                f"Output directory must not contain a link: {current}"
            )
        current.mkdir(exist_ok=True)


def resolve_output_path(plugin: Plugin, value: str | None) -> Path:
    candidate = (
        Path(value)
        if value
        else Path("plugin-fixtures/dist") / versioned_package_name(plugin)
    )
    if not candidate.is_absolute():
        candidate = REPOSITORY_ROOT / candidate
    candidate = Path(os.path.abspath(candidate))
    if candidate.suffix.lower() != ".zip":
        raise PluginToolError("Output path must use the .zip extension")
    try:
        candidate.relative_to(Path(os.path.abspath(DIST_ROOT)))
    except ValueError as error:
        raise PluginToolError(f"Output path must remain below {DIST_ROOT}") from error
    build_root = Path(os.path.abspath(DIST_ROOT / "build"))
    if candidate == build_root or build_root in candidate.parents:
        raise PluginToolError(
            f"Output path must not be inside the staging directory: {build_root}"
        )
    ensure_safe_directory(candidate.parent, DIST_ROOT)
    if candidate.exists() and (candidate.is_dir() or is_link(candidate)):
        raise PluginToolError(
            f"Output file must not be a directory or link: {candidate}"
        )
    return candidate


def versioned_package_name(plugin: Plugin) -> str:
    return f"{plugin.name}-plugin-v{plugin.version}.zip"


def output_path_in_directory(plugin: Plugin, directory: str) -> str:
    output_directory = Path(directory)
    if not output_directory.is_absolute():
        output_directory = REPOSITORY_ROOT / output_directory
    return str(output_directory / versioned_package_name(plugin))


def copy_assets(source: Path, destination: Path) -> None:
    for item in source.rglob("*"):
        if is_link(item):
            raise PluginToolError(f"Plugin assets must not contain links: {item}")
        relative = item.relative_to(source)
        target = destination / relative
        if item.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        elif item.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(item, target)


def stage_plugin(plugin: Plugin) -> Path:
    build_root = DIST_ROOT / "build"
    ensure_safe_directory(build_root, DIST_ROOT)
    staging = build_root / plugin.name
    if staging.exists():
        if is_link(staging) or not staging.is_dir():
            raise PluginToolError(
                f"Build directory must be a plain directory: {staging}"
            )
        shutil.rmtree(staging)
    staging.mkdir()

    shutil.copy2(plugin.manifest_path, staging / "manifest.json")
    source_text = plugin.source.read_text(encoding="utf-8")
    if plugin.runtime:
        runtime_text = plugin.runtime.read_text(encoding="utf-8")
        generated = (
            "// GENERATED FILE -- do not edit.\n"
            f"// Built from plugin-fixtures/{plugin.name}/src/main.js.\n"
            "(function () {\n"
            '  "use strict";\n'
            f"{runtime_text}\n{source_text}\n"
            "})();\n"
        )
        (staging / "main.js").write_text(generated, encoding="utf-8", newline="\n")
    else:
        shutil.copy2(plugin.source, staging / "main.js")

    assets = plugin.root / "assets"
    if assets.exists():
        require_safe_input(assets, FIXTURES_ROOT, directory=True)
        copy_assets(assets, staging / "assets")
    return staging


def package_plugin(plugin: Plugin, output_value: str | None = None) -> Path:
    print(f"[info] Packaging {plugin.name} v{plugin.version} ({plugin.build_strategy})")
    output = resolve_output_path(plugin, output_value)
    staging = stage_plugin(plugin)
    temporary = output.with_name(f".{output.name}.{uuid.uuid4().hex}.tmp")
    try:
        with zipfile.ZipFile(
            temporary, "w", compression=zipfile.ZIP_DEFLATED
        ) as archive:
            entries = [staging / "manifest.json", staging / "main.js"]
            assets = staging / "assets"
            if assets.is_dir():
                entries.extend(path for path in assets.rglob("*") if path.is_file())
            for path in sorted(entries, key=lambda item: item.as_posix()):
                # Fixed timestamps keep a rebuild of the same source byte-identical, which is
                # what lets an index sha256 assert something about plugin content at all.
                entry = zipfile.ZipInfo(
                    path.relative_to(staging).as_posix(), PACKAGE_TIMESTAMP
                )
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = 0o644 << 16
                archive.writestr(entry, path.read_bytes())
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"[ok] Created {output}")
    return output


def package_plugins(
    plugins: Sequence[Plugin],
    *,
    output: str | None = None,
    output_directory: str | None = None,
) -> list[Path]:
    if not plugins:
        raise PluginToolError("Select at least one plugin to package")
    if output and len(plugins) != 1:
        raise PluginToolError("--output can only be used when packaging one plugin")
    if output_directory and len(plugins) == 1:
        raise PluginToolError(
            "--output-dir can only be used when packaging multiple plugins"
        )

    packages: list[Path] = []
    failures: list[str] = []
    for plugin in plugins:
        plugin_output = output
        if output_directory:
            plugin_output = output_path_in_directory(plugin, output_directory)
        try:
            packages.append(package_plugin(plugin, plugin_output))
        except (OSError, UnicodeError, PluginToolError, zipfile.BadZipFile) as error:
            failures.append(f"{plugin.name}: {error}")
    if failures:
        summary = "\n  - ".join(failures)
        succeeded = ", ".join(path.name for path in packages) or "none"
        raise PluginToolError(
            f"Some plugins failed to package (succeeded: {succeeded}):\n  - {summary}"
        )
    return packages


def select_plugins(names: Sequence[str], available: list[Plugin]) -> list[Plugin]:
    selected: list[Plugin] = []
    seen: set[str] = set()
    for name in names:
        if name not in seen:
            selected.append(find_plugin(name, available))
            seen.add(name)
    return selected


def run_git(
    repository: Path, arguments: list[str], *, check: bool = True
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["git", *arguments],
        cwd=str(repository),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if check and result.returncode != 0:
        summary = result.stdout.strip() or "\n".join(arguments)
        raise PluginToolError(f"Git command failed ({result.returncode}): {summary}")
    return result


def repository_slug(remote_url: str) -> str:
    match = re.search(
        r"[/:](?P<owner>[^/]+)[/:](?P<repo>[^/]+?)(?:\.git)?/?$", remote_url
    )
    if not match:
        raise PluginToolError(f"Cannot read a GitHub slug from remote: {remote_url}")
    return f"{match.group('owner')}/{match.group('repo')}"


def resolve_plugin_repository(value: str | None) -> Path:
    configured = value or os.environ.get("INKLEAF_PLUGIN_REPO")
    candidate = (
        Path(configured)
        if configured
        else REPOSITORY_ROOT.parent / PLUGIN_REPOSITORY_SLUG.rsplit("/", 1)[1]
    )
    candidate = Path(os.path.abspath(candidate))
    if not candidate.is_dir():
        raise PluginToolError(
            f"Plugin repository clone not found: {candidate}\n"
            "Clone it once, or point --repo-dir at an existing clone:\n"
            f'  git clone https://github.com/{PLUGIN_REPOSITORY_SLUG}.git "{candidate}"'
        )
    require_plain_directory(candidate)
    remote = run_git(candidate, ["remote", "get-url", "origin"], check=False).stdout.strip()
    if not remote or repository_slug(remote) != PLUGIN_REPOSITORY_SLUG:
        raise PluginToolError(
            f"{candidate} is not a clone of {PLUGIN_REPOSITORY_SLUG}"
            f" (origin: {remote or 'none'})"
        )
    if not (candidate / PLUGIN_REPOSITORY_INDEX).is_file():
        raise PluginToolError(
            f"Plugin repository has no {PLUGIN_REPOSITORY_INDEX}: {candidate}"
        )
    return candidate


def prepare_plugin_repository(repository: Path) -> None:
    """A package published off a side branch would never be served by its raw URL."""
    branch = run_git(repository, ["rev-parse", "--abbrev-ref", "HEAD"]).stdout.strip()
    if branch != PLUGIN_REPOSITORY_BRANCH:
        raise PluginToolError(
            f"Plugin repository must be on {PLUGIN_REPOSITORY_BRANCH}, found {branch}: {repository}"
        )
    if run_git(repository, ["status", "--porcelain"], check=False).stdout.strip():
        raise PluginToolError(f"Plugin repository has uncommitted changes: {repository}")
    run_git(repository, ["fetch", "origin", branch])
    local = run_git(repository, ["rev-parse", "HEAD"]).stdout.strip()
    remote = run_git(repository, ["rev-parse", f"origin/{branch}"]).stdout.strip()
    if local != remote:
        raise PluginToolError(
            f"Plugin repository does not match origin/{branch}; sync it first: {repository}"
        )


def require_committed_source(plugin: Plugin) -> str:
    """Recorded per release: copycomic 0.4.3 shipped from a dirty tree and matched no commit."""
    relative = plugin.root.relative_to(REPOSITORY_ROOT).as_posix()
    status = run_git(
        REPOSITORY_ROOT, ["status", "--porcelain", "--", relative], check=False
    ).stdout.strip()
    if status:
        raise PluginToolError(
            f"{relative} has uncommitted changes; commit them before publishing {plugin.name}"
        )
    return run_git(REPOSITORY_ROOT, ["rev-parse", "HEAD"]).stdout.strip()


def version_key(value: str) -> tuple[int, ...]:
    parts = value.split(".")
    if not parts or not all(part.isdigit() for part in parts):
        raise PluginToolError(f"Cannot compare plugin version '{value}' (expected dotted numbers)")
    return tuple(int(part) for part in parts)


def build_index_entry(
    plugin: Plugin, package_name: str, package: bytes
) -> dict[str, object]:
    manifest = plugin.manifest
    entry: dict[str, object] = {
        "id": plugin.plugin_id,
        "name": str(manifest.get("name") or plugin.name),
        "version": plugin.version,
    }
    for field in ("apiVersion", "capabilities", "description"):
        if manifest.get(field) is not None:
            entry[field] = manifest[field]
    author = manifest.get("author")
    if isinstance(author, dict):
        author = author.get("name")
    if isinstance(author, str) and author.strip():
        entry["author"] = author
    entry["pkgUrl"] = (
        f"{PLUGIN_REPOSITORY_RAW_BASE}/{PLUGIN_REPOSITORY_BRANCH}"
        f"/{PLUGIN_REPOSITORY_PACKAGES}/{package_name}"
    )
    entry["sha256"] = hashlib.sha256(package).hexdigest()
    entry["sizeBytes"] = len(package)
    return entry


def load_plugin_index(repository: Path) -> tuple[str, dict[str, object]]:
    path = repository / PLUGIN_REPOSITORY_INDEX
    try:
        raw = path.read_text(encoding="utf-8")
        index = json.loads(raw)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise PluginToolError(f"Invalid plugin repository index at {path}: {error}") from error
    if not isinstance(index, dict):
        raise PluginToolError(f"Plugin repository index must be a JSON object: {path}")
    if index.get("schemaVersion") != PLUGIN_REPOSITORY_SCHEMA_VERSION:
        raise PluginToolError(
            f"Unsupported plugin index schemaVersion: {index.get('schemaVersion')}"
        )
    if not isinstance(index.get("plugins"), list) or not index["plugins"]:
        raise PluginToolError(f"Plugin repository index lists no plugins: {path}")
    return raw, index


def index_entries(index: dict[str, object]) -> list[dict[str, object]]:
    entries = [entry for entry in index["plugins"] if isinstance(entry, dict)]
    if len(entries) != len(index["plugins"]):
        raise PluginToolError("Every plugin index entry must be a JSON object")
    return entries


def verify_index_packages(
    repository: Path, entries: Sequence[dict[str, object]], pending: dict[str, bytes]
) -> None:
    seen: set[str] = set()
    for entry in entries:
        plugin_id = str(entry.get("id", ""))
        package_url = str(entry.get("pkgUrl", ""))
        required = (plugin_id, str(entry.get("name", "")), str(entry.get("version", "")))
        if not all(value.strip() for value in required) or not package_url.startswith(
            "https://"
        ):
            raise PluginToolError(
                f"Plugin index entry is missing a required field: {plugin_id or '<unnamed>'}"
            )
        if plugin_id in seen:
            raise PluginToolError(f"Duplicate plugin index entry: {plugin_id}")
        seen.add(plugin_id)
        package_name = package_url.rsplit("/", 1)[-1]
        if not PACKAGE_NAME_PATTERN.fullmatch(package_name):
            raise PluginToolError(f"Plugin index entry has an unusable package name: {package_url}")
        if package_name in pending:
            data = pending[package_name]
        else:
            path = repository / PLUGIN_REPOSITORY_PACKAGES / package_name
            if not path.is_file():
                raise PluginToolError(f"Plugin index entry has no package file: {path}")
            data = path.read_bytes()
        if str(entry.get("sha256", "")) != hashlib.sha256(data).hexdigest():
            raise PluginToolError(f"Plugin index sha256 does not match {package_name}")
        if entry.get("sizeBytes") != len(data):
            raise PluginToolError(f"Plugin index sizeBytes does not match {package_name}")


def merge_index_entry(entries: list[dict[str, object]], entry: dict[str, object]) -> str:
    for position, existing in enumerate(entries):
        if existing.get("id") == entry["id"]:
            entries[position] = entry
            return "updated"
    # The fixture source is a development tool, so it stays below the real sources.
    insert_at = next(
        (
            position
            for position, existing in enumerate(entries)
            if not str(existing.get("id", "")).startswith(SOURCE_PLUGIN_PREFIX)
        ),
        len(entries),
    )
    entries.insert(insert_at, entry)
    return "added"


def render_index_entry(entry: dict[str, object]) -> str:
    fields = [field for field in INDEX_ENTRY_FIELDS if field in entry]
    fields += [field for field in entry if field not in fields]
    lines: list[str] = []
    for field in fields:
        value = entry[field]
        if isinstance(value, list) and all(isinstance(item, str) for item in value):
            rendered = "[" + ", ".join(json.dumps(item, ensure_ascii=False) for item in value) + "]"
        else:
            rendered = json.dumps(value, ensure_ascii=False)
        lines.append(f"      {json.dumps(field)}: {rendered}")
    return "    {\n" + ",\n".join(lines) + "\n    }"


def render_index(index: dict[str, object], entries: Sequence[dict[str, object]]) -> str:
    if not entries:
        raise PluginToolError("The plugin index must list at least one plugin")
    fields = [field for field in INDEX_ROOT_FIELDS if field in index]
    fields += [field for field in index if field not in fields and field != "plugins"]
    chunks = [
        f"  {json.dumps(field)}: {json.dumps(index[field], ensure_ascii=False)}"
        for field in fields
    ]
    rendered_entries = ",\n".join(render_index_entry(entry) for entry in entries)
    chunks.append(f"  \"plugins\": [\n{rendered_entries}\n  ]")
    return "{\n" + ",\n".join(chunks) + "\n}\n"


def fetch_https(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "inkleaf-plugin-tool"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.read()
    except (urllib.error.URLError, OSError) as error:
        raise PluginToolError(f"Download failed: {url} ({error})") from error


def verify_published(entries: Sequence[dict[str, object]]) -> None:
    """raw.githubusercontent.com and the app both cache, so a stale read is a note, not a failure."""
    for entry in entries:
        url = str(entry["pkgUrl"])
        try:
            served = fetch_https(url)
        except PluginToolError as error:
            print(f"[warn] {error}")
            continue
        if hashlib.sha256(served).hexdigest() == str(entry["sha256"]):
            print(f"[ok] {entry['id']}@{entry['version']} is live ({len(served)} bytes)")
        else:
            print(f"[warn] {url} is not yet served with the published bytes.")
    try:
        index = json.loads(
            fetch_https(f"{PLUGIN_REPOSITORY_RAW_BASE}/{PLUGIN_REPOSITORY_BRANCH}/index.json").decode(
                "utf-8"
            )
        )
        served_ids = {str(item.get("id")) for item in index.get("plugins", [])}
    except (PluginToolError, UnicodeError, json.JSONDecodeError):
        served_ids = set()
    missing = [str(entry["id"]) for entry in entries if str(entry["id"]) not in served_ids]
    if missing:
        print(
            "[info] The index still reads the previous copy (GitHub CDN ~5 minutes,"
            f" app cache 4 hours): {', '.join(missing)}"
        )


def publish_plugins(
    plugins: Sequence[Plugin],
    *,
    repository_value: str | None,
    message: str | None,
    push: bool,
    force: bool,
) -> list[str]:
    if not plugins:
        raise PluginToolError("Select at least one plugin to publish")
    repository = resolve_plugin_repository(repository_value)
    prepare_plugin_repository(repository)
    original, index = load_plugin_index(repository)
    entries = index_entries(index)
    verify_index_packages(repository, entries, {})

    pending: dict[str, bytes] = {}
    published: list[dict[str, object]] = []
    source_commit = ""
    for plugin in plugins:
        source_commit = require_committed_source(plugin)
        package_path = package_plugin(plugin)
        data = package_path.read_bytes()
        entry = build_index_entry(plugin, package_path.name, data)
        previous = next(
            (item for item in entries if item.get("id") == entry["id"]), None
        )
        if previous is not None and not force:
            published_version = str(previous["version"])
            if version_key(published_version) > version_key(plugin.version):
                raise PluginToolError(
                    f"{plugin.name}: the index already advertises the newer {published_version}"
                )
            if published_version == plugin.version:
                if previous.get("sha256") != entry["sha256"]:
                    raise PluginToolError(
                        f"{plugin.name} {plugin.version} is already published with different"
                        " content; bump the version so installs can tell them apart"
                    )
                raise PluginToolError(
                    f"{plugin.name} {plugin.version} is already published and unchanged"
                )
        staged = repository / PLUGIN_REPOSITORY_PACKAGES / package_path.name
        if (
            staged.is_file()
            and not force
            and hashlib.sha256(staged.read_bytes()).hexdigest()
            != hashlib.sha256(data).hexdigest()
        ):
            raise PluginToolError(f"{staged} already holds different bytes; pass --force to replace it")

        action = merge_index_entry(entries, entry)
        pending[package_path.name] = data
        published.append(entry)
        print(f"[info] {plugin.plugin_id}@{plugin.version} {action} ({len(data)} bytes)")

    verify_index_packages(repository, entries, pending)
    rendered = render_index(index, entries)
    diff = "".join(
        difflib.unified_diff(
            original.splitlines(keepends=True),
            rendered.splitlines(keepends=True),
            fromfile=f"a/{PLUGIN_REPOSITORY_INDEX}",
            tofile=f"b/{PLUGIN_REPOSITORY_INDEX}",
        )
    )
    print(diff.rstrip("\n") or f"[info] {PLUGIN_REPOSITORY_INDEX} is already up to date")
    if not push:
        print("[info] Prepared only. Re-run with --push to commit and publish.")
        return []

    (repository / PLUGIN_REPOSITORY_INDEX).write_text(rendered, encoding="utf-8", newline="\n")
    for package_name, package in pending.items():
        (repository / PLUGIN_REPOSITORY_PACKAGES / package_name).write_bytes(package)
    paths = [PLUGIN_REPOSITORY_INDEX] + [
        f"{PLUGIN_REPOSITORY_PACKAGES}/{name}" for name in pending
    ]
    run_git(repository, ["add", "--", *paths])
    subject = message or "feat: publish " + ", ".join(
        f"{entry['name']} {entry['version']}" for entry in published
    )
    run_git(
        repository,
        ["commit", "-m", subject, "-m", f"Built from Inkleaf {source_commit}."],
    )
    print(run_git(repository, ["push", "origin", PLUGIN_REPOSITORY_BRANCH]).stdout.strip())
    verify_published(published)
    return [str(entry["id"]) for entry in published]


def resolve_adb_path() -> Path:
    executable = shutil.which("adb")
    if executable:
        return Path(executable)
    executable_name = "adb.exe" if os.name == "nt" else "adb"
    candidates: list[Path] = []
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(variable):
            candidates.append(
                Path(os.environ[variable]) / "platform-tools" / executable_name
            )
    local_properties = REPOSITORY_ROOT / "local.properties"
    if local_properties.is_file():
        for line in local_properties.read_text(encoding="utf-8").splitlines():
            if re.match(r"^\s*sdk\.dir\s*=", line):
                value = re.sub(r"^\s*sdk\.dir\s*=\s*", "", line).strip()
                value = value.replace(r"\:", ":").replace(r"\\", "\\")
                candidates.append(Path(value) / "platform-tools" / executable_name)
                break
    home = Path.home()
    candidates.extend(
        [
            home / "AppData/Local/Android/Sdk/platform-tools" / executable_name,
            home / "Library/Android/sdk/platform-tools" / executable_name,
            home / "Android/Sdk/platform-tools" / executable_name,
        ]
    )
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    raise PluginToolError(
        "adb not found. Install Android platform-tools or configure the SDK path."
    )


def run_adb(
    adb: Path, arguments: list[str], *, check: bool = True
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        [str(adb), *arguments],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if check and result.returncode != 0:
        raise PluginToolError(
            f"ADB command failed ({result.returncode}): {result.stdout.strip()}"
        )
    return result


def list_adb_devices(adb: Path) -> list[AdbDevice]:
    run_adb(adb, ["start-server"])
    result = run_adb(adb, ["devices"])
    devices: list[AdbDevice] = []
    for line in result.stdout.splitlines()[1:]:
        match = re.match(r"^(\S+)\s+(\S+)", line.strip())
        if match:
            devices.append(AdbDevice(match.group(1), match.group(2)))
    return devices


def select_device(
    adb: Path,
    preferred: str | None,
    *,
    interactive: bool,
    choose_ready: Callable[[Sequence[AdbDevice]], str] | None = None,
) -> str:
    devices = list_adb_devices(adb)
    ready = [device for device in devices if device.state == "device"]
    if preferred:
        if any(device.serial == preferred for device in ready):
            return preferred
        known = ", ".join(device.serial for device in ready) or "none"
        raise PluginToolError(
            f"ADB device '{preferred}' is not ready. Ready devices: {known}"
        )
    if not ready:
        seen = (
            ", ".join(f"{device.serial}={device.state}" for device in devices) or "none"
        )
        raise PluginToolError(f"No ready ADB device. Seen devices: {seen}")
    if len(ready) == 1:
        return ready[0].serial
    if interactive and choose_ready:
        selected = choose_ready(ready)
        if selected is None:
            raise KeyboardInterrupt
        return selected
    serials = ", ".join(device.serial for device in ready)
    raise PluginToolError(
        f"Multiple ADB devices are ready. Re-run with --serial. Devices: {serials}"
    )


def validate_device_directory(value: str) -> str:
    if not re.fullmatch(r"/sdcard/[A-Za-z0-9._/-]+", value):
        raise PluginToolError("Device directory must be an absolute path below /sdcard")
    path = PurePosixPath(value)
    if any(part in ("", ".", "..") for part in path.relative_to("/sdcard").parts):
        raise PluginToolError("Device directory contains an unsafe path segment")
    return str(path)


def receiver_component(package_id: str) -> str:
    return f"{package_id}/com.exio.inkleaf.plugin.AdbPluginInstallReceiver"


def has_install_receiver(adb: Path, device: str, package_id: str) -> bool:
    component = receiver_component(package_id)
    result = run_adb(
        adb,
        [
            "-s",
            device,
            "shell",
            "cmd",
            "package",
            "query-receivers",
            "--brief",
            "--components",
            "-n",
            component,
        ],
        check=False,
    )
    return result.returncode == 0 and component in {
        line.strip() for line in result.stdout.splitlines()
    }


def assert_install_receiver(adb: Path, device: str, package_id: str) -> None:
    package = run_adb(
        adb, ["-s", device, "shell", "pm", "path", package_id], check=False
    )
    if package.returncode != 0 or not any(
        line.startswith("package:") for line in package.stdout.splitlines()
    ):
        raise PluginToolError(
            f"Inkleaf package is not installed on the device: {package_id}"
        )
    if has_install_receiver(adb, device, package_id):
        return
    hint = " Install a current Inkleaf build before deploying plugins."
    if package_id != DEBUG_PACKAGE_ID and has_install_receiver(
        adb, device, DEBUG_PACKAGE_ID
    ):
        hint += f" The debug app is ready; re-run with --package-id {DEBUG_PACKAGE_ID}."
    raise PluginToolError(
        f"Installed Inkleaf package '{package_id}' does not expose the ADB install receiver.{hint}"
    )


def start_app(adb: Path, device: str, package_id: str) -> None:
    print(f"[info] Waking {package_id} so it can receive the install broadcast")
    resolved = run_adb(
        adb,
        [
            "-s",
            device,
            "shell",
            "cmd",
            "package",
            "resolve-activity",
            "--brief",
            package_id,
        ],
        check=False,
    )
    component = next(
        (
            line.strip()
            for line in resolved.stdout.splitlines()
            if line.strip().startswith(f"{package_id}/")
        ),
        None,
    )
    if component:
        run_adb(adb, ["-s", device, "shell", "am", "start", "-W", "-n", component])
    else:
        run_adb(
            adb,
            [
                "-s",
                device,
                "shell",
                "monkey",
                "-p",
                package_id,
                "-c",
                "android.intent.category.LAUNCHER",
                "1",
            ],
        )
    for _ in range(20):
        time.sleep(0.5)
        result = run_adb(adb, ["-s", device, "shell", "pidof", package_id], check=False)
        if result.returncode == 0 and result.stdout.strip():
            return
    raise PluginToolError(
        f"Unable to start {package_id}; launch it manually and re-run."
    )


def install_broadcast(
    adb: Path, device: str, package_id: str, extras: list[str]
) -> str:
    result = run_adb(
        adb,
        [
            "-s",
            device,
            "shell",
            "am",
            "broadcast",
            "-a",
            "com.exio.inkleaf.action.ADB_INSTALL_PLUGIN",
            "-n",
            receiver_component(package_id),
            "--receiver-foreground",
            "--include-stopped-packages",
            *extras,
        ],
    )
    completion = BROADCAST_RESULT_PATTERN.search(result.stdout)
    if not completion:
        raise PluginToolError(
            f"Inkleaf did not return an installation result: {result.stdout.strip()}"
        )
    detail = completion.group("data") or ""
    if completion.group("code") != "-1":
        if not detail:
            raise PluginToolError(
                f"The install receiver did not execute: {result.stdout.strip()}"
            )
        raise PluginToolError(f"Inkleaf rejected the plugin deployment: {detail}")
    return detail


def deploy_plugin(
    plugin: Plugin,
    *,
    serial: str | None,
    device_directory: str,
    output: str | None,
    package_id: str,
    interactive: bool,
) -> None:
    if not PACKAGE_ID_PATTERN.fullmatch(package_id):
        raise PluginToolError(f"Invalid Android package id: {package_id}")
    device_directory = validate_device_directory(device_directory)
    adb = resolve_adb_path()
    print(f"[info] Using adb at {adb}")
    device = select_device(adb, serial, interactive=interactive)
    print(f"[info] Using ADB device {device}")
    package = package_plugin(plugin, output)
    device_path = (
        f"{device_directory.rstrip('/')}/{plugin.name}-plugin-v{plugin.version}.zip"
    )

    assert_install_receiver(adb, device, package_id)
    start_app(adb, device, package_id)
    print(f"[info] Uploading {package.name} to {device}:{device_path}")
    run_adb(adb, ["-s", device, "shell", "mkdir", "-p", device_directory])
    run_adb(adb, ["-s", device, "push", str(package), device_path])
    print(f"[ok] Package pushed to {device_path}")

    # Command-line-safe chunks stay below both Windows and Binder limits.
    session = uuid.uuid4().hex
    encoded = base64.b64encode(package.read_bytes()).decode("ascii")
    common = ["--es", "session", session]
    chunk_size = 12 * 1024
    chunk_count = (len(encoded) + chunk_size - 1) // chunk_size
    print(f"[info] Starting ADB installation in {package_id}")
    print(f"[info] Sending payload in {chunk_count} chunk(s)")
    install_broadcast(adb, device, package_id, [*common, "--es", "operation", "begin"])
    for offset in range(0, len(encoded), chunk_size):
        install_broadcast(
            adb,
            device,
            package_id,
            [
                *common,
                "--es",
                "operation",
                "append",
                "--es",
                "payload",
                encoded[offset : offset + chunk_size],
            ],
        )
    result = install_broadcast(
        adb,
        device,
        package_id,
        [
            *common,
            "--es",
            "operation",
            "commit",
            "--es",
            "expectedPluginId",
            plugin.plugin_id,
            "--es",
            "expectedVersion",
            plugin.version,
        ],
    )
    print(f"[ok] Plugin installed and activated: {result}")


def run_interactive() -> None:
    if not sys.stdin.isatty() or not sys.stdout.isatty():
        raise PluginToolError(
            "Interactive mode requires a terminal. Use package/deploy arguments in automation."
        )
    questionary = load_questionary()
    plugins = discover_plugins()
    action = questionary.select(
        "Choose an action:",
        choices=[
            questionary.Choice("Package plugins", value="package"),
            questionary.Choice("Deploy a plugin", value="deploy"),
        ],
    ).ask()
    if action is None:
        raise KeyboardInterrupt

    if action == "package":
        selected = questionary.checkbox(
            "Select plugins to package:",
            choices=[
                questionary.Choice(
                    f"{plugin.name} ({plugin.plugin_id}@{plugin.version}; {plugin.build_strategy})",
                    value=plugin,
                )
                for plugin in plugins
            ],
            validate=lambda values: True if values else "Select at least one plugin.",
        ).ask()
        if selected is None:
            raise KeyboardInterrupt
        package_plugins(selected)
        return

    plugin = questionary.select(
        "Choose a plugin:",
        choices=[
            questionary.Choice(
                f"{item.name} ({item.plugin_id}@{item.version}; {item.build_strategy})",
                value=item,
            )
            for item in plugins
        ],
    ).ask()
    if plugin is None:
        raise KeyboardInterrupt
    targets = [("Release app", DEFAULT_PACKAGE_ID), ("Debug app", DEBUG_PACKAGE_ID)]
    target = questionary.select(
        "Choose the target app:",
        choices=[
            questionary.Choice(f"{label} ({app_id})", value=app_id)
            for label, app_id in targets
        ],
    ).ask()
    if target is None:
        raise KeyboardInterrupt
    adb = resolve_adb_path()
    device = select_device(
        adb,
        None,
        interactive=True,
        choose_ready=lambda ready: questionary.select(
            "Choose an ADB device:",
            choices=[
                questionary.Choice(item.serial, value=item.serial) for item in ready
            ],
        ).ask(),
    )
    confirmed = questionary.confirm(
        f"Deploy {plugin.plugin_id}@{plugin.version} to {target} on {device}?",
        default=True,
    ).ask()
    if confirmed is None:
        raise KeyboardInterrupt
    if not confirmed:
        print("[info] Deployment cancelled.")
        return
    deploy_plugin(
        plugin,
        serial=device,
        device_directory=DEFAULT_DEVICE_DIRECTORY,
        output=None,
        package_id=target,
        interactive=True,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Package, deploy, and publish Inkleaf plugins.",
        epilog="Run without arguments for the interactive menu.",
    )
    subparsers = parser.add_subparsers(dest="action", required=True)
    package_parser = subparsers.add_parser(
        "package", help="Build an installable plugin ZIP."
    )
    package_parser.add_argument(
        "plugins",
        nargs="*",
        metavar="plugin",
        help="Directory names below plugin-fixtures.",
    )
    package_parser.add_argument(
        "--all", action="store_true", help="Package every discovered plugin."
    )
    output_group = package_parser.add_mutually_exclusive_group()
    output_group.add_argument(
        "--output", help="ZIP path for a single plugin below plugin-fixtures/dist."
    )
    output_group.add_argument(
        "--output-dir",
        help="Output directory for multiple versioned ZIPs below plugin-fixtures/dist.",
    )

    deploy_parser = subparsers.add_parser(
        "deploy", help="Package, push, install, and activate a plugin over ADB."
    )
    deploy_parser.add_argument("plugin", help="Directory name below plugin-fixtures.")
    deploy_parser.add_argument(
        "--serial", help="ADB device serial; required when several are ready."
    )
    deploy_parser.add_argument("--device-directory", default=DEFAULT_DEVICE_DIRECTORY)
    deploy_parser.add_argument("--output", help="ZIP path below plugin-fixtures/dist.")
    deploy_parser.add_argument("--package-id", default=DEFAULT_PACKAGE_ID)

    publish_parser = subparsers.add_parser(
        "publish",
        help="Build plugins, update the repository index, and optionally push the release.",
    )
    publish_parser.add_argument(
        "plugins", nargs="+", metavar="plugin", help="Directory names below plugin-fixtures."
    )
    publish_parser.add_argument(
        "--repo-dir",
        help=(
            "Clone of "
            f"{PLUGIN_REPOSITORY_SLUG}; defaults to $INKLEAF_PLUGIN_REPO or the sibling of this repository."
        ),
    )
    publish_parser.add_argument("--message", help="Commit subject for the release.")
    publish_parser.add_argument(
        "--push",
        action="store_true",
        help="Write, commit, and push the release; without this the plan is only printed.",
    )
    publish_parser.add_argument(
        "--force",
        action="store_true",
        help="Replace an already published version instead of refusing it.",
    )
    return parser


def main(arguments: list[str]) -> int:
    if sys.version_info < MINIMUM_PYTHON:
        required = ".".join(map(str, MINIMUM_PYTHON))
        raise PluginToolError(f"Python {required} or newer is required")
    if not arguments:
        run_interactive()
        return 0
    options = build_parser().parse_args(arguments)
    if options.action == "package":
        available = discover_plugins()
        if options.all and options.plugins:
            raise PluginToolError("Plugin names and --all cannot be used together")
        if not options.all and not options.plugins:
            raise PluginToolError("Specify one or more plugins, or use --all")
        selected = available if options.all else select_plugins(options.plugins, available)
        package_plugins(
            selected, output=options.output, output_directory=options.output_dir
        )
    elif options.action == "publish":
        available = discover_plugins()
        publish_plugins(
            select_plugins(options.plugins, available),
            repository_value=options.repo_dir,
            message=options.message,
            push=options.push,
            force=options.force,
        )
    else:
        plugin = find_plugin(options.plugin)
        deploy_plugin(
            plugin,
            serial=options.serial,
            device_directory=options.device_directory,
            output=options.output,
            package_id=options.package_id,
            interactive=False,
        )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except PluginToolError as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
    except KeyboardInterrupt:
        print("\nCancelled.", file=sys.stderr)
        raise SystemExit(130)
