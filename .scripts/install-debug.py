#!/usr/bin/env python3
"""Install the debug APK on a wireless ADB device and restart the app."""

from __future__ import annotations

import argparse
import os
import shlex
import shutil
import subprocess
import sys
from pathlib import Path


DEFAULT_PACKAGE = "com.exio.inkleaf.debug"
DEFAULT_ACTIVITY = "com.exio.inkleaf.MainActivity"
DEFAULT_APK = Path(__file__).resolve().parents[1] / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"


class AdbError(RuntimeError):
    """Raised when an ADB command cannot complete successfully."""


def log(message: str) -> None:
    print(f"[INFO] {message}")


def find_adb(explicit: str | None) -> str:
    if explicit:
        path = Path(explicit).expanduser()
        if path.is_file():
            return str(path)
        raise FileNotFoundError(f"adb executable does not exist: {path}")

    on_path = shutil.which("adb")
    if on_path:
        return on_path

    for sdk_variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk_root = os.environ.get(sdk_variable)
        if not sdk_root:
            continue
        candidate = Path(sdk_root) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        if candidate.is_file():
            return str(candidate)

    raise FileNotFoundError("adb was not found on PATH or in ANDROID_HOME/ANDROID_SDK_ROOT")


def run_adb(adb: str, args: list[str], serial: str | None, timeout: float) -> str:
    command = [adb]
    if serial:
        command.extend(("-s", serial))
    command.extend(args)
    log(f"执行: {shlex.join(command)}")

    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise AdbError(f"ADB command timed out after {timeout:g}s: {shlex.join(command)}") from exc
    except OSError as exc:
        raise AdbError(f"could not start adb: {exc}") from exc

    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip() or "no error output"
        raise AdbError(
            f"ADB command failed with exit code {result.returncode}: {detail}"
        )
    return result.stdout.strip()


def device_states(output: str) -> dict[str, str]:
    states: dict[str, str] = {}
    for line in output.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[0] != "List":
            states[parts[0]] = parts[1]
    return states


def looks_wireless(serial: str) -> bool:
    # Android wireless debugging uses an IP:port or an mDNS adb-* serial.
    return ":" in serial or serial.startswith("adb-")


def choose_device(adb: str, requested_serial: str | None, timeout: float) -> str:
    output = run_adb(adb, ["devices"], None, timeout)
    states = device_states(output)

    if requested_serial:
        state = states.get(requested_serial)
        if state != "device":
            available = ", ".join(f"{name} ({state})" for name, state in states.items()) or "none"
            raise AdbError(
                f"requested device {requested_serial!r} is not ready; available devices: {available}"
            )
        return requested_serial

    candidates = [serial for serial, state in states.items() if state == "device" and looks_wireless(serial)]
    if len(candidates) == 1:
        return candidates[0]
    if not candidates:
        raise AdbError(
            "no ready wireless device found; pair/connect the phone first or pass --serial. "
            f"ADB devices output: {output or '<empty>'}"
        )
    raise AdbError(
        f"multiple ready wireless devices found ({', '.join(candidates)}); pass --serial to choose one"
    )


def restart_app(adb: str, serial: str, package: str, activity: str, timeout: float) -> None:
    run_adb(adb, ["shell", "am", "force-stop", package], serial, timeout)
    component = activity if "/" in activity else f"{package}/{activity}"
    run_adb(adb, ["shell", "am", "start", "-n", component], serial, timeout)


def run(args: argparse.Namespace) -> None:
    apk = Path(args.apk).expanduser().resolve()
    if not apk.is_file():
        raise FileNotFoundError(f"debug APK does not exist: {apk}")

    adb = find_adb(args.adb)
    log(f"APK: {apk}")
    serial = choose_device(adb, args.serial, args.timeout)
    log(f"目标设备: {serial}")

    run_adb(adb, ["install", "-r", "-d", str(apk)], serial, args.timeout)
    log("debug 包安装成功")
    restart_app(adb, serial, args.package, args.activity, args.timeout)
    log(f"应用已重启: {args.package}")


def self_test() -> None:
    assert device_states("List of devices attached\n192.168.1.10:5555\tdevice\nUSB123\tdevice\n") == {
        "192.168.1.10:5555": "device",
        "USB123": "device",
    }
    assert looks_wireless("192.168.1.10:5555")
    assert looks_wireless("adb-ABC._adb-tls-connect._tcp")
    assert not looks_wireless("USB123")
    print("[OK] self-test passed")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", default=str(DEFAULT_APK), help="debug APK path")
    parser.add_argument("--serial", help="ADB serial; required when multiple wireless devices are ready")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help="application package name")
    parser.add_argument("--activity", default=DEFAULT_ACTIVITY, help="launcher activity or full package/activity")
    parser.add_argument("--adb", help="path to adb executable")
    parser.add_argument("--timeout", type=float, default=30, help="timeout per adb command in seconds")
    parser.add_argument("--self-test", action="store_true", help="run local checks without adb or a phone")
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be greater than zero")
    return args


def main() -> int:
    args = parse_args()
    try:
        if args.self_test:
            self_test()
        else:
            run(args)
    except KeyboardInterrupt:
        print("[ERROR] 操作已取消", file=sys.stderr)
        return 130
    except Exception as exc:
        print(f"[ERROR] {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
