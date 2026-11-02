#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Boot a disposable AVD clone; preserve the original and stop only our process."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import stat
import subprocess
import sys
import time
import uuid


class RunnerError(RuntimeError):
    pass


WRITABLE_IMAGES = {
    "disk.dataPartition.path": "userdata-qemu.img",
    "disk.cachePartition.path": "cache.img",
    "disk.encryptionKeyPartition.path": "encryptionkey.img",
    "sdcard.path": "sdcard.img",
}


def snapshot(source):
    """Include generated sidecars and directories, not merely backing images."""
    result = {}
    for path in [source, *sorted(source.rglob("*"))]:
        if path.is_symlink():
            raise RunnerError("Source AVD contains a symbolic link")
        info = path.stat()
        if not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)):
            raise RunnerError("Source AVD contains a special file")
        item = {"mode": info.st_mode, "size": info.st_size, "mtimeNs": info.st_mtime_ns}
        if path.is_file():
            digest = hashlib.sha256()
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            item["sha256"] = digest.hexdigest()
        result[str(path.relative_to(source))] = item
    return result


def copy_tree(source, destination):
    # macOS clone copies preserve sparse disk images without a full 2–6 GB rewrite.
    if sys.platform == "darwin":
        subprocess.run(["/bin/cp", "-c", "-R", str(source), str(destination)],
                       check=True, capture_output=True, timeout=120)
    else:
        shutil.copytree(source, destination)


def prepare_clone(source, sdk, workspace):
    source, sdk, workspace = Path(source), Path(sdk), Path(workspace)
    if source.is_symlink() or not source.is_dir() or not (source / "config.ini").is_file():
        raise RunnerError("Source AVD must be a real directory with config.ini")
    source, sdk = source.resolve(), sdk.resolve()
    if workspace.exists() or workspace.is_symlink():
        raise RunnerError("Workspace already exists")
    workspace = workspace.resolve()
    if workspace.is_relative_to(source):
        raise RunnerError("Workspace must be outside the source AVD")
    for binary in [sdk / "emulator/emulator", sdk / "platform-tools/adb"]:
        if not binary.is_file() or not os.access(binary, os.X_OK):
            raise RunnerError("SDK emulator and adb executables are required")
    # Reject links before copying: a clone must not retain writes into its donor.
    snapshot(source)
    config = {}
    for line in (source / "config.ini").read_text().splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            config[key.strip()] = value.strip()
    for key in WRITABLE_IMAGES:
        value = config.get(key, "")
        if value and value != "<temp>":
            target = Path(value)
            if not target.is_absolute():
                target = source / target
            if not target.resolve().is_relative_to(source):
                raise RunnerError("Writable image is outside the source AVD")
    workspace.mkdir(mode=0o700)
    try:
        home, emulator_home = workspace / "avd-home", workspace / "emulator-home"
        home.mkdir(mode=0o700)
        emulator_home.mkdir(mode=0o700)
        name = "PortalToolkitQA" + uuid.uuid4().hex[:12]
        clone = home / (name + ".avd")
        copy_tree(source, clone)
        # Rebuild cached launch paths; they can point back to the donor directory.
        for path in list(clone.rglob("*.lock")) + [
            clone / "hardware-qemu.ini", clone / "emu-launch-params.txt",
        ]:
            if path.is_dir():
                shutil.rmtree(path)
            elif path.exists():
                path.unlink()
        for key, basename in WRITABLE_IMAGES.items():
            if key != "sdcard.path" or key in config:
                value = config.get(key, "")
                relative = Path(basename)
                if value and value != "<temp>":
                    target = Path(value)
                    if not target.is_absolute():
                        target = source / target
                    relative = target.resolve().relative_to(source)
                destination = clone / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                config[key] = str(destination)
        (clone / "config.ini").write_text(
            "".join(f"{key}={value}\n" for key, value in config.items()))
        (home / (name + ".ini")).write_text(f"avd.ini.encoding=UTF-8\npath={clone}\n")
        return {"avd_dir": clone, "avd_home": home, "emulator_home": emulator_home,
                "name": name, "sdk": sdk, "workspace": workspace}
    except BaseException:
        shutil.rmtree(workspace)
        raise


def check_port(port):
    if port < 5554 or port > 5682 or port % 2:
        raise RunnerError("Use an even emulator port from 5554 to 5682")
    for candidate in [port, port + 1]:
        with socket.socket() as connection:
            connection.bind(("127.0.0.1", candidate))


def stop_process(process):
    if process is not None and process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)


def run_session(source, sdk, workspace, port=5558, boot_timeout=120, hold_seconds=30):
    source, workspace = Path(source), Path(workspace)
    if source.is_symlink():
        raise RunnerError("Source AVD must not be a symbolic link")
    source = source.resolve()
    check_port(port)
    before = snapshot(source)
    prepared = None
    process = None
    receipt = {"ready": False, "sourceUnchanged": False, "ownedResourcesRemoved": False}
    try:
        prepared = prepare_clone(source, sdk, workspace)
        workspace = prepared["workspace"]
        if snapshot(source) != before:
            raise RunnerError("Source changed during clone preparation")
        environment = {**os.environ, "ANDROID_HOME": str(prepared["sdk"]),
                       "ANDROID_SDK_ROOT": str(prepared["sdk"]),
                       "ANDROID_AVD_HOME": str(prepared["avd_home"]),
                       "ANDROID_EMULATOR_HOME": str(prepared["emulator_home"])}
        command = [str(prepared["sdk"] / "emulator/emulator"),
                   "-avd", prepared["name"], "-port", str(port),
                   "-datadir", str(prepared["avd_dir"]), "-read-only",
                   "-no-snapshot", "-no-window", "-no-audio", "-no-boot-anim",
                   "-gpu", "swiftshader_indirect"]
        with (workspace / "emulator.log").open("wb") as log:
            process = subprocess.Popen(command, env=environment, cwd=workspace,
                                       stdout=log, stderr=log, start_new_session=True)
            serial = f"emulator-{port}"
            adb = [str(prepared["sdk"] / "platform-tools/adb"), "-s", serial]
            deadline = time.monotonic() + boot_timeout
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RunnerError("Owned emulator exited before readiness")
                result = subprocess.run(adb + ["shell", "getprop", "sys.boot_completed"],
                                        capture_output=True, text=True, timeout=5)
                if result.returncode == 0 and result.stdout.strip() == "1":
                    identity = subprocess.run(adb + ["emu", "avd", "name"],
                                              capture_output=True, text=True, timeout=5)
                    names = identity.stdout.splitlines()
                    if identity.returncode != 0 or not names or names[0] != prepared["name"]:
                        raise RunnerError("Emulator identity does not match owned clone")
                    break
                time.sleep(0.5)
            else:
                raise RunnerError("Owned emulator boot timed out")
            receipt.update(ready=True, serial=serial, avd=prepared["name"])
            print(json.dumps({"ready": True, "serial": serial, "avd": prepared["name"]}),
                  flush=True)
            deadline = time.monotonic() + hold_seconds
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RunnerError("Owned emulator exited during session")
                time.sleep(min(0.5, max(0, deadline - time.monotonic())))
    finally:
        stop_process(process)
        try:
            receipt["sourceUnchanged"] = snapshot(source) == before
        finally:
            if prepared:
                for owned in [prepared["avd_home"], prepared["emulator_home"]]:
                    shutil.rmtree(owned)
                receipt["ownedResourcesRemoved"] = True
                (workspace / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        if not receipt["sourceUnchanged"]:
            raise RunnerError("Source AVD changed; inspect the private receipt")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-avd", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--port", type=int, default=5558)
    parser.add_argument("--boot-timeout", type=int, default=120)
    parser.add_argument("--hold-seconds", type=int, default=30)
    args = parser.parse_args()
    if not 1 <= args.boot_timeout <= 300 or not 0 <= args.hold_seconds <= 1200:
        parser.error("Boot timeout is 1–300; hold time is 0–1200 seconds")
    try:
        receipt = run_session(args.source_avd, args.sdk, args.workspace, args.port,
                              args.boot_timeout, args.hold_seconds)
        print(json.dumps(receipt))
        return 0
    except KeyboardInterrupt:
        print("Interrupted; owned process cleanup completed.", file=sys.stderr)
        return 130
    except (RunnerError, OSError, subprocess.SubprocessError):
        print("Isolated emulator failed; inspect its private receipt/log.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
