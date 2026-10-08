# Pixel 9 Pro Fold companion validation — 2026-10-08

Portal Toolkit **1.5.1-control-center** (version code 7, package
`com.portalinstaller`) is installed and paired on the physical Pixel 9 Pro Fold,
running Android 17 / API 37. The connected Portal+ runs Android API 29 and
Immortal 1.64. The final installation and Android tests used wireless ADB with
the phone's USB cable disconnected.

Work remains on the isolated local branch
`codex/portal-companion-control-center`. The original companion checkout and
baseline branch are preserved. No GitHub authentication, push, or pull request
was involved.

## Automated verification

The final debug app and Android-test APK builds succeeded. Results:

| Check | Tests | Failures / errors / skipped |
|---|---:|---:|
| Companion JVM | 277 | 0 / 0 / 0 |
| Pixel Android tests | 36 | 0 / 0 / 0 |

The 36 Android tests passed over Wi-Fi in 38.882 seconds after the final
keep-screen-on change. They comprise six live Portal integration tests, two
native navigation/settings tests, 17 draft-restoration tests, seven photo-preview
tests, and four mirror-surface/input tests. Regression fixtures exercise retry
after a failed image request, loading cancellation, rotation, credential
exclusion from saved state, mirror sizing, coordinate mapping, and ignored
letterbox touches.

The existing Portal and Jarvis branches previously passed 769 and 363 JVM tests
respectively; they were not changed by this Pixel follow-up. Including the
companion's final results, those branches have 1,409 passing JVM tests.

Live tests use the companion's saved pairing inside its process. The session
credential is never exported to the host. They cover protocol/capabilities,
settings and catalog discovery, layout and snapshot round trips, stale revision
and unsupported-field rejection, scene execution and retained results, streamed
synthetic photo upload/read/delete, and photo-source activation/restoration.
Jarvis model readback and microphone permission are checked without capturing
audio or making a cloud audio probe.

## Actual app and device checks

The native `MainActivity`, rather than a browser mock, was exercised through all
five tabs and the Layout, Scenes, Jarvis, Snapshots, and Activity pages. Settings
were applied, externally refreshed, and restored by the native test.

Manual checks on the Pixel and physical Portal confirmed:

- An ordinary unsaved source-folder draft survives portrait/landscape/portrait
  rotation. Restarting the app discards that unsubmitted draft.
- Android's real document picker imports a synthetic PNG. The native gallery and
  fitted preview display it. Cancelling deletion preserves it; confirming
  deletion removes only that test photo.
- The existing `/storage/emulated/0` source folder is accepted.
- Searching installed apps for VLC and choosing Open launches VLC's onboarding
  activity on the Portal. The Portal was subsequently returned to Immortal Home.
- The advanced tools return control stays below the Pixel status bar.
- A wireless ADB connection inside Device tools browses the Portal's actual
  Android, Download, and Pictures directories.
- Logcat creates a stream only while explicitly running. Backgrounding the phone
  reduces the Portal's logcat process count from two to the original one; returning
  to the app leaves the stream stopped until Start is chosen.
- Screen mirroring preserves the Portal's 3:2 image. A real tap on the measured
  surface reaches the Portal and dismisses its screensaver into HomeActivity.
- Snapshot export through Android's Save picker produces a 5,660-byte JSON file.
  Android's Open picker selects that file and the native target confirmation is
  exercised. The independent live integration test verifies snapshot application
  and settings/layout readback.
- An Android OPENED fold-state override produces a 2076 × 2152 layout with the
  connected native Home and bottom navigation intact. This is a simulated
  configuration on the real phone, not a physical hinge-transition test. The
  override was reset to the phone's actual closed state afterward.

All live-test settings, scenes, and photo-source changes were restored.
No owner photo was deleted.

## Hardware findings and fixes

| Observed issue | Final behavior |
|---|---|
| Unsaved settings/source edits disappeared on rotation | Ordinary drafts use saved state; credentials stay memory-only |
| Storage root aliases were rejected | Accepted public aliases normalize to `/sdcard`; exact file paths retain whitespace |
| Failed photo previews stayed on a spinner; first retry could fail to update | Explicit loading/error/image states, Retry, stable observed state |
| Advanced tools return control overlapped the Android 17 status bar | Status-bar inset is consumed before the control |
| File browser listed the `/sdcard` symlink instead of its contents | Requested directory listings follow the root alias without dereferencing child links |
| Mirror stretched the Portal image vertically | Aspect-fitted surface and shared geometry for tap/drag mapping |
| Refresh fetched unrelated data; background tools kept doing work | Page-specific refresh, lazy catalog, lifecycle cancellation, foreground-only polling/discovery/logcat |
| Companion slept during remote use | Android keeps its visible activity window awake |

The Android test runner was also updated to support Android 17's input API.
This change is limited to test dependencies.

## Performance measurements

These are samples from this LAN and phone, not a universal performance guarantee.

| Measurement | Result |
|---|---|
| Physical-phone cold activity start, `am start -W` | 596 ms total; 598 ms wait |
| 20 authenticated Wi-Fi status requests, median | 63 ms |
| Same sample, p95 / maximum | 84 ms / 433 ms |
| Native app render sample | 1,349 frames, 76 deadline misses (5.63%) |
| Render duration, median / p90 / p95 / p99 | 6 / 13 / 25 / 85 ms |
| GPU duration, median / p95 / p99 | 2 / 3 / 7 ms |
| App memory after native/tool/picker use | 182,227 KiB PSS; 300,912 KiB RSS |
| Window/activity count at that measurement | One activity, one view root, zero WebViews |

Rendering was reset before a mixed manual session including tools, document
pickers, navigation, and app launch. This aggregate includes transition outliers;
it does not establish zero jank or isolate a specific animation. The earlier
roughly one-second emulator network sample does not represent this phone's LAN
performance.

The code avoids unrelated refreshes, stops hidden work, preserves bounded image
and log buffers, and maps input on a measured mirror surface. Battery endurance,
thermal behavior, sustained video frame rate, and VPN latency were not measured.

## Screen sleep

The phone's five-minute timeout is enforced by device administration. A temporary
system stay-awake setting did not keep it awake and was restored to its original
zero value. The app instead uses Android's `FLAG_KEEP_SCREEN_ON` while its
activity is visible, including Device tools. The physical phone remained
**Awake after 352.6 seconds of uninterrupted idle**, beyond the enforced
300-second timeout. Its last-user-activity timestamp stayed unchanged and
WindowManager identified the companion as the window holding the screen on.

Pressing Android Home released the request (`mHoldScreenWindow=null`); returning
to the companion restored it. Manual locking remains under Android's control.
No device-admin policy was disabled and no background wake lock or synthetic
periodic touch was added. The generated phone snapshot and synthetic input photo
were removed after testing.

## Screenshots and remaining scope

Reviewed captures from the real phone:

- [Folded native Home](screenshots/control-center/pixel-home.png)
- [Wide configuration Home](screenshots/control-center/pixel-wide-home.png)
- [Synthetic photo preview](screenshots/control-center/pixel-photo-preview.png)

Notification content, pairing PINs, private session files, and the owner's
dashboard captures are excluded.

Factory reset, destructive maintenance, OTA execution, unrelated catalog
installs, cloud-source credential flows, Google Photos OAuth, and physical hinge
transitions were not executed. These checks therefore do not claim that every
external integration or destructive operation has been tested on this phone.
Third-party widget binding still needs Portal consent, snapshots omit private
credentials/photos/widget data, and Android scene alarms remain inexact.
