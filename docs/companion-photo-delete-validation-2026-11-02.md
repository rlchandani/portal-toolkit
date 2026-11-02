# Native photo-deletion confirmation — 2 November 2026

Bounded unit starting **18:25:05 UTC**, deadline **18:55:05 UTC**,
branch `codex/companion-photo-delete-20261102`, parent `131bb7a7`.
The browser correction is already delivered separately in Portal1.82. This
unit fixes the equivalent Android client contract without changing the server.

## Change and reproduced regression

The previous client accepted a successful response without matching the selected
photo. `JSONObject.optBoolean` also accepts string `"true"`. Nine new JVM
methods produced **seven failures before the correction**.

`CompanionClient.deletePhoto` now validates the actual Boolean flag and the
exact normalized wire path before accepting a positive response. Unreadable or
incomplete positive acknowledgments emit `delete_unconfirmed`:

> The Portal did not confirm this deletion. Refresh and review your photos before retrying.

The repository retains its last known photo list on this error. That list is
not proof that the file still exists: a server can finish deletion before its
reply is lost. Refresh is a user action; there is no automatic deletion replay
or metadata retry. A valid confirmation preserves the existing single list
refresh. Explicit rejection and HTTP401 keep the existing error handling.

Shared-storage aliases normalize before both request and comparison; directory
whitespace remains significant. Existing valid-deletion fixtures now echo the
actual decoded request path. The general parser, upload behavior, persisted
pairings and room/session ownership guards are unchanged.

## Executed checks

| Gate | Result |
| --- | --- |
| Full JVM tests | **573 /75 suites**, zero failures, errors or skips |
| App and Android test assembly | Passed, offline; final combined gate **18 seconds** |
| APK package/version | `com.portalinstaller`, **1.7.2-music-preview /code14** |
| APK signing | Same accepted signer; actual certificate verification passed |
| Installed emulator artifact | Entire APK readback matches the candidate |
| Actual rendered deletion cases | **4/4**, **4.773 seconds**, owned Android14/API34 emulator |
| Original source AVD | Whole-directory contents and metadata unchanged |
| Owned runner cleanup | Emulator stopped; owned clone/resources removed |

The native fixture exercises actual `PhotoLibraryPanel` dialogs and callbacks,
`CompanionRepository`, and `CompanionClient` with an in-memory transport and a
generated PNG. It renders repository error text beside the panel. It is not a
whole-app snackbar or live-network test.

The four cases verify:

1. Literal success and the exact path clear the photo after one Delete and one
   metadata refresh.
2. A wrong-path acknowledgment retains the last known tile, shows review, and
   performs no refresh or automatic retry.
3. String `"true"` produces the same guarded outcome.
4. Explicit401 preserves Unauthorized and the photo.

Opening the preview and confirmation dialogs sends zero Delete requests; only
the confirmation sends one. The emulator was configured as **360×800dp**, normal
font scale. The in-memory positive-response fixture removes its synthetic photo
even for an unconfirmed reply, testing why a retained list must not be treated
as definitive server state.

The first full build found a missing test JSON import; it was corrected. The
first rendered run passed3/4 and failed before clicking Delete photo because
the dialog window had not finished placement. A bounded displayed-state
barrier corrected the test harness; final4/4 passed without changing product
dialog behavior. The Android test APK was rebuilt after that fixture change.

Independent review checked the client against the current Portal contract and
identified the preexisting whitespace fixture needing a valid acknowledgment.
That fixture was corrected. A fresh final independent source review found no
remaining concrete defect. Room/session containment remains source-inspected;
no fresh delayed-response race acceptance is claimed.

## Artifacts and platform limits

App: **14,722,589 bytes**, SHA256:

`c9f7e2beb6fdd1f95a78993bfbd70558fdc2cec5dde4d9c23fff431a85d54d6a`

Final Android test APK: **1,683,563 bytes**, SHA256:

`24a6953e4052baa35d3a28eda599e4ca647d19798985e80727acd3942ac2dabd`

Signer:

`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`

The candidate is built and emulator-verified; **physical Pixel installation and
acceptance remain open**. Historical1.7.1 UI results keep their original scope;
they are not repeated acceptance of1.7.2. No native cold-reconnect issue,
provider login, audible playback, FPS or general speedup is claimed.

No Portal write, install, user-photo deletion, pairing change or HA operation
was performed. Both rooms' full installed Portal APKs still match exact1.82;
their complete profile/layout/screensaver settings remain unchanged. Family's
font, accessibility/binding state, original Chrome task and manual photo-frame
host remain intact.

Private gates, artifact hashes, runner and retention receipts:
`/private/tmp/portal-companion-photo-delete-20261102/`.
No GitHub authentication, push or publication.
