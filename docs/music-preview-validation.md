# Family Area music companion preview — October 19, 2026

## Latest interruption-policy checkpoint — October 19

Selective guards keep supported Pause/Volume/player selection/group removal and passive Queue/Lyrics available during local interruption, preserving reported metadata. Play/Next/Previous/Seek/queue selection/group joining remain denied. Pending, stale-observation, source/revision and membership guards remain in effect. Added five JVM cases and one Compose UI regression.

Full JVM gate: **538 tests / 72 suites / zero failures, errors or skips**. Debug and instrumentation APKs build successfully. The new physical Pixel UI regression failed before any Compose hierarchy was available, including one awake-device repeat; there is **no physical UI verdict**. This harness issue is parked within the bounded work window. Main/test APKs were automatically restored; pairing/data were never cleared. Phone retains prior preview `cbcc3b8df774e0890e4f7659fd587f975ac39e30e4abb0c1724db21b98bf9585`; candidate `4ce7199200bec393ec35d1fb4117c608d6fdebac72c059b14e0e6f1a2bba6187` is built only. Earlier accepted tests below belong to their recorded version and do not pass this new case.

Filtered receipts/logs and APK rollback copies: `/private/tmp/portal-ma-receiver-binding-source/`. Live receiver interruption propagation remains inactive; no new audible handoff or performance acceptance is claimed. No YouTube credentials or account cookies were copied.

Branch `codex/toolkit-music-20261019`, based on preserved companion checkpoint `8d18051`. The primary checkout and frozen companion branch were not changed. Preview version 12 / `1.7.0-music-preview`.

## Implemented

The companion reads the Portal music protocol into a strict typed model and presents authoritative queue, speakers, lyrics, release-only seek and volume controls. A single selected Portal owns music commands even when other settings target several Portals. Existing older-Portal playback remains the unsupported-endpoint fallback. No YouTube credentials or unrelated album art are copied into this screen.

Repository requests carry the selected session generation through read/write admission and response delivery. Controller commands bind the observed source, queue, track and room membership; synchronous admission rejects duplicate taps before coroutine launch. POST admission is distinguished from correlated server confirmation, with one coalesced readback every 250 ms for at most 12 seconds. Commands are never replayed on reconnect. Visible Home/Remote screens observe music; background and unrelated screens stop observing.

Source/player/queue replacement discards unfinished dialogs and drag gestures. A pending change keeps its speaker sheet visible while disabling new writes and displaying confirmation feedback. Terminal errors and refresh remain visible in that sheet.

## Executed evidence

- Final JVM/build gate after the visibility correction passed **533 tests, zero failures/errors/skips**, with both debug APKs assembled.
- Initial 16 Android music interaction tests passed. Three further source/group/gesture guards were added; the original source-binding behavior failed its regression before the corrected behavior passed. The 20-test final suite initially found a remaining sheet render guard; its one-line correction passed the final **20/20 Android UI tests in 29.079 seconds**. The exact test-run elapsed value is recorded in the private log.
- Physical paired Pixel 9 Pro Fold: **1 opt-in actual Family Area API test passed in 2.463 seconds** through the production repository/parser/controller. It read real music state, rejected a deliberately stale source revision with HTTP 409, then verified unchanged source/queue/track/pending state, all room volumes and group membership. This test used existing pairing credentials only inside the phone and did not issue an admitted playback change.
- Existing main/test APKs were backed up and signing compatibility verified before in-place installation. Pairings and app data were not cleared. The previous physical test APK was restored successfully.
- Final **20/20 UI tests passed** and the corrected preview installed on the Pixel successfully. The temporary owned emulator was shut down. UI tests run on an owned temporary emulator, not the locked physical phone.

## Resolved pending-capability review finding

The additional P2 was reproduced against the actual Compose panel: withdrawing all command/row capabilities while a command remained pending closed Speakers, Queue and Lyrics (**3/3 regressions failed**). One shared `readableSheet` rule now retains an already-open view during pending confirmation; current command permissions still govern all writes. Source/player/queue keys still reset views, Lyrics requires the current track, and terminal unsupported/lifecycle states still close views. Independent scoped review found no actionable remaining findings in the changed panel/tests.

The final full JVM gate passed **533 tests / zero failures/errors/skips**, debug and test assembly passed, and **23/23 Android UI tests passed in 38.87 seconds**.

Actual paired Pixel acceptance then passed **2 tests in 3.312 seconds**. In addition to stale-source rejection, the phone issued a valid one-step Family-only volume change, waited for a new source/revision/request-correlated `set_volume / confirmed` result with the actual target value, then restored the original volume. The probe checked unchanged other-room volume, group membership, queue and track, with no pending command after cleanup. Pairings stayed in the phone and the previous test APK was restored. These elapsed values cover whole test runs, not command latency or a p95 benchmark.

## Remaining gates

This is companion integration, not completion of the full music goal. Actual YouTube source authentication/playback, real transport/seek/groups, Calls/Jarvis receiver focus integration, live-playing retention, native visible latency/frame targets, complete household end-to-end acceptance and Living Area deployment remain open. No phone timing or audible playback performance claim is made from emulator or stale-command evidence. The receiver source candidate remains separate and uninstalled.

Private receipts and APK rollback copies reside under `/private/tmp/toolkit-music-validation/`; UI/physical logs are `/private/tmp/toolkit-music-ui-final-device.log` and `/private/tmp/toolkit-music-live-phone.log`. No credentials are included in this document or Git.

Installed preview APK SHA-256: `cbcc3b8df774e0890e4f7659fd587f975ac39e30e4abb0c1724db21b98bf9585`.

Additional private receipts: `/private/tmp/toolkit-music-capability-red.log`, `/private/tmp/toolkit-music-capability-green-build.log`, `/private/tmp/toolkit-music-capability-green-ui.log`, `/private/tmp/toolkit-music-valid-volume-phone.log`.


## Synchronous player selection and latest Pixel preview — October 19, 11:08 UTC

Immortal source selection applies synchronously and intentionally returns a new Connecting snapshot without a pending playback operation. The companion previously treated that valid response as a missing-correlated-admission error. It now publishes the matching selected source and says Player selected, without claiming playback confirmation. A missing pending response for transport/volume or the wrong selected player still fails. Two meaningful regression cases were added; scoped independent review found no remaining defect in this change.

Complete companion gate: **540 JVM tests / 72 suites / zero failures, errors or skips**; debug and Android-test APK assemblies succeeded. Candidate SHA-256 `c2933b966edad3f1793641bf105e1a33292543132774beeaacc79318d8fcbbd3` installed in place on the paired Pixel and the pulled installed APK hash/signer verified. Both saved pairings and target selection remain byte-for-byte unchanged; the actual prior APK is retained. No companion data was cleared.

The narrow OS foreground check identified the installed companion. A later screenshot attempt did not establish current window focus, so no new phone visual acceptance is claimed. The earlier physical Compose fixture remains parked; these JVM/build/install checks do not prove that fixture, audible playback, source login or music latency. Deployment/readback receipts and rollback APKs are private under `/private/tmp/portal-music-recovery/integrated-rollout/`.
