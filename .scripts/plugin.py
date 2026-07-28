#!/usr/bin/env python3
"""Package and deploy Inkleaf plugins from one cross-platform entry point.

Plugin source lives at ``plugin-fixtures/<name>/src/main.js``. Optional
``plugin.build.json`` data affects only the build; every ZIP exposes the same
root-level ``manifest.json`` and ``main.js`` contract to the Android host.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import time
from dataclasses import dataclass
from typing import Callable, Sequence
import uuid
import zipfile


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
            raise PluginToolError(f"Plugin input path must not contain a link: {current}")
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
            raise PluginToolError(f"Manifest must declare a non-empty string '{field}': {manifest_path}")
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
            raise PluginToolError(f"Unsupported plugin.build.json fields for {name}: {fields}")
        runtime_value = build.get("runtime")
        if not isinstance(runtime_value, str) or not runtime_value.strip():
            raise PluginToolError(f"plugin.build.json must declare a non-empty 'runtime': {build_path}")
        runtime = directory / runtime_value
        require_safe_input(runtime, FIXTURES_ROOT)
        runtime = Path(os.path.abspath(runtime))

    return Plugin(name, directory, source, manifest_path, manifest, runtime)


def discover_plugins() -> list[Plugin]:
    require_plain_directory(FIXTURES_ROOT)
    plugins: list[Plugin] = []
    failures: list[str] = []
    for directory in sorted(FIXTURES_ROOT.iterdir(), key=lambda item: item.name.casefold()):
        if not directory.is_dir() or not (directory / "manifest.json").exists():
            continue
        try:
            plugins.append(load_plugin(directory))
        except (OSError, UnicodeError, PluginToolError, zipfile.BadZipFile) as error:
            failures.append(str(error))
    if failures:
        raise PluginToolError("Invalid plugin directories:\n  - " + "\n  - ".join(failures))
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
            raise PluginToolError(f"Output directory must not contain a link: {current}")
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
        raise PluginToolError(f"Output path must not be inside the staging directory: {build_root}")
    ensure_safe_directory(candidate.parent, DIST_ROOT)
    if candidate.exists() and (candidate.is_dir() or is_link(candidate)):
        raise PluginToolError(f"Output file must not be a directory or link: {candidate}")
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
            raise PluginToolError(f"Build directory must be a plain directory: {staging}")
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
    output = resolve_output_path(plugin, output_value)
    staging = stage_plugin(plugin)
    temporary = output.with_name(f".{output.name}.{uuid.uuid4().hex}.tmp")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            entries = [staging / "manifest.json", staging / "main.js"]
            assets = staging / "assets"
            if assets.is_dir():
                entries.extend(path for path in assets.rglob("*") if path.is_file())
            for path in sorted(entries, key=lambda item: item.as_posix()):
                archive.write(path, path.relative_to(staging).as_posix())
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Created {output}")
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
        raise PluginToolError("--output-dir can only be used when packaging multiple plugins")

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


def resolve_adb_path() -> Path:
    executable = shutil.which("adb")
    if executable:
        return Path(executable)
    executable_name = "adb.exe" if os.name == "nt" else "adb"
    candidates: list[Path] = []
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(variable):
            candidates.append(Path(os.environ[variable]) / "platform-tools" / executable_name)
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
    raise PluginToolError("adb not found. Install Android platform-tools or configure the SDK path.")


def run_adb(adb: Path, arguments: list[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
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
        raise PluginToolError(f"ADB command failed ({result.returncode}): {result.stdout.strip()}")
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
        raise PluginToolError(f"ADB device '{preferred}' is not ready. Ready devices: {known}")
    if not ready:
        seen = ", ".join(f"{device.serial}={device.state}" for device in devices) or "none"
        raise PluginToolError(f"No ready ADB device. Seen devices: {seen}")
    if len(ready) == 1:
        return ready[0].serial
    if interactive and choose_ready:
        selected = choose_ready(ready)
        if selected is None:
            raise KeyboardInterrupt
        return selected
    serials = ", ".join(device.serial for device in ready)
    raise PluginToolError(f"Multiple ADB devices are ready. Re-run with --serial. Devices: {serials}")


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
        ["-s", device, "shell", "cmd", "package", "query-receivers", "--brief", "--components", "-n", component],
        check=False,
    )
    return result.returncode == 0 and component in {line.strip() for line in result.stdout.splitlines()}


def assert_install_receiver(adb: Path, device: str, package_id: str) -> None:
    package = run_adb(adb, ["-s", device, "shell", "pm", "path", package_id], check=False)
    if package.returncode != 0 or not any(line.startswith("package:") for line in package.stdout.splitlines()):
        raise PluginToolError(f"Inkleaf package is not installed on the device: {package_id}")
    if has_install_receiver(adb, device, package_id):
        return
    hint = " Install a current Inkleaf build before deploying plugins."
    if package_id != DEBUG_PACKAGE_ID and has_install_receiver(adb, device, DEBUG_PACKAGE_ID):
        hint += f" The debug app is ready; re-run with --package-id {DEBUG_PACKAGE_ID}."
    raise PluginToolError(
        f"Installed Inkleaf package '{package_id}' does not expose the ADB install receiver.{hint}"
    )


def start_app(adb: Path, device: str, package_id: str) -> None:
    print(f"[info] Waking {package_id} so it can receive the install broadcast")
    resolved = run_adb(
        adb,
        ["-s", device, "shell", "cmd", "package", "resolve-activity", "--brief", package_id],
        check=False,
    )
    component = next(
        (line.strip() for line in resolved.stdout.splitlines() if line.strip().startswith(f"{package_id}/")),
        None,
    )
    if component:
        run_adb(adb, ["-s", device, "shell", "am", "start", "-W", "-n", component])
    else:
        run_adb(
            adb,
            ["-s", device, "shell", "monkey", "-p", package_id, "-c", "android.intent.category.LAUNCHER", "1"],
        )
    for _ in range(20):
        time.sleep(0.5)
        result = run_adb(adb, ["-s", device, "shell", "pidof", package_id], check=False)
        if result.returncode == 0 and result.stdout.strip():
            return
    raise PluginToolError(f"Unable to start {package_id}; launch it manually and re-run.")


def install_broadcast(adb: Path, device: str, package_id: str, extras: list[str]) -> str:
    result = run_adb(
        adb,
        [
            "-s", device, "shell", "am", "broadcast",
            "-a", "com.exio.inkleaf.action.ADB_INSTALL_PLUGIN",
            "-n", receiver_component(package_id),
            "--receiver-foreground", "--include-stopped-packages", *extras,
        ],
    )
    completion = BROADCAST_RESULT_PATTERN.search(result.stdout)
    if not completion:
        raise PluginToolError(f"Inkleaf did not return an installation result: {result.stdout.strip()}")
    detail = completion.group("data") or ""
    if completion.group("code") != "-1":
        if not detail:
            raise PluginToolError(f"The install receiver did not execute: {result.stdout.strip()}")
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
    device = select_device(adb, serial, interactive=interactive)
    package = package_plugin(plugin, output)
    device_path = f"{device_directory.rstrip('/')}/{plugin.name}-plugin-v{plugin.version}.zip"

    print(f"[info] Using ADB device {device}")
    assert_install_receiver(adb, device, package_id)
    start_app(adb, device, package_id)
    run_adb(adb, ["-s", device, "shell", "mkdir", "-p", device_directory])
    run_adb(adb, ["-s", device, "push", str(package), device_path])

    # Command-line-safe chunks stay below both Windows and Binder limits.
    session = uuid.uuid4().hex
    encoded = base64.b64encode(package.read_bytes()).decode("ascii")
    common = ["--es", "session", session]
    print(f"[info] Starting ADB installation in {package_id}")
    install_broadcast(adb, device, package_id, [*common, "--es", "operation", "begin"])
    chunk_size = 12 * 1024
    for offset in range(0, len(encoded), chunk_size):
        install_broadcast(
            adb,
            device,
            package_id,
            [*common, "--es", "operation", "append", "--es", "payload", encoded[offset : offset + chunk_size]],
        )
    result = install_broadcast(
        adb,
        device,
        package_id,
        [
            *common,
            "--es", "operation", "commit",
            "--es", "expectedPluginId", plugin.plugin_id,
            "--es", "expectedVersion", plugin.version,
        ],
    )
    print(f"[ok] Plugin package pushed to {device_path}")
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
        choices=[questionary.Choice(f"{label} ({app_id})", value=app_id) for label, app_id in targets],
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
            choices=[questionary.Choice(item.serial, value=item.serial) for item in ready],
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
        description="Package and deploy Inkleaf plugins.",
        epilog="Run without arguments for the interactive menu.",
    )
    subparsers = parser.add_subparsers(dest="action", required=True)
    package_parser = subparsers.add_parser("package", help="Build an installable plugin ZIP.")
    package_parser.add_argument(
        "plugins", nargs="*", metavar="plugin", help="Directory names below plugin-fixtures."
    )
    package_parser.add_argument("--all", action="store_true", help="Package every discovered plugin.")
    output_group = package_parser.add_mutually_exclusive_group()
    output_group.add_argument("--output", help="ZIP path for a single plugin below plugin-fixtures/dist.")
    output_group.add_argument(
        "--output-dir",
        help="Output directory for multiple versioned ZIPs below plugin-fixtures/dist.",
    )

    deploy_parser = subparsers.add_parser(
        "deploy", help="Package, push, install, and activate a plugin over ADB."
    )
    deploy_parser.add_argument("plugin", help="Directory name below plugin-fixtures.")
    deploy_parser.add_argument("--serial", help="ADB device serial; required when several are ready.")
    deploy_parser.add_argument("--device-directory", default=DEFAULT_DEVICE_DIRECTORY)
    deploy_parser.add_argument("--output", help="ZIP path below plugin-fixtures/dist.")
    deploy_parser.add_argument("--package-id", default=DEFAULT_PACKAGE_ID)
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
        if options.all:
            selected = available
        else:
            selected = []
            seen: set[str] = set()
            for name in options.plugins:
                if name not in seen:
                    selected.append(find_plugin(name, available))
                    seen.add(name)
        package_plugins(selected, output=options.output, output_directory=options.output_dir)
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
