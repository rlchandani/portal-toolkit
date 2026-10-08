# Immortal companion control center

The Android app opens a native control center. Home, Remote, Photos, Apps, and
Settings share one activity and use the Portal's approved neutral light/dark palette.

## Pairing

On the Portal, open **Control From Your Phone** to enable the remote and show a
fresh six-digit PIN. On the phone choose **Add a Portal**, select a discovered
device or enter its address, and enter the PIN. A pairing link containing the PIN
can also be pasted. The PIN expires and is single use; the resulting session
persists across restarts.

Saved pairings are held in private storage excluded from Android backups. Select
another Portal in the top device menu. Every operation captures its device
session before it starts. Changing devices invalidates pending results.

Local addresses default to port 8723. Existing private HTTPS endpoints and VPN
addresses may be entered explicitly. This app does not create an Internet relay.

## Daily controls

- **Home:** slideshow, home layout, scenes, Jarvis, snapshots, activity, media.
- **Remote:** pointer movement, tap, scrolling, keyboard, Back/Home, media/volume.
  Gestures and text require Immortal's accessibility service on the Portal.
- **Photos:** import from Android's document/media picker, browse bounded
  thumbnails, delete with confirmation, activate a folder, and preview on Portal.
  Supported image files are limited to 20 MiB. Photos remain on the Portal.
  Google Photos Picker and additional file tools remain under Device tools.
- **Apps:** launch installed apps and install/update compatible catalog apps.
  Install results are retained on the Portal. An existing installer operation
  cannot safely be cancelled; the UI only exposes cancellation when supported.
- **Settings:** categories and searchable server-provided controls. Advanced
  settings are hidden by default. Visibility, bounds, options, and side effects
  come from Immortal's registry.

## Layouts, routines, snapshots

Layout editing supports long-press reorder, accessible move controls, and widget
spans from one to four grid cells. Existing bound Android widgets retain their
identity. New third-party widgets still need binding on the Portal.

Scenes hold ordered navigation, app launch, settings, local photo source, and
wait actions. Schedules use the Portal's local clock and weekday selection.
Alarms are inexact on Android; this is not a precise alarm-clock replacement.

Snapshot JSON contains supported settings and layout, without credentials,
pairing tokens, source connection strings, photo files, or third-party widget
data. Restore targets the device that exported the snapshot. The server validates
the candidate before applying it and rejects concurrent layout/settings changes.
Side-effect failures are reported explicitly; snapshot apply is not a database
transaction across all Android services.

## Jarvis

An updated Jarvis app exposes a small provider to Immortal only. The phone can
read readiness and microphone status, select an available model, and opt in to
local wake listening on supported Portal firmware. API keys stay in Jarvis.
Wake remains opt-in; microphone status does not prove room-distance audibility.

## Advanced tools and performance

Device tools retain ADB/USB provisioning, APK import/uninstall, screen mirror,
files, backups, diagnostics, OTA controls, app profiles, and Google Photos import.
Their independent ADB connection is established inside Device tools.

Native control metadata refreshes stop in the background. Pointer movement is
coalesced; image requests and decoder queues are bounded. Imports stream rather
than buffering entire collections in RAM. In-place app updates preserve saved
settings and credentials; an APK signature mismatch must never trigger uninstall.
