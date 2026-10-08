# Immortal Control Center Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or
> superpowers:executing-plans to implement tasks with disjoint write scopes.

**Goal:** Build the approved Immortal companion control center and validate it
against the connected Portal+.

**Architecture:** Paired HTTP contracts drive daily control and live device
settings; independent ADB maintenance remains available. Native Compose screens
share one session-aware shell. New paired management contracts live in Immortal.

**Tech Stack:** Kotlin, Jetpack Compose, Android API 26+ phone, Android API 28/29
Portal, OkHttp, DataStore, existing Dadb and hardware H.264 decoder.

**Spec:** `docs/superpowers/specs/2026-10-08-immortal-control-center-design.md`

**Implementation evidence:** The control center is implemented on
`codex/portal-companion-control-center`, with Portal support on
`codex/portal-companion-api` and Jarvis support on
`codex/jarvis-companion-management`. The dated
[extended validation record](../../companion-extended-validation-2026-10-08.md)
is the current source of test results and remaining environmental limits.
The original planning checklist below is retained; unexecuted destructive or
provider-dependent checks are not represented as completed.

## Global constraints

* Preserve package IDs, signing identities, persisted data, and existing features.
* Branches start at companion `4e1dadb` and Immortal `c47e069`.
* No GitHub connector or authentication request.
* No raw preferences writes in Immortal remote routes; use the settings registry.
* New Portal files use the Starbright Lab MIT header.
* Never export Jarvis API keys or initiate an unapproved cloud microphone probe.
* Compile/test Gradle execution in a shared checkout is serialized.

## Review focus

1. Switching devices during an operation cannot redirect input or late results.
2. A disconnected or cancelled upload cannot destroy an existing destination.
3. Restoring a malformed snapshot rejects it before any configuration mutation.
4. Advanced and parent-dependent settings remain consistent after phone edits.
5. A slow decoder cannot grow its pending queue indefinitely.

## Task 1: Baseline and preserved workspaces

- [ ] Run companion `:app:testDebugUnitTest :app:assembleDebug` and inspect output.
- [ ] Record clean application-source baselines and create isolated branches.
- [ ] Commit this reviewed design and plan before implementation.

## Task 2: Paired Portal management contracts

**Files:** Immortal `RemoteCompanionRoutes.kt`, supporting focused policy/storage
files, `RemoteRoutes.kt`, and corresponding JVM tests.

**Interfaces:** The exact endpoint/JSON contracts in the spec. Reuse existing
RemotePairing, SettingsRegistry, Fleet install/catalog helpers, UserLayout,
HomeWidgetStore, PresenceHub, RemoteMedia, and source configuration.

- [ ] Write pure policy tests for layout validation and snapshot preflight:
  `assertFalse(validateSnapshot(JSONObject().put("schemaVersion", 999)).valid)`.
- [ ] Run the targeted tests and confirm missing behavior fails.
- [ ] Implement authenticated routing with finite, bounded state and allowlists.
- [ ] Expose a stable device UUID and real capability flags.
- [ ] Test unsupported actions, invalid dimensions/IDs, partial install failures,
  and snapshots with invalid enum/int/type values.
- [ ] Run all Portal unit tests and build its debug APK.
- [ ] Commit the server contract independently.

## Task 3: Native companion client and device sessions

**Files:** `data/companion/*.kt`,
`viewmodel/ControlCenterViewModel.kt`, `src/test/.../companion/*.kt`.

**Interfaces:** `ControlCenterViewModel` exposes `state: StateFlow<ControlCenterState>`,
pair/select/disconnect/refresh, remote/media/text/settings/source methods, and
catalog/layout/snapshot/scene/operation calls. All HTTP models follow the spec.
The worker documents exact signatures for shell integration.

- [ ] Test endpoint normalization, JSON model parsing, settings types, and stale
  session rejection with an injected fake HTTP transport.
- [ ] Confirm failures, then implement IO-only requests and finite timeouts.
- [ ] Persist endpoint/name/device ID and protected per-device session credentials.
- [ ] Add DNS-SD discovery using existing `_immortal-remote._tcp.` advertisements,
  with manual endpoint and PIN fallback.
- [ ] Ensure errors retain actionable messages and never report failed writes saved.
- [ ] Verify unknown capability fields and older Portals remain usable.
- [ ] Run companion unit tests and commit the client.

## Task 4: Existing transport reliability

**Files:** ADB connection/holder, file manager/path safety, mirror parser/decoder/
ViewModel, InstallPolicy, and focused existing/new tests. MainScreen is main-owned.

- [ ] Add failing tests for resolved path confinement, actual API-level
  compatibility, bounded queues, and immutable mirror input targets.
- [ ] Implement temporary upload plus verified rename; preserve existing files
  after failed pushes and propagate actual failures.
- [ ] Bind mirror input and asynchronous results to the originating session.
- [ ] Bound decoder queues and move stream parsing off the main dispatcher.
- [ ] Use actual device SDK/ABI for install policy.
- [ ] Make cancellation states truthful and retain retryable item outcomes.
- [ ] Run unit tests and commit the reliability fixes.

## Task 5: Jarvis management and local wake policy

**Files:** Separate Jarvis branch; focused IPC policy/provider/service/settings
files and tests.

- [ ] Test caller validation, model allowlists, and no secret fields in replies.
- [ ] Add an Immortal-only IPC surface for model/microphone/wake status and
  explicitly supported writes; keep the saved API key private.
- [ ] Add opt-in local background wake detection where API29 permission/runtime
  policy permits; preserve current foreground-only behavior by default.
- [ ] Test pause/rearm rules, foreground-service lifecycle and competing capture.
- [ ] Build/test without running a live cloud microphone diagnostic.
- [ ] Commit and provide the APK plus exact IPC contract.

## Task 6: Continuous companion UI and real workflows

**Files:** Main-owned `ui/controlcenter/*.kt`, MainActivity/MainScreen integration,
AppContainer, PortalRoute, theme, localized resources, photo activation wiring,
and session/flow tests.

- [ ] Build Home, Remote, Photos, Apps, Settings with device identity visible.
- [ ] Render registry controls from metadata, including search and Advanced.
- [ ] Add supported source editors, folder activation, on-Portal preview, share
  import, and persistent per-device destinations.
- [ ] Add native layout editor using actual supported widget sizes and IDs.
- [ ] Add catalog installs/updates, operation results/retry, scenes and schedules,
  snapshot document export/import, and Jarvis management.
- [ ] Keep ADB maintenance, existing Google Photos picker, filesystem, shell,
  logcat, and legacy backups reachable under Advanced.
- [ ] Add manual VPN/endpoint profiles without silently changing network exposure.
- [ ] Test failed writes, offline state, expired sessions, interrupted transfers,
  parent-setting refresh, and configuration/process recreation.

## Task 7: Integration, performance and delivery

- [ ] Review worker patches against contracts and run full tests/builds.
- [ ] Install the Portal API APK with the existing signing identity and preserve
  all device data; install companion to a phone/emulator when available.
- [ ] Verify real pairing, settings readback, photo activation/preview, layout
  round trip, app operation status, scenes, snapshot validation and Jarvis IPC.
- [ ] Measure UI jank, decoder queue limits, memory and Portal CPU; exercise
  reconnect, Wi-Fi interruption, phone process death, and extended mirroring.
- [ ] Fix observed regressions; run relevant checks again.
- [ ] Commit all implementation changes, update README/release notes/version,
  and report delivered features, evidence, and actual environment limitations.
