# Mobile UI layout audit — 2026-10-08

Portal Toolkit 1.5.2-control-center, version code 8. Changes remain on
`codex/portal-companion-control-center`; the original checkout and earlier commits
are preserved.

**Validation in progress:** the revised layout is built and installed on the
Pixel 9 Pro Fold. All 422 JVM tests pass. A complete 98-test Pixel run passed
before the final keyboard-send addition. The following 100-test run was
interrupted by the phone locking and is excluded from valid evidence. A
test-only runner now holds every fixture window awake. Final Pixel validation
and render measurements await owner unlock.

The final Android 14 emulator run reported **100 tests**, with all **92
account-free fixtures passing** and the eight paired-device checks disabled
by their opt-in conditions. It completed in 114.999 seconds. The final keyboard
submission and guard fixtures are included.

An initial emulator run hit a Compose `SlotTable` exception during photo-preview
fixture teardown. All seven preview tests and six Shell tests passed in isolation
(13 tests, 15.074 seconds), and the fresh full run passed. The one-off teardown
failure was not reproduced; it is recorded rather than claimed eliminated.

## Corrections

| Area | Change |
|---|---|
| Settings search, Advanced, category headings, and This phone | Explicit 14dp section spacing, including inside a single lazy-list item |
| Settings dropdowns | Label/help on the left; selected value and menu button on the right |
| Photo interval and other numeric settings | Inline trailing steppers with 48dp buttons; existing limits, wrapping, and apply callbacks retained |
| Phone Appearance | One full-width pill with three equal selectable segments and 1dp separators |
| Device tools Appearance | The same segmented control, retaining localized labels and existing preferences |
| Photos, Jarvis, and Scenes | Source, model, scene/action selectors, and schedule time use the same inline row pattern |
| Widget editor | Width and height use labeled inline steppers |
| Main pages and management cards | Consistent section spacing; action groups wrap instead of squeezing or overflowing |
| Advanced app/device pages | Long labels receive bounded space; dialogs reserve their action footer and scroll their content |
| Shell, Files, and Logcat | Short or enlarged-text viewports scroll controls while keeping nested lazy lists bounded; tall layouts retain weighted lists |
| Mirror keyboard sheet | Opens fully expanded; its form scrolls above the keyboard |
| Shell keyboard | Send uses the existing command classifier/handler, retains destructive confirmation, and ignores submission while running |

The settings renderer still consumes the paired Portal's schema. No setting
storage, wire keys, credential rules, or apply boundary was replaced.
Credential/address forms retain full-width text fields.

## Audit and regression scope

Real Pixel screenshots were reviewed for Home, Remote, Photos, Apps, Settings,
Home layout, Scenes, Jarvis, Snapshots, and Activity. All ten available Settings
categories were opened: Screensaver, Calendar, Immortal, Quick buttons, Device,
Sounds, Clock, Welcome, Sunrise alarm, and Home dashboard. The actual pairing
dialog was opened with the keyboard visible and then cancelled.

The Android 14 QA emulator was used for actual landscape Settings and Device
tools, including Shell, Files, Logcat, and Mirror. The short-height audit found
unreachable Shell actions and a partially expanded mirror input sheet. After
correction, the Shell page exposes its input/actions by scrolling, and the
mirror sheet scrolls to Send above the keyboard without a separate expansion
gesture. No command or mirror text was sent during these visual checks.

Twenty-four new Android regressions cover section gaps, measured button and
label bounds, equal segment widths/heights, mutual selection, narrow widths,
1.3× and 2× text, short 170dp viewports, lazy-list bounds, and the real emitted
save/apply callbacks. Source/model/scene fixture edits remain drafts until Save.
Keyboard tests use callback-only commands and verify confirmation/running guards.
The existing tests also cover confirmation dialogs, Activity rows, pagination,
draft recreation, native navigation, mirror geometry, six live Portal API checks,
and two paired tests of the real MainActivity.

## Device and data boundaries

The Pixel is tested over wireless ADB, with USB disconnected. Its original
1.1 font scale and five-minute screen timeout are preserved. Fixture wakefulness
uses window flags in the test APK; production MainActivity already holds its
visible window awake.

Owner photos, saved pairings, source credentials, installed applications, and
Portal configuration are preserved. No owner app was deleted or cleared, no
factory reset or OTA was run, and no cloud account was selected. Google Photos
account/import verification remains a separate pending provider check.

Physical fold/hinge transitions, TalkBack use, every possible live Files/Logcat
transfer state, cloud-source authentication, and long-term endurance are outside
this layout audit. Bounds/callback fixtures and short render samples do not prove
those behaviors.

Only reviewed Settings screenshots with the system status bar removed and
allowlisted test/performance summaries are included in delivery. Raw UI XML,
owner photo screenshots, credentials, and signing keys are excluded.
