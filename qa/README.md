# Isolated native companion QA

`isolated_emulator.py` boots a disposable clone of an existing Android AVD.
It isolates generated boot/modem files as well as writable disk images, checks
the original directory by SHA-256 and file/directory metadata, and stops only
the emulator process group it started. It does not pair with or write to a Portal.

Requirements: Python 3.9+, an installed SDK with executable `emulator/emulator`
and `platform-tools/adb`, a stopped source AVD, and a new private workspace
outside that AVD. Explicit writable image paths must stay inside the source.
Source symlinks, external writable images and existing output paths are rejected.
macOS uses a copy-on-write clone; the portable full-copy fallback is covered by
filesystem tests but has not been verified with a real non-macOS emulator.

Run the offline guards:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest qa.test_isolated_emulator -v
```

Run from the Toolkit root with your actual paths:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 qa/isolated_emulator.py \
  --sdk /path/to/android-sdk \
  --source-avd /path/to/stopped-source.avd \
  --workspace /private/tmp/new-owned-companion-qa \
  --port 5558 \
  --boot-timeout 90 \
  --hold-seconds 60
```

Wait for the ready JSON before addressing the reported emulator serial.
Use that exact serial for APK installation or UI checks. The hold period is the
available interaction window; it does not run app tests automatically.
The SDK and Android homes are isolated without changing the user's `HOME`.

On normal exit or Python interruption/error, cleanup targets the owned process
group and clone directories. A retained private `receipt.json` reports readiness,
source preservation and clone cleanup; `emulator.log` retains diagnostics.
Boot timeout is bounded to 1–300 seconds and the hold interval to 0–1200 seconds.
An even console port and its adjacent ADB port must be available.
Hard termination of the Python process or host shutdown cannot run its cleanup.

Real macOS/API 34 verification on 2 November 2026 passed native unpaired app
readiness and exact installed-APK readback, with zero changes in the original
AVD directory. Custom internal image preservation is an offline regression
check; actual interrupted/timeout shutdown, other hosts, fresh pairing,
cold reconnect and physical Pixel behavior remain unverified.
See [the scoped receipt](../docs/companion-qa-isolation-validation-2026-11-02.md).
