# Native numeric editor live acceptance

The previously unrun numeric-editor case passed on 2 November 2026. Existing
**1.7.1-music-preview / code 13** production source
`3ae098044a0eaf4f42dadf2fff8184f3aa5a5646` was unchanged.
The exact installed APK matched 14,722,589 bytes and SHA-256
`b5a0feb0b0486c53553e3b8126417afa0fd45eae340efd116ab539283ddc10f4`.

The owned API 34 emulator used a 360 × 800 dp phone viewport and existing
authorized Family/Living sessions, loaded into the normal private no-backup
store with mode 600. No fresh pairing or token creation was exercised.
Both actual room identities reported Immortal 1.72 / code 66.

The actual `intervalSec` schemas have min 5, max 600, step 5 and `asText:true`.
Native Settings displayed Family values with both destinations selected:

- Drafts 4, 601, `2147483648`, `5.5` and blank disabled Save.
- Actual disabled Save taps for 4/601 left both complete profiles unchanged.
- Boundaries 5/600 enabled Save; neither was submitted.
- One native Save of 60 reported **2 confirmed / 0 failed / 0 cancelled**.
- Independent complete-profile reads proved both intervals 60 and only that
  intended control changed. Living's screensaver stayed disabled.
- Single-room validated registry API restores returned both complete profiles
  and screensaver configurations exactly to their original values.

The first XML probe inspected the TextView label's enabled property instead of
the enclosing clickable button. The corrected probe and preserved XML showed
the expected disabled button. No speculative app change was made.
Outgoing write bodies were not traced; the native ledger and independent
complete-profile readbacks are the positive-write evidence.

The owned emulator stopped. Source AVD backing disks, snapshots and config
retained size/mtime; seven generated boot/modem sidecars changed, so no claim
that the whole AVD was unchanged is made. Original Family USB/Wi-Fi remained.
The next QA-isolation improvement should contain those generated files in a
disposable data directory as well as preserving the backing image.

Cold reconnect, physical Pixel/folded layout, fresh onboarding, playback,
frame rate and general performance remain separately open. This successful
initial warm connection does not reopen or pass the earlier cold test.
Prior 564 JVM / 33 rendered checks retain their own scope; production source
and APK were unchanged, so this unit performed no fresh build.

Bounded slot: 11:26:14–11:56:14 UTC, including cleanup. Private evidence:
`/private/tmp/portal-companion-numeric-acceptance-20261102/`.
