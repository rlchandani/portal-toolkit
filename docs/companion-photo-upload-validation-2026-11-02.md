# Native photo-upload confirmation — 2 November 2026

Bounded unit starting **18:35:48 UTC**, deadline **19:05:48 UTC**,
branch `codex/companion-photo-upload-20261102`, parent `00c48d27`.
This brings the phone's upload acknowledgment handling into line with the
separately delivered browser correction, without changing the Portal server.

## Change and reproduced regression

Previously, the shared client checked bytes after a coercing success parser.
It could accept a wrong or missing destination and string-valued success or
bytes. Eleven new JVM methods produced **nine failures before correction**.

Positive upload replies now require actual Boolean success, the exact normalized
destination/name, and numeric bytes matching the prepared image. An unreadable
positive reply or transport IOException after dispatch reports
`upload_unconfirmed`; explicit server rejection and HTTP401 retain existing
handling. Cancellation propagates. Directory whitespace remains significant.

Group uploads have a terminal **Unconfirmed** result, displayed separately from
Failed. Retry failed only excludes unconfirmed images and retries captured
failed destinations even when the current room selection changes. Confirmed
uploads keep their result if local import disposal fails; that cleanup issue is
reported separately. Single-room imports retain uncertainty guidance when their
subsequent metadata refresh fails, while updating connection status.

No automatic replay, polling, new background work or persistence schema was
added. The existing bounded group fanout and progress throttling remain in place.
This is outcome correctness and duplicate-work prevention, not a measured speedup.

## Executed checks

| Gate | Result |
| --- | --- |
| Full JVM tests | **590 /76 suites**, zero failures, errors or skips |
| Focused upload JVM methods | **17/17** |
| App and Android test assembly | Passed offline; final combined gate **9 seconds** |
| Candidate | `com.portalinstaller`, **1.7.3-music-preview /code15** |
| Signing | Certificate verification passed; existing signer retained |
| Installed emulator artifact | Complete APK bytes and SHA256 match candidate |
| Normal-text rendered checks | **7/7 in14.901 seconds**: three upload and four retained deletion cases |
| Enlarged-text upload checks | **3/3 in8.765 seconds**, font scale1.3 |
| Original source AVD | Whole-directory contents and metadata unchanged |
| Owned runner cleanup | Emulator stopped; owned clone/resources removed |

The owned Android14/API34 emulator used **360×800dp**. The three upload fixtures
exercise the actual `GroupProgressPanel`, repository and shared client with
generated PNG media, memory-only sessions and an in-memory transport:

1. One exact acknowledgment and one wrong path show one Confirmed and one
   Unconfirmed result, with no Retry failed button or duplicate request.
2. Explicit409 shows Failed; the actual Retry failed only button resends only
   that captured room and accepts its later valid acknowledgment.
3. Unreadable positive replies show Unconfirmed and close/dispose each prepared
   stream once, with no automatic replay after the panel settles.

The JVM checks additionally cover missing/coerced acknowledgment fields,
fractional bytes, transport loss, malformed401, mixed three-room retry, failed
refresh and local cleanup exceptions. The latter prove attempted cleanup and
retained outcome, not successful cleanup when disposal itself throws.

Independent review identified two real corner defects: failed refresh hid
single-room uncertainty and local disposal failure replaced group results.
Two regressions failed before those corrections; a further single-room cleanup
regression failed before its correction. A later full gate had two failures
because looped test scenarios shared a cancelled scope. Each fixture now owns
its child scope; the final full gate passed. A fresh independent source review
found no remaining concrete regression. No new auth/cancellation race
acceptance is inferred from source review.

## Artifacts and limits

App: **15,374,817 bytes**, SHA256:

`2c3361e926d6659e83eac51a60d7c51d81a80fa9503aac28fae7cb1e46c83436`

Android test APK: **1,701,242 bytes**, SHA256:

`2eef85b8ffe37078da7fdbbc6c62abe5ab7b2843f996994be1e0270b6a9caabd`

Signer:
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`

**Physical Pixel installation and live-network acceptance remain open.** These
are scoped rendered fixtures, not whole-app snackbar, all-screen visual,
real-network stream/latency, cold-reconnect, playback or FPS acceptance.
An unconfirmed result is not proof that the remote image was not stored.

Both rooms retain the exact installed Portal1.82 APK and complete
profile/layout/screensaver configuration. Family's font, accessibility/binding,
original Chrome task and manual photo-frame host remain intact. There were
**zero Portal writes or installs**, no real user-photo transfer or deletion,
and no GitHub authentication, push, publication or HA action.

Private artifacts, gates, native runs, retention and cleanup receipts:
`/private/tmp/portal-companion-photo-upload-20261102/`.
