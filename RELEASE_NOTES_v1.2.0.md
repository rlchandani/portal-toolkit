# Portal Toolkit v1.2.0

Phase 1 of the v1.2–v1.4 roadmap: a foundation refresh plus the first batch of
user-facing features. This release focuses on connection reliability, device
insight, and appearance — all built on a modernized architecture.

## New features

### Device dashboard & power controls
- Storage and RAM shown as donut gauges, with device uptime and Android SDK
  level at a glance.
- Power controls: reboot, reboot to recovery (both behind confirmation
  dialogs), turn the Portal screen on/off, and launch any installed Portal app
  from a picker.
- Reboots intentionally stop the connection monitor so the device can restart
  without the app fighting it with reconnect attempts.

### Connection health & auto-reconnect
- A background heartbeat pings the Portal on an interval and surfaces the live
  link state in a status chip on every screen.
- On connection loss the app automatically reconnects with exponential backoff
  (2s → 30s), reusing the last Wi-Fi host/port or the USB helper.
- A connection log records connect, loss, reconnect, and disconnect events.

### Appearance settings
- Theme mode: System, Light, or Dark, applied instantly without restarting.
- Optional dynamic color from the device wallpaper on Android 12+.

## Under the hood
- Toolchain refresh: compileSdk 35, Compose BOM 2024.12, Kotlin 2.0.
- `MainScreen` split into focused feature packages; top-level tabs now use
  Navigation-Compose.
- `PortalViewModel` split into feature ViewModels (connection, apps, backup,
  photos) sharing a single connection holder.
- Persistence migrated to DataStore (saved portals, app profiles, operation
  history, settings), migrating existing SharedPreferences data on first run.
- Direct ADB channel access centralized, removing reflection into dadb
  internals.

## Reliability
- New unit tests cover backup format/package-name validation, zip-slip path
  traversal guards, the consecutive-failure restore stop, the install
  compatibility policy, connection backoff, the heartbeat monitor, and settings
  persistence.
- Portal compatibility gating (`minSdk ≤ 29`, Android 10) is now a single
  tested source of truth (`InstallPolicy`).

## Compatibility
- Portal devices remain capped at Android 10 (API 29); apps requiring a higher
  minSdk are still blocked with a clear message.
