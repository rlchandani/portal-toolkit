# Companion rendered settings — 2 November 2026

Scope: 09:27:30–09:57:30 UTC, including cleanup. Source branch
`codex/companion-settings-ui-20261102`, based on `d2ab3398`.
The unpaired settings category previously displayed the raw internal ID
`immortal`. An empty domain list now displays “Connect to load categories”;
the saved category and loaded domain titles are preserved.

Candidate: `com.portalinstaller`, **1.7.1-music-preview / code 13**,
minSdk 26 / targetSdk 35. App APK: **14,722,589 bytes**,
SHA-256 `b5a0feb0b0486c53553e3b8126417afa0fd45eae340efd116ab539283ddc10f4`.
Signer SHA-256:
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`.
APK metadata, signature and a full installed APK readback matched.

Fresh offline Gradle unit/debug/instrumentation assembly passed: **564 JVM
tests / 74 suites**, zero failures, errors or skips. Actual Android 14 / API 34
instrumentation passed **33/33 tests in 40.425 seconds**, across inactive
settings, layout/alignment, Portal-header and premium-control classes.
The emulator phone viewport was 360 × 800 dp.

Three new rendered integration checks traverse the actual
RegistrySettingsPanel → ViewModel → repository → client → group operation
pipeline using a strict, in-memory two-room transport. They verify captured
destinations while selection changes, validated inactive metadata and exact
acknowledgements, disabled-parent basic/Advanced visibility and re-enabling,
and invalid numeric input rejection. The numeric fixture deliberately uses
the supported `asText` renderer branch; it does not establish live Portal
metadata. Existing tests exercise right-side selectors, equal joined actions,
narrow/enlarged-text layout, long menus and viewing/destination separation.

Actual unpaired MainActivity before/after screenshots and XML confirm the
placeholder correction. An additional manual five-tab smoke check was not
completed: its first script used an obsolete resumed-activity key, and the
corrected script produced no receipt. No five-tab acceptance is claimed.

Only owned emulator-5558 received these APKs, in a read-only/no-snapshot-save
overlay. It was stopped at **09:50:04 UTC**. Its source AVD backing-file
modification times were unchanged; the original Hearth emulators and Family
USB/Wi-Fi connections remained present. No Portal configuration was changed.
Private receipts are in `/private/tmp/portal-companion-native-ui-20261102/`.

Live Family/Living paired writes, physical Pixel installation/layout/input,
audible playback and hardware performance remain unverified by this unit.
APK byte-size differences are not a performance improvement claim.
