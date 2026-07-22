#!/usr/bin/env python3
"""agy-delegate runner — thin sync wrapper around Antigravity CLI (`agy`).

Subcommands:
  check  Probe whether agy is installed and looks authenticated.
  ask    Delegate one prompt via `agy -p` (required --path + prompt).
  help   Print usage.

Exit codes:
  0   success
  1   agy present but run/auth failed
  64  usage / validation error
  127 agy binary not found
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import NoReturn

EXIT_OK = 0
EXIT_FAIL = 1
EXIT_USAGE = 64
EXIT_NOT_FOUND = 127


def eprint(*args: object) -> None:
    print(*args, file=sys.stderr)


def die(code: int, message: str) -> NoReturn:
    eprint(f"error: {message}")
    raise SystemExit(code)


def find_agy() -> Path | None:
    for name in ("agy", "agy.exe"):
        found = shutil.which(name)
        if found:
            return Path(found)

    home = Path.home()
    candidates = [
        home / ".local" / "bin" / "agy",
        home / ".local" / "bin" / "agy.exe",
        Path("/opt/antigravity/bin/agy"),
        Path("/usr/local/bin/agy"),
        home / "AppData" / "Local" / "Programs" / "agy" / "agy.exe",
    ]
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate
        # Windows: X_OK is unreliable; existence is enough for .exe
        if candidate.is_file() and candidate.suffix.lower() == ".exe":
            return candidate
    return None


def auth_status() -> str:
    if os.environ.get("ANTIGRAVITY_API_KEY"):
        return "api-key"
    home = Path.home()
    if (home / ".config" / "antigravity").is_dir() or (
        home / ".gemini" / "antigravity-cli"
    ).is_dir():
        return "oauth"
    return "missing"


def agy_version(agy: Path) -> str:
    try:
        proc = subprocess.run(
            [str(agy), "--version"],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return "unknown"
    text = (proc.stdout or proc.stderr or "").strip()
    if not text:
        return "unknown"
    return text.splitlines()[0].strip()


def cmd_check(_args: argparse.Namespace) -> int:
    agy = find_agy()
    if agy is None:
        payload = {
            "installed": False,
            "path": "",
            "version": "",
            "auth": "unknown",
            "error": (
                "agy binary not found; install Antigravity CLI and ensure "
                "agy/agy.exe is on PATH"
            ),
        }
        print(json.dumps(payload, ensure_ascii=False))
        return EXIT_OK

    auth = auth_status()
    payload = {
        "installed": True,
        "path": str(agy),
        "version": agy_version(agy),
        "auth": auth,
        "error": "" if auth != "missing" else "agy does not look authenticated",
    }
    print(json.dumps(payload, ensure_ascii=False))
    return EXIT_OK


def require_agy() -> Path:
    agy = find_agy()
    if agy is None:
        die(
            EXIT_NOT_FOUND,
            "agy is not installed or not on PATH "
            "(looked for agy / agy.exe).",
        )
    if auth_status() == "missing":
        die(
            EXIT_FAIL,
            "agy is not authenticated. Run `agy` once interactively, "
            "or set ANTIGRAVITY_API_KEY.",
        )
    return agy


def cmd_ask(args: argparse.Namespace) -> int:
    prompt = args.prompt
    if not prompt or not str(prompt).strip():
        die(EXIT_USAGE, "ask requires a non-empty prompt argument")

    path = Path(args.path).expanduser()
    try:
        path = path.resolve(strict=True)
    except FileNotFoundError:
        die(EXIT_USAGE, f"--path does not exist: {args.path}")
    except OSError as exc:
        die(EXIT_USAGE, f"--path is not usable ({args.path}): {exc}")

    if not path.is_dir():
        die(EXIT_USAGE, f"--path must be a directory: {path}")

    agy = require_agy()
    cmd = [
        str(agy),
        "--dangerously-skip-permissions",
        "-p",
        prompt,
    ]
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(path),
            check=False,
        )
    except OSError as exc:
        die(EXIT_FAIL, f"failed to execute agy: {exc}")

    return EXIT_OK if proc.returncode == 0 else EXIT_FAIL


def cmd_help(_args: argparse.Namespace | None = None) -> int:
    text = """agy-delegate — sync wrapper around Antigravity CLI (agy)

Usage:
  python agy_run.py check
  python agy_run.py ask --path <dir> "<prompt>"
  python agy_run.py help

ask:
  --path   Required. Existing directory; process cwd for agy.
  prompt   Required. Single prompt string passed to `agy -p`.

Behavior:
  - Synchronous: waits for agy, streams/returns its stdout.
  - Always passes --dangerously-skip-permissions.
  - Does not set --model or other agy flags (use agy defaults).
  - Script messages go to stderr.

Exit codes:
  0    success
  1    agy/auth/run failure
  64   usage or invalid --path
  127  agy not found
"""
    print(text, end="")
    return EXIT_OK


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="agy_run.py",
        description="Thin sync wrapper around agy for agent delegation.",
        add_help=False,
    )
    sub = parser.add_subparsers(dest="command")

    p_check = sub.add_parser("check", help="Probe agy install/auth")
    p_check.set_defaults(func=cmd_check)

    p_ask = sub.add_parser("ask", help="Delegate one prompt via agy -p")
    p_ask.add_argument(
        "--path",
        required=True,
        help="Required project directory (must exist)",
    )
    p_ask.add_argument(
        "prompt",
        help="Task prompt (quote as one shell argument)",
    )
    p_ask.set_defaults(func=cmd_ask)

    p_help = sub.add_parser("help", help="Show usage")
    p_help.set_defaults(func=cmd_help)

    return parser


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0] in ("-h", "--help"):
        return cmd_help()

    parser = build_parser()
    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        # argparse already printed to stderr; normalize to usage code
        code = exc.code
        if code in (None, 0):
            return EXIT_OK
        return EXIT_USAGE

    if not getattr(args, "command", None):
        cmd_help()
        return EXIT_USAGE

    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
