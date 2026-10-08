# Extended Pixel and Portal validation — 2026-10-08

Portal Toolkit 1.5.2-control-center (version code 8) is being validated on the
physical Pixel 9 Pro Fold over wireless ADB, with USB disconnected. The paired
Portal+ runs Android API 29 and Immortal 1.64.

**Status: test candidate, not final sign-off.** The Activity width correction is
installed and verified on the real phone. The last complete Pixel run passed
75 of 76 tests; schedule time confirmation is unresolved. The Pixel subsequently
locked behind its PIN/fingerprint screen. The final physical rerun, provider
check, and interactive render measurement require the owner to unlock it once.

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
- Tested API 29 `mv -n` using two synthetic files: destination bytes survived and
  the staging file remained on collision despite a zero exit code. Both fixtures
  were removed immediately.

Live integration tests restore settings, scenes, and photo-source changes in
`finally` blocks and remove their generated images. Pairing credentials remain
inside the app process.

## Verification and performance

The final companion JVM run passed **417 tests**, with zero failures, errors, or
skips. The updated Portal branch passed **788 JVM tests** and built successfully.
The unchanged Jarvis branch previously passed **363 JVM tests**. These are 1,568
JVM tests across the three branches; the Jarvis count is previous evidence.

| Android evidence | Result and scope |
|---|---|
| Real Pixel Activity regressions | 3/3 passed: width, spacing, and lazy scrolling |
| Last complete Pixel run | 75/76 passed, with live Portal tests enabled; 95.898 seconds |
| Account-free UI fixtures on Android 14 emulator | 68/68 passed; 93.264 seconds |
| Management fixtures after the keep-awake adjustment | 25/25 passed on Android 14 emulator; 39.363 seconds |
| Isolated Android 17 native picker diagnostic | Selected hour/minute survived input validation; passed without opening a window |
| Final physical suite and native Google Photos check | Pending owner unlock |

The failing Pixel schedule fixture requested 06:45, but its saved scene still
contained 22:15. The corresponding emulator test passed. An isolated Android 17
widget check showed that setting and validating the time preserves 06:45; that
does not establish whether the visible dialog receives its confirmation tap.
The UI regression now asserts the displayed time before saving. The management
fixture also keeps its own window awake, since the production activity's flag
does not carry over to an isolated test activity. The locked-device repeats
could not find a Compose hierarchy and do not constitute valid UI verification.
No owner schedule was used or modified by this fixture.

The 68 emulator fixtures cover Activity, management panels, photo pagination,
destructive confirmations, draft restoration, preview states, and mirror
geometry. They do not replace the eight live phone/Portal integration tests.

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
diagnostic frame collection were stopped afterward. The companion's measured
cold activity start was 545 ms on the earlier candidate; final interactive render
measurements are pending the phone unlock.

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
