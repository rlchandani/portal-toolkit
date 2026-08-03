# Code-Quality Audit — 2026-08-02

Baseline: 112 tests passing, 80 Kotlin files, ~12k LOC. Branch `roadmap-v1.2-1.4`.
Five parallel audit agents (data / viewmodel / ui / test-coverage / maintainability).

Legend: [ ] pending, [x] done, [~] deferred.

## Group A — Correctness & concurrency (behavioral, highest value)
- [ ] A1 CRITICAL AppsViewModel install: no "already installing" guard; installJob overwritten (AppsViewModel.installApp/installApkFromUri)
- [ ] A2 HIGH ConnectionViewModel TOCTOU: busy sentinel set inside launch{} -> double connect, leaked Dadb, double monitor (connect/connectUsb/reboot/discover)
- [ ] A3 HIGH refreshDeviceStatus + loadInstalledApps lack runCatching -> child-coroutine crash
- [ ] A4 HIGH loadPortalPackages/enrichPortalApps uncancelled -> overlapping uninstalls race
- [ ] A5 MED ScreenCaptureManager: no exit-code check -> stale PNG on failure; remote temp leak
- [ ] A6 MED ConnectionMonitor.append/clearLog non-atomic RMW -> dropped log events (use update{})
- [ ] A7 MED enrichPortalApp temp APK not deleted on failure (try/finally)
- [ ] A8 MED FileManagerViewModel/Screensaver/Backup: transferJob/opJob overwrite + TOCTOU busy guard
- [ ] A9 MED _notConnected never reset in Screensaver/Logcat/Capture VMs (add holder.dadb collector)
- [ ] A10 MED PortalDiscoveryManager soTimeout 500ms used for whole handshake -> real Portals dropped
- [ ] A11 LOW LogcatViewModel stream @Volatile + finally close on EOF/cancel
- [ ] A12 LOW CaptureViewModel: clear _frame bitmap on stop/disconnect

## Group B — Security / shell quoting (DRY + correctness)
- [ ] B1 HIGH Extract shellQuote to data/adb/ShellQuote.kt; delete 4 private copies
- [ ] B2 MED Quote packageName in uninstallApp + setOsUpdatesBlocked (unquoted interpolation)

## Group C — UI recomposition / performance
- [ ] C1 HIGH PortalAppsTab.AppIcon: remember+decodeFile on main thread -> produceState+IO
- [ ] C2 HIGH InstalledAppsTab: File.length() in composition body -> precompute/remember
- [ ] C3 MED ScreensaverTab/FilesTab: remember() the selection filter+sum
- [ ] C4 LOW MoreTab HistoryTab items() missing key
- [ ] C5 LOW MainScreen usbStatus 750ms poll runs app-lifetime (gate to USB screen)

## Group D — Dead code / cleanup
- [ ] D1 MED Delete PortalConnectionManager.pushPhoto (0 call sites; keep PORTAL_PHOTOS_DIR)
- [ ] D2 LOW Inline formatAdbReason wrapper (8 sites) -> AdbErrors.formatReason
- [ ] D3 LOW DedupeMatcher dead (planUpload ships) — delete matcher+test OR route through it

## Group E — Tests (add high-value coverage)
- [ ] E1 Extract RemotePaths pure helper (dest/quote) + tests (destructive targets)
- [ ] E2 ImageFolderScanner.rankFolders tests (dedupe, legacy, sort tie-break)
- [ ] E3 LsParser malformed-row / numeric-fallback tests
- [ ] E4 LogcatParser fatal/paren-tag/empty-msg tests
- [ ] E5 TransferProgress.fraction clamp/negative tests
- [ ] E6 ScreensaverUploadPlan ordering assertion fix

## Group F — File splits (mechanical, same-package internal)
- [~] F1 MED MainScreen.kt (824) -> snackbar effects / launchers / dialogs
- [~] F2 MED DevicesTab.kt (797) -> ConnectionSection / SavedPortalsCard / PortalScannerDialog
(Deferred unless correctness fixes push them over; large moves = regression risk. Reassess at end.)

## Lower-value / noted, not blocking
- ContentUriHelper -> suspend+IO (A-data #6); ConnectionHolder orphan scope; AdbKeyPairProvider sync;
  forceStopApp/setScreenOn ignore exit code; writeIconPng recycle; cached-label read runCatching;
  installMultiple success assert; connection-method string-equality -> enum; IP: hardcoded label;
  accessibility contentDescriptions; ScreensaverTab VM-as-param stability.
