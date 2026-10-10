# Immortal companion control center

The Android app opens a native control center. Home, Remote, Photos, Apps, and
Settings share one activity and use the Portal's approved neutral light/dark palette.

The current development build is **1.6.0-control-center** (`versionCode 10`).
The [extended validation record](companion-extended-validation-2026-10-08.md)
covers earlier physical Pixel/Portal checks and performance measurements.
The [multi-Portal controls](companion-multi-portal-2026-10-09.md) document describes
the new group settings and photo imports, automated coverage, and remaining live checks.

## Pairing

On the Portal, open **Control From Your Phone** to enable the remote and show a
fresh six-digit PIN. On the phone choose **Add a Portal**, select a discovered
device or enter its address, and enter the PIN. A pairing link containing the PIN
can also be pasted. The PIN expires and is single use; the resulting session
persists across restarts.

To import new photos, choose **Allow photo imports** in the Portal's pairing card
and grant Android file access. Reading existing photos does not imply write
access; the phone shows a setup hint until this permission is available.

Saved pairings are held in private storage excluded from Android backups. Select
another Portal in the top device menu. Active-device operations capture their session before starting; changing devices
invalidates their pending results. Group settings and photo imports retain their captured
targets and show per-Portal outcomes after a selection change. The top Portal menu owns
both **View Portal** and **Send changes to**. The latter has destination checkboxes,
**Select all**, and **Clear selection**; Settings and Photos show a passive destination
summary. The initial group selects all saved pairings; explicit choices persist privately
across restarts. Viewing another Portal does not change those destinations.
See [multi-Portal controls](companion-multi-portal-2026-10-09.md) for scope, retries and cancellation.
With exactly one saved Portal, the phone reconnects automatically on startup.

Local addresses default to port 8723. Existing private HTTPS endpoints and VPN
addresses may be entered explicitly. This app does not create an Internet relay.

## Daily controls

- **Home:** slideshow, home layout, scenes, Jarvis, snapshots, activity, media.
- **Remote:** pointer movement, tap, scrolling, keyboard, Back/Home, media/volume.
  Gestures and text require Immortal's accessibility service on the Portal.
  Select a text field on the Portal before sending text from the phone.
- **Photos:** import from Android's document/media picker, browse bounded
  thumbnails, choose another public Portal folder, delete with confirmation,
  activate the displayed collection with **Use for slideshow**, and preview on Portal.
  Supported image files are limited to 20 MiB. Photos remain on the Portal.
  Android storage aliases such as `/storage/emulated/0` are accepted and normalized
  to `/sdcard`. A failed preview shows an error and Retry rather than an endless
  spinner.
  Google Photos Picker and additional file tools remain under Device tools.
- **Apps:** launch installed apps and install/update compatible catalog apps.
  Install results are retained on the Portal. An existing installer operation
  cannot safely be cancelled; the UI only exposes cancellation when supported.
- **Settings:** categories and searchable server-provided controls. Advanced
  settings are hidden by default. Visibility, bounds, options, and side effects
  come from Immortal's registry.

Ordinary unsaved setting and source drafts survive rotation. Passwords, API keys,
and URLs containing credentials or query/fragment components remain in memory
and are excluded from saved instance state.

## Layouts, routines, snapshots

Layout editing supports long-press reorder, accessible move controls, and widget
spans from one to four grid cells. Existing bound Android widgets retain their
identity. New third-party widgets still need binding on the Portal.

Scenes hold ordered navigation, app launch, settings, local photo source, and
wait actions. Schedules use the Portal's local clock and weekday selection.
Alarms are inexact on Android; this is not a precise alarm-clock replacement.

Updated Portals protect full scene-list saves with a revision captured by the editor.
If another client changes a scene or schedule, the Portal rejects the stale save;
reload scenes before editing again. Unsaved edits retain their original list and revision.
Older Portals without scene revisions cannot provide this concurrency protection.

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
Uninstall and app-data clearing require confirmation tied to the selected app
and device connection. Changing or losing that connection dismisses the pending
confirmation.

Backup restore validates the complete archive before applying it. Partial
restores retain their failure details and refresh app/settings lists after
confirmed changes. These backups contain APKs and supported settings; they do
not capture private third-party app data.

Native control metadata refreshes stop in the background. Pointer movement is
coalesced; image requests and decoder queues are bounded. Imports stream rather
than buffering entire collections in RAM. In-place app updates preserve saved
settings and credentials; an APK signature mismatch must never trigger uninstall.
Connection loads device status and media first. Settings, photos, apps, layouts,
scenes, and Jarvis details load when their page is opened.
The toolbar refreshes the current page; the catalog loads only when selected.
Discovery runs while the pairing dialog is visible. Device tools pause native
control-center polling. Logcat stops when its page or the app leaves the foreground
and requires an explicit Start to resume. Screen mirroring preserves the Portal's
aspect ratio and maps touches against the measured video surface.
While visible, the app retries an offline paired device every 30 seconds. An
expired or revoked pairing requires pairing again rather than automatic retries.
Activity history uses independently measured, full-width lazy rows; a long
history does not compose every retained operation at once.

Portal Toolkit keeps the phone's screen awake while its activity is visible,
including during remote control and mirroring. Leaving the app or manually
locking the phone restores normal Android sleep behavior. This uses Android's
window flag rather than a background wake lock or a system timeout change.
