# Extended Pixel and Portal validation — 2026-10-08

Portal Toolkit 1.5.2-control-center (version code 8) is being validated on the
physical Pixel 9 Pro Fold over wireless ADB, with USB disconnected. The paired
Portal+ runs Android API 29 and Immortal 1.64.

**Status: final build and automated validation passed.** The Activity width
correction and photo-folder discovery/error/retry flow are installed and verified
on the real phone and Portal. The final installed build passed all 76 Android
tests, including six live phone/Portal integration tests, with zero skips.
Interactive render measurements are recorded below. Google Photos reached
Google's account chooser; owner account selection and the subsequent provider
flow remain pending.

This follows the [initial physical-phone validation](companion-pixel-validation-2026-10-08.md).
All work stays on the companion and Portal API branches. The original checkouts,
signing identities, saved pairings, owner photos, and existing app data are
preserved. No GitHub authentication, push, or pull request is involved.

## Bugs found and corrected

| Finding | Correction and evidence |
|---|---|
| Short Activity cards wrapped their text instead of filling the row | Shared group cards fill the width. The before test measured 303 px where 1,080 px was available; three native tests verify width, equal spacing, and lazy history scrolling |
| Activity history was composed inside one lazy item | Each operation is a separate lazy item with a stable identity |
| An installer confirmation was obscured by Immortal's active Dream | Dismiss the owned Dream deliberately before opening Android's confirmation. A real catalog install completed without touching the Portal |
| A delayed installation succeeded on Android but remained failed in Activity | Reconciliation updates terminal state and nested results from installed-package evidence; the real late install now reads succeeded |
| A catalog app without a pinned latest version claimed “Up to date” | Offer “Check for updates”; compare the downloaded APK's version and compatibility. The real Transistor check reported up to date without reinstalling |
| The catalog offered an Immortal self-update rejected by the paired API | Mark it Manage on Portal; leave that unsupported remote operation disabled |
| A Google picker could outlive or switch its original connection/folder | One owned coroutine captures the origin, drains stale callbacks, and cancels on connection loss |
| Picker events could disappear while the UI collector was absent | Buffered delivery retains events across collector gaps; stale events still fail ownership checks |
| A quick folder switch could use the previous folder's duplicate list | Read the captured destination directly; reject listing failure and suppress stale A/B list results |
| A failed photo listing could discard known photos/stats and appear empty | Retain the last known photos and stats, clear selection, and show an explicit Retry action; the synthetic missing-folder and retry flow passed on the phone/Portal |
| Photo discovery through the `/sdcard` symlink could silently return no images | Append a directory trailing slash while retaining the four-level traversal bound; the discovery regression failed before the fix and passed after |
| A destination appearing during import could be overwritten | Google imports request no-clobber publication, detect retained staging files, and count collisions as skipped |
| Partial backup restore was reported as successful or left the app list stale | Retain failed/partial outcomes, reject invalid archives before mutation, and refresh after confirmed app or settings changes |
| Session loss cancelled a restore but left its progress screen busy | Operation-owned cleanup releases progress on disconnect/replacement and prevents stale completion from clearing newer work; nine lifecycle regressions pass |
| A temporary outage prompted PIN setup | Explain saved pairing and retry separately from unpaired/unauthorized setup |
| Advanced Uninstall acted immediately | Confirmation retains the selected app and connection; device changes dismiss pending destructive dialogs |

The reviewed [Activity screenshot](screenshots/control-center/pixel-activity.png)
shows the real phone after the width fix. It excludes the system status bar.

## Physical device checks

- Installed the companion and Portal API APKs in place over Wi-Fi.
- Restarted the companion process. Its saved pairing reconnected without a new PIN.
- Stopped the Portal app with no install in progress. The native companion showed
  Offline; restarting Immortal restored Connected without pairing again.
- Installed the previously absent Transistor catalog app, checked its installed
  package, and refreshed the retained operation result.
- Repeated that install with Immortal's own Dream active. The confirmation flow
  exited that Dream and installed successfully without a manual Portal tap.
- Checked for a catalog update when the hosted entry had no latest-version pin.
  The operation succeeded with up to date.
- Removed only the Transistor test app through Device tools and verified its
  package was absent. No owner app was uninstalled.
- Opened the real Aurora Store Uninstall and Clear data dialogs, then cancelled
  both. Its `pm path` result was unchanged; no owner app or owner-app data was
  mutated.
- On the actual Portal, `find /sdcard` returned zero images with exit code 0;
  `find /sdcard/` found 13, including nine original PortalInstaller images and
  one synthetic QA image.
- Selected the discovered synthetic image folder, removed only its generated
  fixture, and reselected its cached entry. The phone showed an explicit read
  failure and Retry, with no false empty message or unknown storage statistics.
  Recreated the fixture; Retry loaded its image and cleared the error. Restored
  the original folder with nine photos, then removed the synthetic fixture and
  empty directory. Same-folder retention is independently covered by JVM tests.
- Tested API 29 `mv -n` using two synthetic files: destination bytes survived and
  the staging file remained on collision despite a zero exit code. Both fixtures
  were removed immediately.
- Selected 06:45 through the actual app's native clock controls and ordinary OS
  touches. Its scene draft updated to 06:45. Discarded the draft without saving
  a scene or schedule to the Portal.
- Opened Google Photos in Device tools. Google's account chooser appeared;
  no account was guessed and no OAuth token was exported.

Live integration tests restore settings, scenes, and photo-source changes in
`finally` blocks and remove their generated images. Pairing credentials remain
inside the app process.

## Verification and performance

The final companion build succeeded and its JVM run passed **422 tests**, with
zero failures, errors, or skips. The updated Portal branch passed **788 JVM
tests** and built successfully. The unchanged Jarvis branch previously passed
**363 JVM tests**. These are 1,573 JVM tests across the three branches; the Jarvis
count is previous evidence.
The physical listing retry check and final Pixel rerun both passed.

| Android evidence | Result and scope |
|---|---|
| Real Pixel Activity regressions | 3/3 passed: width, spacing, and lazy scrolling |
| Final complete Pixel run | 76/76 passed, zero failures/skips, with six live Portal tests enabled; 95.020 seconds; final installed listing/discovery build |
| Account-free UI fixtures on Android 14 emulator | 68/68 passed; 93.264 seconds |
| Management fixtures with OS-input confirmation | 25/25 passed on Android 14 emulator; 38.157 seconds |
| Pixel schedule confirmation stability | Three consecutive passes with OS-input confirmation |
| Isolated Android 17 native picker diagnostic | Selected hour/minute survived input validation; passed without opening a window |
| Native Google Photos provider flow | Reached account chooser; owner account selection pending |

An earlier Pixel schedule fixture requested 06:45 but saved 22:15 because its
Espresso-injected native-dialog tap did not reach the confirmation callback.
The corresponding emulator test passed. The real app accepted 06:45 through
ordinary clock touches. On the Pixel, the button's global-visible rectangle was
window-relative while its screen location included the dialog offset; using
explicit screen coordinates with Espresso still missed the callback. The final
test obtains screen coordinates and injects a real OS `input tap` through
UiAutomation's shell interface, off the UI thread. It does not call
`performClick` to bypass input delivery. The displayed time is asserted before
saving, and the saved schedule is checked independently. This passed three
consecutive Pixel runs and the final complete suite.

The management fixture also keeps its own window awake, since the production
activity's flag does not carry over to an isolated test activity. Locked-device
repeats could not find a Compose hierarchy and are excluded from valid UI
verification. No owner schedule was used or modified by this fixture.

The 68 emulator fixtures cover Activity, management panels, photo pagination,
destructive confirmations, draft restoration, preview states, and mirror
geometry. They do not replace the six live phone/Portal integration tests.

The sustained mirror experiment captured the Portal's real slideshow on the
Pixel. Its code was unchanged between the measured 1.5.2 build and the final
photo/confirmation corrections. Twenty-one passive samples span **601.54
seconds**, without injecting touches:

| Measurement | Result |
|---|---|
| Phone wakefulness | Awake in every sample |
| User activity timestamp | Unchanged throughout |
| Window holding the screen on | Companion in every sample |
| Activity / view-root count | One / one throughout |
| App PSS, initial / final | 186,267 / 188,268 KiB |
| App PSS range | 176,411–198,254 KiB (172.3–193.6 MiB) |
| App RSS range | 329,932–351,772 KiB |
| Recorder process generations | Four; three automatic recorder restarts |
| Android thermal status | Normal (0) throughout |
| Battery temperature | 30.6–31.1 °C |
| Battery level sample | 60% to 58%; not a battery-endurance measurement |
| Recent process CPU counters, companion / Immortal | 8.7–52% / 16–65% |

CPU values are Android's rolling process counters, not fixed-interval whole-device
utilization. The phone and Portal have other work and the slideshow changes
content. Memory finished about 2 MiB above its initial sample and fluctuated
within the reported range; this ten-minute observation does not prove an absence
of every possible leak.

Android legacy compositor counters cover a separate 699-second window. The
mirror's two display-rate groups report averageFPS values of 35.010 and 2.082,
with 14,078 and 266 frames and 2,723 and 113 discarded buffers respectively.
Moving and static content have different cadence, and late buffers can be
superseded before presentation. FrameTimeline coverage is insufficient to derive
video jank. These counters are neither a constant frame-rate guarantee nor native
Compose UI jank measurements.

Leaving the mirror page stopped its remote `screenrecord` process. Capture and
diagnostic frame collection were stopped afterward.

A separate native interaction measurement covered **20 tab interactions** on the
1.5.2 build before the final legacy photo-folder corrections; the main tab render
code was unchanged by those corrections. The
measured cold activity start was **540 ms** (`TotalTime`; the earlier candidate
measured 545 ms).

| Native render measurement | Result and scope |
|---|---|
| Aggregate rendered frames | 1,879 |
| Modern Android jank algorithm | 40 frames (2.13%) |
| Legacy Android jank algorithm | 177 frames (9.42%) |
| Aggregate frame histogram p50 / p90 / p95 / p99 | 6 / 8 / 15 / 61 ms |
| Aggregate GPU histogram p50 / p90 / p95 / p99 | 2 / 3 / 3 / 3 ms |
| Last view root's 34 retained completion frames | Median 4.49 ms; p95 38.09 ms; maximum 45.09 ms; one completed after its deadline |

The aggregate histograms cover all 1,879 frames. The 34 retained completion
samples cover only the last view root and are not percentiles for the whole run.
The two Android jank algorithms use different criteria. These are observed
rendering and launch measurements, not guaranteed input latency or provider
response times.

## Boundaries

Compose fixture tests exercise real controls and emitted requests, but do not
stand in for live provider or hardware tests. Backup archive tests use synthetic
packages/settings; factory reset, OTA, owner-app data clearing, and restoration
over owner installations are not executed.

Google Photos requires the owner's Picker API/OAuth setup matching this APK's
package and signing certificate. Cloud-source credentials, VPN/Internet
latency, physical hinge transitions, and long-term battery endurance require
separate environmental checks.

Legacy API 29 toybox performs its no-clobber existence check and rename as
separate syscalls. The tested collision paths preserve existing files; this is
not an absolute transaction against an external writer modifying that same
destination between those syscalls.
