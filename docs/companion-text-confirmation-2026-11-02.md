# Remote keyboard confirmation — 2 November 2026

Candidate **1.7.5-music-preview / code17** is built, signed and verified in an
owned Android34 emulator. It has not been installed on the physical Pixel;
ADB and mDNS discovery found no reachable phone.

## Correction

The keyboard already keeps its draft when `sendTextConfirmed` returns false.
The shared client previously accepted an incomplete positive response such as
`{"ok":true}`, or non-Boolean `applied`, as confirmation. That can incorrectly
clear the draft. This is a reproduced client/protocol defect using controlled
responses, not an observed loss of the owner's text over Wi-Fi.

The text POST now requires literal `ok` and, for a positive result, literal
Boolean `applied`. Explicit false continues through the existing rejection
path. Missing, malformed or incorrectly typed positive replies and lost
transport replies become `text_unconfirmed`; cancellation still propagates.
The request body, Unicode draft, FIFO ownership and keyboard UI are unchanged.
No automatic resend, polling or additional request was introduced.

Current Portal `RemoteRoutes.text` returns `applied`, not `dispatched`.
Three older text fixtures were corrected to that actual contract.
No Portal API or server change is required for this phone update.

## Verification

| Gate | Result |
|---|---|
| Original focused reproduction | 5 failed / 7 before the correction |
| Final focused JVM | 7 passed |
| Full final JVM | 609 tests / 78 suites; no failures, errors or skips |
| Build and signing | App and test APKs passed; same existing certificate |
| Native default/system 100% | 3 methods passed in 7.090s |
| Native actual system 130% | 3 methods passed in 9.377s |
| Font precondition protection | A deliberately mismatched scale failed before rendering |
| Installed app | Full APK readback matches the candidate |
| Owned cleanup | Original AVD unchanged; cloned resources removed |

The actual panel, keyboard controller, repository and client run with a
memory-only transport/session store. Nine reply variants per font cover six
unconfirmed responses, a confirmed response and two explicit rejections.
Each rendered Send produces exactly one text POST. Fixture refresh/recomposition
does not resend it. Confirmed replies clear the controller draft and keyboard
semantics; reopening shows an empty draft. Unconfirmed replies retain the exact
draft and existing warning.

The first 130% attempt used only parent `LocalDensity`, which did not scale the
sheet's separate Android window. Final acceptance sets the owned emulator's
actual system font and checks `resources.configuration.fontScale` before
rendering. A styling-sensitive `AnnotatedString` comparison was also corrected
to compare exact editable characters; controller and request equality remain
strict. The initial runner-selection error and failed test are retained privately.

Public screenshots show only the unconfirmed state at system 100%. The final 130%
snapshot caught an opening animation and is excluded from pixel acceptance. Success screenshots were
captured during the OS window exit animation and are not settled-success pixel
evidence. No physical Pixel, foldable layout, real Portal text entry, whole-app
snackbar, cancellation over real Wi-Fi, FPS or universal latency claim is made.

## Artifact

- Application: `com.portalinstaller`, 14,722,589 bytes.
- SHA-256: `dfc26b71f020e31936aac4d3ed7d2fb6f976e762f0eabc43c723a9a504cf84e4`.
- Signing certificate SHA-256:
  `4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`.

Original clock: **19:45:25–20:15:25 UTC**, including cleanup. No Portal
installation or input, saved pairing write, GitHub action or Home Assistant
action occurred. Family's original USB/Wi-Fi transports and the unrelated
emulator remain present. This does not close physical-phone acceptance or
the separate browser keyboard source concern.
