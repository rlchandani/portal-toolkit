# Companion emulator source isolation

The numeric-settings acceptance run preserved backing disks but changed seven
generated sidecars in the original AVD. The new standard-library runner
`qa/isolated_emulator.py` uses a uniquely registered clone, separate AVD and
emulator homes, an explicit clone data directory, and only its owned process
group for shutdown. Cached launch paths and locks are removed in the clone.
Internal custom image filenames are preserved; paths outside the donor are
rejected before ownership or launch.

## Verified results

| Check | Result |
| --- | --- |
| Offline ownership / path / fingerprint guards | **23 passed**, including failed copy, existing output, symlink rejection and custom userdata preservation |
| Real clone boot | Android 14 / API 34, arm64-v8a, SDK emulator 37.2.13.0 on macOS |
| Native app readiness | Actual Apps, Home, Photos, Remote and Settings tabs visible at 360 × 800 dp |
| Installed APK | Exact readback of existing 1.7.1-music-preview / code 13; 14,722,589 bytes |
| Original AVD comparison | **0 changes**, whole directory contents, SHA-256, modes, sizes, mtimes and directory metadata |
| Normal owned shutdown | Process exited; owned AVD/emulator homes removed; private receipt/log retained |
| Original connections | Family USB and Wi-Fi transports remained; owned emulator-5558 absent after shutdown |

Installed APK SHA-256:
`b5a0feb0b0486c53553e3b8126417afa0fd45eae340efd116ab539283ddc10f4`.
Production source remains `3ae098044a0eaf4f42dadf2fff8184f3aa5a5646`.
No Android source, APK version, Portal configuration or installed Portal APK
changed. This unit's app run was unpaired.

The actual default-path clone run completed before the final custom-image
mapping correction. That correction passed a meaningful new regression plus
all prior cases; the default mapping and runtime flags are unchanged.
Independent source review found no concrete ownership or deletion defect in
the runner. A separate follow-up review of the custom mapping found no concrete
ownership or data-loss defect. These are source reviews, not process runtime
acceptance.

## Boundaries

The source-preservation proof covers the original `.avd` directory; it does not
claim every host file was fingerprinted. No real signal/timeout shutdown,
custom-image emulator boot, Linux/Windows emulator, fresh pairing, cold
reconnect, physical Pixel/folded layout, playback or FPS acceptance is claimed.
The existing Android build and 564 JVM / 33 rendered checks retain their earlier
scope. No new Android build was needed for this Python-only QA change.

Slot: **11:40:49–12:10:49 UTC**, including cleanup.
Private evidence: `/private/tmp/portal-companion-qa-isolation-20261102/`.
Usage and the offline command are in [qa/README.md](../qa/README.md).
