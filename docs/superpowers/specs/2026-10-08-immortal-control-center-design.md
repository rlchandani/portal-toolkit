# Immortal companion control center

## Purpose and authorization

The owner approved implementation of the companion recommendations on 2026-10-08,
after asking that existing changes be committed. The companion baseline is
`4e1dadb`; the Immortal baseline is `c47e069`. Both application source trees were
clean. Implementation uses new isolated branches and preserves existing phone
settings, saved devices, Portal state, Jarvis credentials, and APK signing keys.

## Product

The Android companion becomes a daily control center with five destinations:
Home, Remote, Photos, Apps, Settings. The active Portal is always identified.
ADB provisioning, filesystem tools, shell, logcat, and legacy backup remain
available under Advanced. Existing functions must remain reachable.

Home shows current device state, media, source, quick actions, and operations.
Remote provides navigation, touchpad, keyboard, media controls, and optional
mirroring. Photos manages uploads and the actual Immortal source, with an
explicit activation and on-device preview. Settings renders the live registry,
refreshes conditional controls after writes, and supports search and Advanced.

The wider management scope includes widget layout editing, compatible catalog
installs and updates, scenes, configuration snapshots, independent device
sessions, persistent operation history/retries, and Jarvis status/model controls.
Optional away-from-home profiles use an existing VPN or authenticated endpoint;
the app does not automatically publish a device or create a cloud account.

## Architecture

Use paired Immortal HTTP sessions for normal control. Keep ADB as a separate
maintenance transport. Existing `/remote/*` routes remain compatible. A scoped
companion surface extends the paired API without treating a paired phone token
as the unrestricted Fleet operator credential.

Phone network calls run on IO dispatchers, have finite timeouts, and carry an
immutable device session and generation. Late results cannot alter a different
device's state. Persist paired endpoints separately from legacy IP/port records.
Keep secrets outside automatic Android backup and never include them in logs.

Use cached small metadata responses and thumbnails. Do not pull entire APKs to
render native Immortal app icons. Poll only visible, relevant state, with
backoff while disconnected. Mirroring is opt-in, hardware decoded, bounded in
queued bytes, and stops when its screen is left.

## Shared API contracts

All new remote routes require existing RemotePairing authorization.

* `GET /remote/companion`: `{ok, protocolVersion:1, deviceId, name, model,
  apiLevel, app, capabilities, presence, install, canWriteSecureSettings}`.
  Device identity persists across IP changes. Capability flags describe actual
  support, including `layout`, `snapshot`, `catalog`, `operations`, `jarvis`,
  and `scenes`; missing capabilities are unavailable, not guessed.
* `GET|POST /remote/layout`: `{ok, layout:{assignments, homeOrder, gridOrder,
  gridSlots, widgets}}`. POST accepts `{layout:...}`; validate the complete
  candidate before mutation. Existing third-party widgets retain their bound
  IDs. New custom widgets use supported native kinds; arbitrary new third-party
  widget binding remains an on-Portal consent flow.
* `GET|POST /remote/snapshot`: versioned JSON snapshot of writable registry
  values and layout. GET excludes credentials and paired session tokens.
  POST validates schema, values, and layout before applying. Report each applied
  domain; never claim third-party private app data is backed up.
* `GET /remote/catalog`: catalog app metadata and installed versions.
* `POST /remote/operation`: start an allowlisted install/update/action with
  explicit target parameters; returns `{ok, operation:{id,type,state,...}}`.
  `GET /remote/operations` reports bounded operation history; completed results
  are retained. Cancellation reports whether backend work can actually stop.
  Arbitrary URLs, shell commands, and dev-APK installation are not accepted
  through the paired management API.
* `GET|POST /remote/jarvis`: report installed/model/microphone/wake support;
  POST uses an explicit allowlisted Jarvis IPC contract for model or wake policy.
  API keys never leave Jarvis. Unsupported installed versions report capability
  absence. No cloud microphone diagnostic is started by these routes.
* `GET|POST /remote/scenes`: saved scenes with IDs, names, steps, optional local
  schedule. `POST /remote/scene` starts a selected scene. Steps use supported
  settings/source/navigation/app actions, bounded waits, and truthful outcomes.
  Existing `/remote/presets` behavior remains available.

Existing pairing, apps/icons, sources, settings, media, keyboard, gestures,
and screen-frame launch endpoints are reused with their current JSON contracts.
Google Photos remains explicit user-selected import; continuous sources use
Immich, WebDAV, SMB, or local folders supported by Immortal.

## Phone state and UX

Connection states distinguish unpaired, connecting, connected, offline, and
unauthorized. Preserve last-known data with a visible freshness label. A failed
write never displays a success toast. All settings writes verify the response
and refresh affected controls; rich editors are exposed only when supported.

Use neutral light/dark colors, grouped settings rows, clear icons, restrained
motion, and one continuous navigation shell. Separate device settings from
phone preferences. Remote selection, active operations, and destination
confirmation make the target device unambiguous.

Persist upload destinations by device. Sending photos does not imply activation:
offer Use this folder and Preview on Portal. Imports explain duplicate/collision
handling and preserve individual failures for retry. File pushes use temporary
names and verified atomic rename, preserving an existing destination on failure.

## Execution ownership

* Main agent: design/plan, shared shell/navigation, app-container integration,
  companion Compose screens, source activation, end-to-end integration, builds,
  native-device verification, final commits.
* Portal worker: new paired companion routes and pure policy tests in the isolated
  Immortal worktree. No HomeActivity/UI or Jarvis source edits.
* Companion client worker: new `data/companion` models/client/session persistence
  and `viewmodel/ControlCenterViewModel.kt`, plus their tests. No existing shell,
  AppContainer, resources, or Portal source edits.
* Reliability worker: existing ADB session/file/mirror/compatibility fixes and
  their tests. No companion HTTP client, MainScreen, resources, or photo UI edits.
* Jarvis worker: controlled IPC and local wake policy in a separate Jarvis branch;
  no API-key reads, cloud microphone probes, or Immortal edits.

## Validation

Unit coverage must exercise authorization/allowlists, malformed and future
snapshots, conditional settings, stale device generations, failed atomic pushes,
actual SDK compatibility, bounded video queues, and truthful operation outcomes.
Build both Android apps and retain APKs. Run the companion on an available phone
or Android emulator; use the connected Portal+ for real API and navigation
checks. Do not install the phone companion as a substitute for testing a phone.

Verify photo activation and preview, setting readback, reconnect, device switching,
file failure recovery, and prolonged mirroring. Measure phone UI jank and Portal
CPU/memory during normal controls and mirroring. Existing paired/API credentials
must not appear in exported reports, source, or command output.

External-account setup and user-triggered cloud audio remain separate explicit
actions. Offline tests and ordinary pairing/control do not silently run a cloud
microphone experiment that was previously rejected by approval review.
