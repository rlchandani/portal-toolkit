# Multi-Portal device validation — 9 October 2026

The development companion `1.6.0-control-center` (`versionCode 10`) was installed
over Wi-Fi on the paired Pixel 9 Pro Fold. Three opt-in instrumentation tests used
the production `CompanionRepository`, group coordinator, HTTP client and saved
room sessions. Pairing credentials stayed in the phone process. An in-memory
store wrapper prevented test selection changes from overwriting the user's
saved pairings or target preferences.

Both Family Area and Living Area confirmed a temperature-unit change. The test
then read both registry values, restored each room's own original value through
the settings API, and verified the restoration. Changing the selected target
after submission did not redirect the captured write. The grouped operation
and confirmation took 488 ms in the final run.

A generated 160 × 90 PNG with a unique test filename was sent through the
coordinator to both rooms' own saved import folders. Both targets confirmed
success; the test verified the exact paths and sizes using metadata only.
Two fresh streams opened and were disposed. The grouped uploads took 558 ms in the final run.
Cleanup removed only the generated UUID filename from each room and verified
its absence. Full slideshow-source responses matched their initial values.
No existing household image was fetched, replaced or deleted.

Both updated rooms returned stable scene-list revisions and rejected an intentionally
stale replacement with HTTP 409 / `stale_revision`. Their scenes and revision
remained unchanged. This test posted the existing list with an invalid revision;
it did not add a scene or change a schedule.

The final physical suite reported `OK (3 tests)` in 3.286 seconds. These timings
describe API operations, not rendered touch latency or sustained upload
throughput.

The owned API 34 read-only emulator also reported `OK (34 tests)` in 52.27
seconds across:

- Multi-Portal checkboxes, Select all, captured retry and cancellation UI.
- Photo collection discovery, pagination and image metadata invalidation.
- Settings card gaps, right-aligned controls and large-text layouts.
- Source/scene/Jarvis selectors and premium dropdown/action components.

After the final scene/editor refinements, the emulator additionally passed
`OK (4 tests)` in 7.807 seconds: the three Manage Portals checks plus the scene
editor test that retains its draft and opening revision during a concurrent
remote change. There are 35 distinct passing UI checks across these runs.

The physical phone remains securely locked, so these results do not establish
that its visible multi-Portal screens were exercised. The emulator's temporary
read-only instance does not modify the original saved AVD.

Offline, unsupported, partial-key rejection, stale retry, selection races and
removed-session scenarios are covered by the repository's deterministic JVM
tests. The physical test did not disconnect a household Portal to simulate
failure. Final source verification passed 488 JVM tests with zero failures/errors/skips,
plus the debug and AndroidTest APK builds. Log:
`/private/tmp/companion-scenes-handoff.log`. The final APK was installed on both
the physical Pixel and the owned emulator before the final live/UI checks.
