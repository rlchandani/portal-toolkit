# Phone settings confirmation

Candidate **1.7.4-music-preview / code16**. Physical-phone installation remains
open. Portal code, settings and APKs are unchanged.

The source audit began at **19:08UTC**, with **19:38UTC** as the original
half-hour deadline including cleanup. Implementation retains that clock.

## Reproduction and correction

The old shared client accepted string `"true"` as success. The single-room path
did not check the returned settings section; group writes substituted the
requested section when the reply omitted it.

Nine focused JVM tests ran against the old code: **seven failed**, while valid
confirmation and explicit rejection already passed. The initial test compile
also caught a nullable test assertion; this was corrected before reproduction.

Settings POST now has one shared acknowledgment check: literal Boolean success
and an actual domain object with an exact string ID matching the request.
Unreadable positive responses and transport loss become `settings_unconfirmed`.
Explicit rejection and non-success HTTP responses retain the existing server
error handling; cancellation propagates.

Single-room writes retain their existing metadata refresh and review error.
Group writes retain terminal **Unconfirmed** results, excluded from **Retry
failed only**. Existing partial-key handling, validation, captured destinations,
session ownership and concurrency limits remain in their current paths.

Four old successful/partial group fixtures were updated to include the actual
matching domain identity. One first full run exposed a guard that prematurely
intercepted server validation of an empty request. The unnecessary request-side
checks were removed, preserving server rejection details and credential
redaction; positive replies still require a nonempty requested identity.

## Verification

- Final JVM run: **602 tests /77 suites**, zero failures, errors or skips.
- Debug app assembly passed; signature matches the existing debug candidate.
- **Three rendered Android cases** passed at100% and130% text (3.278s and3.104s respectively), exercising actual registry controls, ViewModel, repository, client and group panel.
- Full installed emulator APK readback matches the final candidate. API34 at1080×2400, density480. Both generated uncertainty captures were visually inspected.
- Owned emulator removed; the original AVD remained unchanged. No physical Pixel deployment.

Independent review also found missing/non-array applied-key lists could permit a
failed-only retry. Three new JVM tests reproduced two failures and preserved the
valid-empty-list rejection case. Positive settings replies now require a string
applied-key array; **all12 focused tests pass**. Rendered uncertainty cases cover
wrong/missing domain; malformed applied-list cases are JVM coverage.

Final APK:15,376,014bytes, SHA256
`a45e85e251dc36c37095e90edcce6fc56fff4021309c3aac759a914c82f0ca04`.
Native test APK:1,717,894bytes, SHA256
`920be34210eb50cd93db554ce98a7412ff1f5531ae933ab1f40e04dcb12e54e1`.

No additional request, polling or automatic settings resend was introduced.
The bounded-work tests count outgoing POSTs; they do not establish frame rate
or a universal speed improvement.

## Limits

The malformed replies are controlled transport fixtures, not evidence that the
installed Portal normally sends those replies. Rendered fixtures exercise the
real registry controls, ViewModel, repository, client and progress panel, with
memory-only sessions; they cannot establish physical Pixel or live Wi-Fi
acceptance. No Home Assistant, GitHub authentication, publication or Portal
deployment is involved.
