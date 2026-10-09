# Native dropdowns and joined controls — 2026-10-08

Portal Toolkit 1.5.2-control-center, version code 8, on
`codex/portal-companion-control-center`. The previous layout changes remain
preserved in commit `8c8d998`.

The final installed build passed all **422 JVM tests** and **108 Android tests on
the Pixel** in 126.083 seconds, including the menu-width and long-menu-height
corrections. The final Android 14 emulator run reported 108 tests in
124.513 seconds: **100 account-free fixtures passed**, with the eight paired
checks disabled by their opt-in conditions.

## Interface changes

- Choice selectors use a native Material 3 menu with a non-editable anchor, a
  standard arrow, rounded field and menu surfaces, and a checked current selection.
  Menus scroll within a maximum 360dp height, with a smaller limit for short windows.
- Settings categories, Portal selection, and the full-width home-app selector
  open menus that match their fields. Compact row selectors allow wider menus
  so longer choices remain readable.
- Registry settings, photo sources, Jarvis models, scene/action choices, and app
  assignments share that selector. Raw values, advanced-option gating, drafts,
  credentials, and Save/apply callbacks retain their existing behavior.
- Widget, scene, and photo action menus use the same menu surface, anchored to
  their actual trigger. They remain action menus, without selection state.
- Previous / Play–Pause / Next, Back / Home / Slideshow, Home / Slideshow,
  Volume − / Volume +, and Scroll up / Scroll down use joined full-width pills.
  Segments have equal widths and matching heights, visible separators, and
  independent enabled guards.
- Action segments keep Button semantics. Appearance continues to use mutually
  exclusive RadioButton semantics because it represents a lasting preference.
- Long help text no longer shifts a selector below its label. Enlarged text can
  expand the row, and address/credential forms keep full-width input fields.

No new runtime dependency, blur effect, image asset, network request, preference
store, or Portal/Jarvis backend change was introduced.

## Verification

The current-choice regression fails on the previous installed APK because its
menu has no selected state; it passes on the replacement. Seven shared-control
fixtures verify raw-value callbacks, dismissal, disabled selectors/options,
long-menu scrolling, equal bounds with 2× fonts at 280dp, and momentary Button
semantics. Existing panel checks exercise draft/save behavior and inline
alignment with actual registry, photo, model, and scene controls.

The focused 16-test run exposed the long-help alignment issue; it passed after
that correction. Screenshot review then exposed the wide-field/narrow-menu
mismatch; a bounds assertion now covers matching menu width.

The Pixel subsequently exposed a real long-menu clipping issue: an unbounded
popup measured 2423px tall although it opened below the anchor. Its final row
remained below the screen after scrolling. The shared menu-height limit resolves
that reproduction; the regression checks the height and scrolls to and physically
taps the 60th choice at 2× text size. All seven shared-control tests passed
after the correction in 6.553 seconds.

One full Pixel attempt crashed in Compose `SlotTable` disposal during the
existing photo-preview Retry fixture. The same teardown exception had appeared
in the earlier emulator audit. All seven photo-preview checks passed in isolation
in 6.992 seconds. The failed attempt remains recorded and does not count as a
passing full run. This intermittent teardown issue is not claimed eliminated.
The subsequent fresh full Pixel run passed all 108 tests.

The full suite also covers prior spacing, compact layouts, keyboard Send,
mirror geometry, draft restoration, app confirmation dialogs, activity rows,
photo pagination/preview, six paired Portal API checks, and two paired checks
of the real MainActivity.

## Performance on the final Pixel build

Four Remote / Photos / Apps / Settings / Home cycles use 20 actual touches.
A separate sample opens and dismisses the category menu ten times with direct
touches at bounds observed before recording; it avoids repeated accessibility
dumps during menu timing. No preference changes during these samples.

| Measurement | Navigation sample | Menu sample |
|---|---:|---:|
| Cold Activity launch, `am start -W` TotalTime | 626ms | — |
| System frames rendered | 1878 | 1261 |
| System janky frames | 36 (1.92%) | 39 (3.09%) |
| Legacy janky-frame metric | 168 (8.95%) | 83 (6.58%) |
| Aggregate frame histogram p50 / p90 / p95 / p99 | 6 / 8 / 14 / 65ms | 6 / 7 / 13 / 61ms |
| Aggregate GPU histogram p95 | 3ms | 3ms |

The navigation dump's last-view-root retained buffer has 37 samples: median
4.62ms, p95 38.35ms, maximum 44.67ms, with two completing after their deadline.
That small buffer and the aggregate histograms cover different sets of frames.
End-of-sample total PSS is 193585KiB; this is not a memory-leak or endurance test.

An earlier read-only exposed text-field anchor recorded a 65ms menu p95 bucket
and 60 system-janky frames over ten openings/dismissals. The final non-editable
button anchor recorded 13ms and 39 respectively. Native button and text-field
animations produce different frame counts, and the menu sizing also changed,
so this is a short diagnostic comparison, not a percentage speedup claim.
The lighter anchor avoids text-input layout/focus work and retains native
Material scrolling, touch feedback, and dismissal.

These samples include animation/idle frames and rendering/presentation
scheduling. They do not measure Portal command latency, network completion,
fully loaded photo sources, or prove zero jank.

## Device and data scope

Physical tests use the Pixel 9 Pro Fold over wireless ADB and the paired Portal+.
The QA emulator uses Android 14. Fixture windows remain awake through the test
runner. The owner's 1.1 font scale, five-minute timeout, pairings, photo sources,
credentials, installed apps, and Portal configuration are preserved.

Only reviewed interface screenshots with the phone status bar removed and
allowlisted test/performance summaries are exported. Raw UI XML, owner photos,
credentials, keys, and compositor dumps stay excluded.

Physical hinge transitions, hands-on TalkBack use, cloud-provider account import,
active-track playback for every player, and long-term endurance are outside this
UI change. Callback fixtures and short render samples do not prove those cases.
