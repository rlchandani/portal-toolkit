# Companion validation — 2026-10-08

This records the initial emulator validation. The later
[Pixel 9 Pro Fold validation](companion-pixel-validation-2026-10-08.md) covers
version 1.5.1, physical-phone testing, wireless debugging, and hardware fixes.

## Builds and isolation

The companion baseline `4e1dadb` was clean before implementation. Its original
`roadmap-v1.2-1.4` branch remains intact. Work is on
`codex/portal-companion-control-center`, version `1.5.0-control-center` (code 6).
The Portal API is on `codex/portal-companion-api`, based on `c47e069`.
Jarvis management is on `codex/jarvis-companion-management`, version
`2.4.0-companion` (code 7).

All three debug builds succeeded. Portal and Jarvis were updated in place on the
connected Portal+ over Wi-Fi ADB. App data and configured credentials were retained.
The phone companion was installed and paired in an Android API 34 emulator.
No GitHub authentication, push, or pull request was used.

## Automated checks

| Project | JVM tests | Failures / errors / skipped |
|---|---:|---:|
| Companion | 242 | 0 / 0 / 0 |
| Immortal Portal | 769 | 0 / 0 / 0 |
| Jarvis | 363 | 0 / 0 / 0 |
| Total | 1,374 | 0 / 0 / 0 |

The six opt-in Android integration tests passed against the real Portal+ in
52.105 seconds. They used the companion's own saved pairing inside its process;
the session credential was not exported to the host.

Coverage includes:

- Protocol, capability, settings-registry, and catalog discovery.
- Jarvis status, Gemini 3.8 Live model readback, microphone permission, and
  configuration/readback of the already-selected model.
- Native layout round trip and rejection of a stale revision.
- Snapshot round trip and rejection of unsupported fields.
- Scene execution, retained results, and restoration of the scene list.
- Twenty repeated status requests.
- Streamed synthetic PNG upload, listing, thumbnail/original reads, collision
  protection, deletion, and local photo-source activation/readback/restoration.

The suite does not capture microphone audio or perform a cloud audio probe.
Wake-word audibility and microphone status are separate questions.

## Runtime findings and fixes

The original live photo import failed because the Portal had read access but
lacked Android write access. The shared Portal pairing card now offers **Allow
photo imports**; its normal permission request was exercised successfully.

An active Immortal DreamService covered Home after remote navigation. Explicit
launcher navigation now finishes only the owned Immortal dream and marks an
intentional exit. In the physical-device regression check, the phone's Home
button returned to HomeActivity, the current dream became null, and the lifecycle
log reported `USER_EXIT`. Meta/system dream callbacks remained outside that
ownership bridge.

Native startup now loads status and media first. Management pages load when
opened. One saved Portal reconnects automatically; visible offline sessions retry
every 30 seconds. Expired sessions require pairing again. Metadata polling stops
in the background, requests are cancelled on lifecycle exit, cursor input is
coalesced, and image/decoder queues and caches remain bounded.

New photo collections display as empty before the first upload creates their
folder. Photo permission errors preserve valid pairing. The collection chooser
and **Use for slideshow** action separate browsing/importing from activation.
Install retries remain unavailable while the previous installer may still run.

## Native UI checks

The installed companion was exercised through Home, Remote, Photos, Apps,
Settings, and Jarvis management. A restart reconnected the single saved Portal.
Turning the phone's Advanced switch on revealed the MQTT category; turning it
off hid the category. The Portal's existing Advanced preference was retained.

The Android photo picker imported a synthetic PNG into the managed collection.
The gallery and preview displayed it, the delete confirmation removed it, and
the managed collection was checked empty afterward. The integration suite also
activated a synthetic photo folder, read it back, and restored the original
slideshow source. No owner photo was deleted.

Remote typing was tested against the physical Portal's focused app-search field:
sending `VLC` from the phone filtered the Portal's grid to VLC. The remote input
service correctly rejected typing when no editable field had focus. The phone
now explains this requirement next to its text-entry field.

Captured native screens from the paired Android API 34 emulator:

- [Home](screenshots/control-center/home.png)
- [Apps](screenshots/control-center/apps.png)
- [Settings](screenshots/control-center/settings.png)
- [Jarvis](screenshots/control-center/jarvis.png)

## Performance evidence and limits

The final authenticated status sample from the emulator measured median
**1,002 ms**, p95 **1,007 ms**, maximum **1,007 ms**, for 20 requests.

A separate credential-free comparison reproduced this delay before application
work: the unauthorized response median was **12.3 ms from the Mac**, **10.0 ms on
Portal loopback**, and about **992 ms from the emulator**. Emulator socket traces
showed delay during TCP connect and waiting for the first response. Its configured
artificial network latency was zero. The precise virtual-network cause remains
unresolved; these measurements do not establish physical-phone response time.

No physical phone was connected for testing. Real-phone rendering, battery use,
and LAN/VPN performance still need validation on that phone. Catalog downloads
and installs were checked through automated policy/integration boundaries;
this run did not install an unrelated catalog app on the owner's Portal.
The existing ADB maintenance tools remain available and have automated coverage,
but every maintenance command was not executed against the owner's device.

New Android widget binding still requires Portal consent. Snapshot files omit
credentials, photo files, and third-party widget data. Scene schedules use
inexact Android alarms, and an active system installer cannot safely be cancelled.
