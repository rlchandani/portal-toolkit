# Compact header, Remote and playback validation

Validated October 8, 2026 on the physical Pixel 9 Pro Fold, the connected Portal+, and an
Android emulator. Local branch: `codex/portal-companion-control-center`. The previous
companion implementation is preserved at `ca68f65336dfc66d7f8e91ca271f2dd0e7f8678e`.

## Delivered behavior

- A single compact native header combines Portal selection, connection status and refresh.
  Its menu retains Add, Disconnect and Forget, including while the selected Portal is offline.
- Remote uses joined Back/Home/Slideshow controls, a smaller touchpad and joined scroll
  controls. Text entry opens a native keyboard sheet when requested.
- Text drafts and outstanding sends belong to the ViewModel, survive activity recreation,
  reject duplicate sends and clear only after an acknowledgement for the same device session.
  A newer draft is not cleared by an older acknowledgement. This is not a process-death
  persistence guarantee.
- Now Playing has bounded album artwork, artist/album metadata, joined icon playback
  controls, seek when the media session explicitly supports it, and an absolute media-volume
  slider when supported by the Portal. Older servers keep relative volume controls.
- Seek and volume commit on gesture completion. Polls do not overwrite an active drag;
  cancelled native drags send no command, and reconnect discards an uncommitted drag.
- Artwork downloads are versioned and use the existing bounded session-owned image cache.
  Compressed responses are limited to 512 KiB and decoding runs off the UI thread with a
  maximum sampled side of 256 pixels. The Portal returns a stale-version response rather
  than serving a new track's artwork under an older track's key.

Portal API changes are preserved in `c2e74c1` and `7435aa2` in the separate Portal checkout.
The later `78f2de8` adds only the WhatsApp feasibility report. It does not change this APK.
No new persisted Portal setting was introduced.

## Verification results

| Check | Result |
| --- | --- |
| Companion debug and instrumentation builds | Passed |
| Companion JVM tests | 452 passed, no failures/errors/skips |
| Portal debug build and JVM tests | 818 passed, no failures/errors/skips |
| Physical Pixel full instrumentation suite | 138 passed, no skips |
| Emulator full instrumentation suite | 127 passed, 11 expected live-device skips |
| Isolated playback/Remote gesture and lifecycle cases | 18 passed on each device |
| Pixel installed production and test APK hashes | Match the final local APKs |
| Portal installed production APK hash | Matches the final local APK |
| Portal critical preferences | All 13 checked preference files match the pre-test backup |
| Pixel awake behavior | Awake with MainActivity's `KEEP_SCREEN_ON`; global settings preserved |

The Pixel suite exercised all 11 live-Portal cases: six paired-device round trips, two
silent-fixture playback cases, and three connected native UI cases. The emulator skipped
those same cases because it does not have the paired physical Portal.

Meaningful failure-before-fix checks covered cancelled slider commands and stale artwork
responses. Native controls were also checked at narrow widths and enlarged text, with
slider taps, native cancellation, reconnect during a drag, keyboard activity recreation,
and delayed text acknowledgement. A keyboard focus request before its sheet window attached
and a narrow-header overlap were corrected before the passing final suites.

An intermittent PhotoPreview `SlotTable` teardown crash had occurred in an earlier
validation round. All seven PhotoPreview cases passed in both fresh final suites, but these
passes do not establish that the older intermittent teardown issue is permanently resolved.

## Physical-phone performance

A cold launch followed by four Remote/Photos/Apps/Settings/Home cycles used 20 real touches.
The final sample recorded:

- Cold-start TotalTime: **547 ms**.
- Android aggregate: **1,896 frames**, **32 janky frames (1.69%)**; legacy metric **8.54%**.
- Aggregate frame histogram: p50 **6 ms**, p90 **8 ms**, p95 **15 ms**, p99 **69 ms**.
- GPU histogram: p95 **3 ms**, p99 **4 ms**.
- Process PSS: **211,269 KiB**.

The preceding premium-controls sample using the same navigation method recorded 626 ms
and 1.92% modern jank. This sample improves cold start and aggregate jank modestly; its
p95/p99 frame times are slightly higher than the previous 14/65 ms. It is not evidence
that every interaction became faster.

Ten Portal-selector open/dismiss cycles recorded 1,244 frames, 2.89% modern jank, p95
8 ms and p99 48 ms; GPU p95 was 2 ms. Ten keyboard open/dismiss cycles recorded 2,651
frames, 1.43% modern jank, p95 11 ms and p99 46 ms; GPU p95 was 2 ms. No text or Portal
commands were sent in those checks. These short samples include rendering/window/IME
scheduling, not network command latency, and are not a long-duration performance guarantee.
Raw device dumps and owner data remain private; delivered measurements contain allowlisted
aggregates only.

## Build identity and preservation

| Artifact | SHA-256 |
| --- | --- |
| Companion production APK | `f87b91141d0702fdaa0a00faa76add275fd972abb6aea8d929cfdaaf23f323bd` |
| Companion instrumentation APK | `ed933705fba4e87a11afedcac65b2ebc847dc415ce79910eafb47f2a4d48e72a` |
| Portal APK | `721170d003417c9b922964f8b176c8a2b09b30b52bddf2f33c306f19c3da5004` |

Companion package: `com.portalinstaller`, version code 8, `1.5.2-control-center`.
Signing certificate SHA-256:
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`.
Portal package: `com.immortal.launcher.debug`, version code 58, `1.64`.

The checked Portal preferences cover cameras, home dashboard/widgets, Home Assistant,
wallpaper, screensaver, settings, layout, notes, timers, todos, remote roster and pairing.
Phone font scale remains 1.1, screen-off timeout 300,000 ms and plugged-in stay-awake mode 0.
The companion keeps its own visible activity awake.

## WhatsApp widget status

The user confirmed that native Portal WhatsApp calling works. Inspection of the installed
calling app established that its outgoing-call entry point requires Meta's trusted caller;
it is not an unrestricted recipient/audio/video API. No calling widget or bypass was added.
The requested seamless native flow remains unavailable through the inspected interface.

A separate linked WhatsApp client is a candidate only. Installing/linking one awaits the
user's preference, and its ability to avoid Meta Home or eliminate a final WhatsApp call
tap has not been verified. The working native WhatsApp app and account were preserved.
