# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Offline ownership guards for disposable AVD clones."""

import hashlib
import os
from pathlib import Path
import shutil
import stat
import tempfile
import unittest
from unittest.mock import patch

from qa import isolated_emulator as runner


WRITABLE_KEYS = (
    "disk.dataPartition.path",
    "disk.cachePartition.path",
    "disk.encryptionKeyPartition.path",
    "sdcard.path",
)
SYSTEM_IMAGE = "system-images/android-34/google_apis/arm64-v8a/"


class IsolatedEmulatorFilesystemTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="isolated-avd-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.source = self.root / "owner.avd"
        self.source.mkdir()
        self.write_config({"disk.dataPartition.path": "<temp>"})
        (self.source / "userdata-qemu.img").write_bytes(b"PUBLIC OWNER IMAGE")
        (self.source / ".owner-sidecar").write_bytes(b"PUBLIC OWNER SIDECAR")
        snapshots = self.source / "snapshots" / "default_boot"
        snapshots.mkdir(parents=True)
        (snapshots / "ram.img").write_bytes(b"PUBLIC SNAPSHOT")
        self.sdk = self.root / "sdk"
        self.make_sdk(self.sdk)
        self.workspace = self.root / "owned-workspace"
        copying = patch.object(runner, "copy_tree", side_effect=shutil.copytree)
        self.copy_tree = copying.start()
        self.addCleanup(copying.stop)

    def make_sdk(self, sdk):
        for relative in ("emulator/emulator", "platform-tools/adb"):
            binary = sdk / relative
            binary.parent.mkdir(parents=True, exist_ok=True)
            binary.write_text("#!/bin/sh\nexit 0\n")
            binary.chmod(0o700)

    def write_config(self, writable):
        values = {"image.sysdir.1": SYSTEM_IMAGE, **writable}
        (self.source / "config.ini").write_text(
            "".join(f"{key}={value}\n" for key, value in values.items())
        )

    def read_config(self, path):
        return dict(
            line.split("=", 1)
            for line in path.read_text().splitlines()
            if "=" in line
        )

    def assert_inside(self, path, directory):
        self.assertTrue(
            path.resolve().is_relative_to(directory.resolve()),
            f"{path} must remain inside {directory}",
        )

    def assert_rejected(self, source=None, sdk=None, workspace=None):
        before = runner.snapshot(self.source)
        with self.assertRaises(runner.RunnerError):
            runner.prepare_clone(
                self.source if source is None else source,
                self.sdk if sdk is None else sdk,
                self.workspace if workspace is None else workspace,
            )
        self.assertEqual(before, runner.snapshot(self.source))

    def test_runner_error_is_a_runtime_error(self):
        self.assertTrue(issubclass(runner.RunnerError, RuntimeError))

    def test_clone_owns_paths_registration_and_image_writes(self):
        before = runner.snapshot(self.source)
        prepared = runner.prepare_clone(self.source, self.sdk, self.workspace)
        for key in ("avd_dir", "avd_home", "emulator_home", "sdk"):
            self.assertIsInstance(prepared[key], Path)
        self.assertIsInstance(prepared["name"], str)
        self.assertTrue(prepared["name"])
        self.assertEqual(self.sdk.resolve(), prepared["sdk"].resolve())
        for key in ("avd_dir", "avd_home", "emulator_home"):
            self.assertTrue(prepared[key].is_dir())
            self.assert_inside(prepared[key], self.workspace)
        self.assert_inside(prepared["avd_dir"], prepared["avd_home"])
        clone = prepared["avd_dir"]
        config = self.read_config(clone / "config.ini")
        self.assertEqual(SYSTEM_IMAGE, config["image.sysdir.1"])
        self.assert_inside(Path(config["disk.dataPartition.path"]), clone)
        registration = prepared["avd_home"] / (prepared["name"] + ".ini")
        self.assertTrue(registration.is_file())
        self.assertEqual(
            clone.resolve(),
            Path(self.read_config(registration)["path"]).resolve(),
        )
        self.assertEqual(
            {registration.name},
            {path.name for path in prepared["avd_home"].glob("*.ini")},
        )
        self.assertEqual(
            b"PUBLIC SNAPSHOT",
            (clone / "snapshots/default_boot/ram.img").read_bytes(),
        )
        self.assertEqual(
            b"PUBLIC OWNER SIDECAR", (clone / ".owner-sidecar").read_bytes()
        )
        (clone / "userdata-qemu.img").write_bytes(b"CLONE CHANGED")
        (clone / "snapshots/default_boot/ram.img").write_bytes(b"CLONE SNAPSHOT")
        (clone / "new-sidecar").write_bytes(b"CLONE SIDECAR")
        self.assertEqual(before, runner.snapshot(self.source))
        self.copy_tree.assert_called_once_with(self.source.resolve(), clone)

    def test_cached_launch_files_and_locks_are_removed_only_from_clone(self):
        for relative in (
            "hardware-qemu.ini",
            "emu-launch-params.txt",
            "multiinstance.lock",
            "snapshots/default_boot/snapshot.lock",
        ):
            (self.source / relative).write_bytes(b"PUBLIC RUNTIME FIXTURE")
        lock = self.source / "hardware-qemu.ini.lock"
        lock.mkdir()
        (lock / "pid").write_text("public fixture")
        before = runner.snapshot(self.source)
        prepared = runner.prepare_clone(self.source, self.sdk, self.workspace)
        clone = prepared["avd_dir"]
        self.assertFalse((clone / "hardware-qemu.ini").exists())
        self.assertFalse((clone / "emu-launch-params.txt").exists())
        self.assertEqual([], list(clone.rglob("*.lock")))
        self.assertEqual(before, runner.snapshot(self.source))

    def test_separate_clones_have_distinct_registered_names(self):
        first = runner.prepare_clone(self.source, self.sdk, self.workspace)
        second = runner.prepare_clone(
            self.source, self.sdk, self.root / "second-workspace"
        )
        self.assertNotEqual(first["name"], second["name"])
        for prepared in (first, second):
            self.assertTrue(
                (prepared["avd_home"] / (prepared["name"] + ".ini")).is_file()
            )

    def test_missing_source_and_config_are_rejected(self):
        self.assert_rejected(source=self.root / "absent.avd")
        (self.source / "config.ini").unlink()
        self.assert_rejected()

    def test_missing_sdk_is_rejected(self):
        self.assert_rejected(sdk=self.root / "missing-sdk")

    def test_sdk_binaries_must_be_executable_regular_files(self):
        for relative in ("emulator/emulator", "platform-tools/adb"):
            for invalid in ("missing", "directory", "nonexecutable"):
                with self.subTest(binary=relative, invalid=invalid):
                    sdk = self.root / f"sdk-{relative.split('/')[0]}-{invalid}"
                    self.make_sdk(sdk)
                    binary = sdk / relative
                    if invalid == "nonexecutable":
                        binary.chmod(0o600)
                    else:
                        binary.unlink()
                        if invalid == "directory":
                            binary.mkdir()
                    self.assert_rejected(sdk=sdk)

    def test_existing_workspace_is_never_overwritten(self):
        self.workspace.mkdir()
        self.assert_rejected()
        marker = self.workspace / "owner-output"
        marker.write_bytes(b"KEEP OWNER OUTPUT")
        before = runner.snapshot(self.workspace)
        self.assert_rejected()
        self.assertEqual(before, runner.snapshot(self.workspace))

    def test_workspace_cannot_be_source_or_its_descendant(self):
        for workspace in (self.source, self.source / "nested/workspace"):
            with self.subTest(workspace=workspace):
                self.assert_rejected(workspace=workspace)

    def test_workspace_alias_into_source_is_rejected(self):
        alias = self.root / "source-alias"
        alias.symlink_to(self.source, target_is_directory=True)
        self.assert_rejected(workspace=alias / "nested-workspace")

    def test_symlink_workspace_preserves_owner_output(self):
        owner = self.root / "owner-output"
        owner.mkdir()
        (owner / "keep.txt").write_text("KEEP")
        self.workspace.symlink_to(owner, target_is_directory=True)
        before = runner.snapshot(owner)
        self.assert_rejected()
        self.assertTrue(self.workspace.is_symlink())
        self.assertEqual(before, runner.snapshot(owner))

    def test_dangling_workspace_symlink_is_rejected(self):
        self.workspace.symlink_to(self.root / "absent-owner-output")
        self.assert_rejected()
        self.assertTrue(self.workspace.is_symlink())

    def test_symlink_source_is_rejected(self):
        alias = self.root / "owner-alias.avd"
        alias.symlink_to(self.source, target_is_directory=True)
        self.assert_rejected(source=alias)
        self.assertTrue(alias.is_symlink())

    def test_any_source_symlink_is_rejected_without_touching_its_target(self):
        original = (self.source / "userdata-qemu.img").read_bytes()
        for target in (
            self.source / "userdata-qemu.img",
            self.source / "snapshots",
            self.root / "missing-link-target",
        ):
            with self.subTest(target=target):
                link = self.source / "linked-entry"
                link.symlink_to(target, target_is_directory=target.is_dir())
                try:
                    with self.assertRaises(runner.RunnerError):
                        runner.prepare_clone(self.source, self.sdk, self.workspace)
                    self.assertTrue(link.is_symlink())
                    self.assertEqual(
                        original, (self.source / "userdata-qemu.img").read_bytes()
                    )
                    self.assertFalse(self.workspace.exists())
                finally:
                    link.unlink()

    def test_external_writable_image_paths_are_rejected_for_every_partition(self):
        external = self.root / "owner-image.img"
        external.write_bytes(b"KEEP EXTERNAL IMAGE")
        for key in WRITABLE_KEYS:
            for value in (
                str(external),
                str(self.root / "absent-external.img"),
                "../owner-image.img",
                "images/../../owner-image.img",
            ):
                with self.subTest(key=key, value=value):
                    self.write_config({key: value})
                    self.assert_rejected()
                    self.assertEqual(b"KEEP EXTERNAL IMAGE", external.read_bytes())
                    self.assertFalse(self.workspace.exists())

    def test_allowed_image_paths_are_rewritten_into_owned_clone(self):
        for index, value in enumerate(("<temp>", "", "images/internal.img")):
            with self.subTest(value=value):
                self.write_config({key: value for key in WRITABLE_KEYS})
                before = runner.snapshot(self.source)
                prepared = runner.prepare_clone(
                    self.source, self.sdk, self.root / f"allowed-{index}"
                )
                config = self.read_config(prepared["avd_dir"] / "config.ini")
                for key in WRITABLE_KEYS:
                    self.assert_inside(Path(config[key]), prepared["avd_dir"])
                self.assertEqual(before, runner.snapshot(self.source))

    def test_absolute_internal_image_paths_are_rewritten_away_from_source(self):
        self.write_config(
            {key: str(self.source / "userdata-qemu.img") for key in WRITABLE_KEYS}
        )
        before = runner.snapshot(self.source)
        prepared = runner.prepare_clone(self.source, self.sdk, self.workspace)
        config = self.read_config(prepared["avd_dir"] / "config.ini")
        for key in WRITABLE_KEYS:
            self.assert_inside(Path(config[key]), prepared["avd_dir"])
        self.assertEqual(before, runner.snapshot(self.source))

    def test_custom_internal_userdata_path_preserves_its_image(self):
        custom = self.source / "images" / "custom-user.img"
        custom.parent.mkdir()
        custom.write_bytes(b"EXISTING CUSTOM USER STATE")
        self.write_config({"disk.dataPartition.path": "images/custom-user.img"})
        before = runner.snapshot(self.source)
        prepared = runner.prepare_clone(self.source, self.sdk, self.workspace)
        config = self.read_config(prepared["avd_dir"] / "config.ini")
        actual = Path(config["disk.dataPartition.path"])
        self.assertEqual(prepared["avd_dir"] / "images/custom-user.img", actual)
        self.assertEqual(b"EXISTING CUSTOM USER STATE", actual.read_bytes())
        self.assertEqual(before, runner.snapshot(self.source))

    def test_failed_copy_does_not_remove_sibling_owner_output_or_change_source(self):
        owner = self.root / "owner-output.txt"
        owner.write_bytes(b"KEEP OWNER OUTPUT")
        before = runner.snapshot(self.source)

        def interrupted_copy(source, destination):
            destination.mkdir()
            (destination / "partial.img").write_bytes(b"PARTIAL COPY")
            raise OSError("public fixture copy failure")

        self.copy_tree.side_effect = interrupted_copy
        with self.assertRaises(OSError):
            runner.prepare_clone(self.source, self.sdk, self.workspace)
        self.assertEqual(b"KEEP OWNER OUTPUT", owner.read_bytes())
        self.assertEqual(before, runner.snapshot(self.source))
        self.assertFalse(self.workspace.exists())

    def test_snapshot_fingerprints_complete_tree_and_metadata(self):
        captured = runner.snapshot(self.source)
        self.assertIsInstance(captured, dict)
        expected_paths = {
            str(path.relative_to(self.source))
            for path in (self.source, *self.source.rglob("*"))
        }
        self.assertEqual(expected_paths, set(captured))
        for path in (self.source, *self.source.rglob("*")):
            with self.subTest(path=path):
                item = captured[str(path.relative_to(self.source))]
                metadata = path.stat()
                self.assertEqual(metadata.st_size, item["size"])
                self.assertEqual(metadata.st_mtime_ns, item["mtimeNs"])
                self.assertEqual(metadata.st_mode, item["mode"])
                if path.is_file():
                    self.assertEqual(
                        hashlib.sha256(path.read_bytes()).hexdigest(), item["sha256"]
                    )

    def test_snapshot_detects_same_size_content_change_with_original_mtime(self):
        image = self.source / "userdata-qemu.img"
        before = runner.snapshot(self.source)
        metadata = image.stat()
        image.write_bytes(b"X" * metadata.st_size)
        os.utime(image, ns=(metadata.st_atime_ns, metadata.st_mtime_ns))
        self.assertNotEqual(before, runner.snapshot(self.source))

    def test_snapshot_detects_file_and_directory_metadata_changes(self):
        for path in (self.source / "userdata-qemu.img", self.source / "snapshots"):
            for field in ("mode", "mtime"):
                with self.subTest(path=path, field=field):
                    before = runner.snapshot(self.source)
                    metadata = path.stat()
                    if field == "mode":
                        path.chmod(stat.S_IMODE(metadata.st_mode) ^ stat.S_IWGRP)
                    else:
                        os.utime(
                            path,
                            ns=(metadata.st_atime_ns, metadata.st_mtime_ns + 2_000_000_000),
                        )
                    self.assertNotEqual(before, runner.snapshot(self.source))

    def test_run_session_rejects_symlink_source_before_launching(self):
        alias = self.root / "owner-alias.avd"
        alias.symlink_to(self.source, target_is_directory=True)
        before = runner.snapshot(self.source)
        with patch.object(runner, "check_port"), patch.object(
            runner.subprocess, "Popen",
            side_effect=AssertionError("must reject source alias before launch"),
        ) as launch, patch.object(
            runner.subprocess, "run",
            side_effect=AssertionError("no runtime subprocesses in filesystem tests"),
        ):
            with self.assertRaises(runner.RunnerError):
                runner.run_session(alias, self.sdk, self.workspace)
        launch.assert_not_called()
        self.assertEqual(before, runner.snapshot(self.source))


if __name__ == "__main__":
    unittest.main()
