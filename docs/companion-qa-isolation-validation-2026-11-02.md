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
## Subsequent current-layout acceptance

An independent owned clone ran the unchanged **1.7.1-music-preview / code13**
candidate. Installed readback matched **14,722,589 bytes** and SHA-256
`b5a0feb0b0486c53553e3b8126417afa0fd45eae340efd116ab539283ddc10f4`.
The existing test APK was **1,669,619 bytes**, SHA-256
`117a084a9f77fb638896cc69bf9de20678081bdaed716a1e9ffe3121119471d8`.

**Seven additional rendered tests passed in 11.174 seconds**, using
`SelectorPanelLayoutUiTest` (four methods, 360 × 640dp, 1.3× text) and
`ManagementPanelLayoutUiTest` (three methods, 360dp width, 1.3× and 2× text).
They cover selector alignment, equal theme segments, saved draft payloads,
management-action label fit and pagination callbacks. Neither class was in the
earlier 33-test receipt. These are callback fixtures, not live Portal transport,
provider login, photo import, physical Pixel or performance acceptance.

After instrumentation exited successfully, Ctrl+C interrupted the still-live
owned runner. It exited **130** after cleanup; its receipt reports
`ready=true`, `sourceUnchanged=true`, `ownedResourcesRemoved=true`. A subsequent
ADB inventory found the owned emulator absent and the original Family USB/Wi-Fi
transports retained. No APK source/version or real room configuration changed.
Private logs and receipts are in
`/private/tmp/portal-companion-current-layout-20261102/`.

## Subsequent compact Tools acceptance

The same unchanged candidate passed **eight additional rendered methods in
11.157 seconds** on a fresh owned API34 clone. `AdvancedPanelLayoutUiTest`
checks two mirror/app-action layouts at 280 × 440dp and 1.3× text.
`ShortToolLayoutUiTest` checks six cases at 360 × 170dp: keyboard Send and
confirmation/running guards at 1.3×, plus four reachability/list/callback cases
at 1.3× and 2×. Tool actions retain measured 48dp targets; the shared layout
fixture keeps its 200-item lazy list bounded and scrollable.

Callbacks are fixtures, including the destructive-command confirmation case:
no shell command, Portal uninstall, file operation or real room request runs.
The shared Files/Logcat layout fixture is not their complete workflow acceptance.
Neither class appeared in the earlier 33- or seven-test logs. All three runs use
the exact app hash above, totaling **48 unique methods across separate runs**.
This is not a single full-suite result, physical Pixel or performance evidence.

Owned Ctrl+C cleanup again exited130 with source unchanged, clone removed,
owned emulator absent and both original Family transports retained. Current
private instrumentation, artifact and cleanup receipts are in
`/private/tmp/portal-companion-compact-tools-20261102/`.
