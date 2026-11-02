# Companion backup preview — 2 November 2026

Candidate **1.7.6-music-preview / code 18** makes archive preview cancellable and
bounds every decompressed entry against the existing 3 GiB archive budget. The
existing Cancel control is available while inspecting. Cancelling, clearing or
disconnecting invalidates the preview owner, so a late result cannot overwrite
newer work. Format 1, 500 entries, 1 MiB manifest and the restore format are retained.

## Evidence and performance scope

The unchanged old inspector failed the declared-oversize regression by reading
an omitted entry body instead of rejecting its size. The accepted 1.7.5 APK failed
the native missing-Cancel check. This does not reproduce actual 3 GiB inflation or OOM.

All **632 JVM tests /80 suites** passed with no failures, errors or skips, including
17 streamed-ZIP and 6 lifecycle cases. Signed debug and AndroidTest builds passed.
Independent review found three native-fixture weaknesses; actual job joins,
terminal writer assertions and child-process cleanup replace delay assumptions.

Four unique native methods passed at actual 100% and 130% system text (**8 workflow
executions**). They use the actual ViewModel, BackupCard, generated archive and a
sandbox-local blocked FIFO. The tests await cancelled reader completion and
retain a newer preview after the old reader settles. A single cancel-to-idle
sample measured **65.005 ms** at 100% and **90.373 ms** at 130%, below the test's 1000 ms
limit. These are emulator observations, not physical-phone or FPS measurements.

The initial system screenshots were blank and were rejected. Capturing the actual
Compose root produced visible Cancel pixels at both scales; the affected method
passed twice more after a test-only capture correction. This covers the isolated
card, not the full Devices page or a physical phone.

## Artifact and cleanup

- Package: `com.portalinstaller`, version 18 /1.7.6-music-preview.
- APK: **15,014,631 bytes**; SHA 256
  `972e5f75c22ebc6598553844a32ed9d51bebc9456d5d803200d7d45a3a0e2828`.
- APK signature passed and matches the accepted 1.7.5 signer. Complete installed
  emulator APK readback matches the candidate.
- Owned FIFO/file/process work settled. The owned emulator was stopped and removed;
  runner receipt proves the donor AVD unchanged. Unrelated emulator and Family
  transports remain. The independent agent is closed.
- Original issue clock **20:51:32–21:21:32 UTC**, including cleanup, was retained.

A content provider blocked inside a read may still need to return before its
cancelled coroutine terminates. UI ownership invalidation is immediate; universal
provider interruption is not promised. No owner archive, Portal configuration,
ADB backup/restore, physical phone, GitHub or Home Assistant operation occurred.

Sanitized evidence is mirrored in the Portal roadmap as
`companion-backup-preview-verification.json`, with generated native card crops.
Full logs, candidate and initial rejected images remain private.
