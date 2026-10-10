# Unified Portal menu validation

Version: 1.6.1-control-center, versionCode 11.

The top Portal menu is the sole selection entry. “View Portal” changes the library/status/settings values being displayed. “Send changes to” independently controls destinations for group settings and photo imports. Checkboxes, Select all and Clear selection retain the current viewed Portal and keep the menu open. Existing operation snapshots and per-Portal outcomes remain unchanged. Photos and Settings show a passive destination summary with no click action or second dialog.

The menu reuses the native bounded, scrollable premium dropdown. Its header remains 64dp, controls are at least 48dp, long names ellipsize within two lines, and destination persistence stays behind the existing repository's immediate-state/serialized-background-save handler. No network polling is added by opening the menu. Error messages and current usage docs point to the top menu.

## Verification

- Offline debug and Android test builds passed; unit gate: 488 tests, zero failures/errors/skips.
- Isolated Android phone-sized display: all 17 header/summary UI tests passed. Includes one entry on Photos/Settings, passive summary, independent view/destination callbacks, Select all/clear/empty states, connection/refresh behavior, 12-Portal scroll limits, 280dp width and doubled font size with bounded text and 48dp targets.
- One initial combined-page test assumed that reopening a native menu reset its remembered scroll position. The corrected assertion explicitly scrolls to the section being checked; production behavior was unchanged.
- Focused independent source review returned no verified findings. Main's local diff inspection confirms the intended branch scope.
- APK SHA-256: ae782c11af39b1816456be54bae6584f95dee0d1b36f1ed850f893667bb98540.

## Device install

Installed over the existing app on the connected Pixel 9 Pro Fold through Wi-Fi ADB. Package read-back confirms versionCode 11 / 1.6.1-control-center. App data was preserved. The Pixel reported a locked keyguard, so UI tests ran on the owned emulator; its lock screen was not bypassed. The temporary emulator was closed after the tests.
