# Native companion music compatibility — November 2, 2026

## Source and scope

This bounded unit uses branch `codex/companion-compat-20261102`, based on preserved
commit `422286e1d8e2afd51c9809315418681e2f868efd`. Its app version remains
`1.7.0-music-preview` / versionCode 12.

The controller, client, repository and music protocol are unchanged. The existing
MediaPanel predicate (`matching device/session key && unsupported`) is extracted
into `usesLegacyPlayback` and consumed by the same UI branch. No new fallback
behavior is introduced.

Fifteen JVM tests use the production client, repository, controller and UI selection
policy with a synthetic transport and complete music/nowplaying fixtures:

- HTTP 404, 405 and 501 select the existing legacy playback path. Observation
  emits no writes; an explicit generic Pause uses the viewed Portal's `/remote/media`.
- HTTP 401 disables the paired session and cannot select legacy playback or admit
  a stale music action.
- Network failure, invalid JSON, unsupported/missing music schema and HTTP 500
  leave native music stale, without selecting fallback or admitting stale actions.
- A successful refresh clears unsupported state without automatically writing.
- Unsupported snapshots cannot select fallback for another Portal or a newer
  session generation, including returning to the original Portal.
- Controlled reads continue after cancellation, then deliver an unsupported
  response. Both repository and controller ownership are exercised separately.
  A retired read also cannot overwrite a fresh observation of the same session.
  Late results preserve the viewed Portal and produce no writes.

These characterize existing behavior. No newly discovered production bug or
initial failing regression/fix cycle is claimed.

## Execution receipts

All three Gradle invocations exited 0, offline, with
`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`, one worker,
`-Dorg.gradle.jvmargs="-Xmx3g -Dfile.encoding=UTF-8"` and
`-Pkotlin.compiler.execution.strategy=in-process`.

| Gate | Result | Gradle elapsed |
| --- | --- | --- |
| Initial full JVM + debug APK | 555 tests / 73 suites; debug assembled | 3m 24s |
| Focused compatibility + instrumentation APK | 15 tests / 1 suite; test APK assembled | 1m 12s |
| Final full JVM + debug APK | 555 tests / 73 suites; debug assembly up to date | 15s |

Each JVM run has zero failures, errors and skips. Independent XML summation
matches the testcase count: **540 tests / 72 unchanged original classes**, plus
**15 tests / 1 new compatibility class**. Original and new failures are both zero.
The final run followed adding the new class's explicit coroutine API opt-in;
there were no test failures or production fixes during this unit.

Original test classes still emit coroutine opt-in compiler warnings. Debug
packaging reports that `libandroidx.graphics.path.so` and
`libdatastore_shared_counter.so` are included without symbol stripping. These
warnings did not fail either APK gate.

The exact gate commands were:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
flags=(--offline --no-daemon --max-workers=1
  '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8'
  -Pkotlin.compiler.execution.strategy=in-process)
./gradlew "${flags[@]}" :app:testDebugUnitTest :app:assembleDebug
./gradlew "${flags[@]}" :app:testDebugUnitTest \
  --tests com.portalinstaller.viewmodel.CompanionMusicCompatibilityTest \
  :app:assembleDebugAndroidTest
./gradlew "${flags[@]}" :app:testDebugUnitTest :app:assembleDebug
```

The final gate completed at 07:18:45 UTC, before the unit's 07:26:24 UTC limit.
Both artifact hashes were independently recomputed after that gate:

| Artifact | Bytes | SHA-256 |
| --- | --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 14,722,589 | `9ce56c077517e5a92ec5e10858926fe70b3c54aceb7ea46b873d40e5c1b1359e` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1,396,448 | `92efb97aaf1e3f495b2f9974198135300ed2d03b5eb6b7adadb5b6e3b58f0f53` |

APK output metadata confirms versionCode 12 / `1.7.0-music-preview`. Neither APK
was installed or run.

Private logs, independent XML totals and artifact hashes are retained at
`/private/tmp/portal-companion-compat-20261102/`. Original test files are unchanged;
the final XML receipt separates their results from the added compatibility class.
An unmodified-base execution has not been performed in this unit; the original
class counts above describe their execution against this candidate.

## Exact changes

- `app/src/test/java/com/portalinstaller/viewmodel/CompanionMusicCompatibilityTest.kt`:
  the fifteen synthetic behavior tests and their transport/cancellation fixtures.
- `app/src/main/java/com/portalinstaller/ui/controlcenter/MediaPanelPolicy.kt`:
  the extracted existing fallback predicate.
- `app/src/main/java/com/portalinstaller/ui/controlcenter/ControlCenterScreen.kt`:
  MediaPanel calls that predicate; its branch behavior is preserved.
- `README.md` and `docs/companion-control-center.md`: current-version corrections
  and links to this scoped validation record.
- `docs/companion-compatibility-validation-2026-11-02.md`: this receipt.

## Acceptance limits

JVM results cover HTTP classification, strict parsing, session/cancellation guards,
write admission, and the exact policy used by MediaPanel. They do not render
Compose, exercise real OkHttp networking, or establish physical device, audible
playback, latency, installation or deployment acceptance. APK assembly is a
compilation/package gate only.

No phone, Portal, live endpoint, Home Assistant server, pairing credential,
GitHub authentication or publication is part of this unit. Primary Toolkit,
other worktrees and Portal-prime are outside its edit scope.
