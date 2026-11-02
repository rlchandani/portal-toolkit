# Backup Preview Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for coordinator integration and verification.

**Goal:** Keep backup preview cancellable, bounded and unable to publish obsolete results.

**Architecture:** A preview operation owns its coroutine independently of ADB. The existing ZIP inspector receives cancellation checkpoints and counts all decompressed entry bytes. Existing Cancel is shown during preview; the format and restore pipeline remain.

**Tech Stack:** Kotlin coroutines, Compose, Java ZIP, JVM fixtures and isolated API34 Android UI tests.

**Spec:** Existing backup workflow in `PortalBackupManager.kt`, `BackupViewModel.kt` and `BackupCard.kt`; user goal requires verified reliability and responsive performance.

## Constraints and review focus

- Original discovery clock 20:51:32–21:21:32 UTC includes cleanup.
- Keep format1, 500 entries, 1MiB manifest and 3GiB total; no device writes or owner archive.
- Preserve cancellation, obsolete-result rejection, CRC/truncation detection and stream closure.
- Await the actual preview job in native tests; blocked-provider IO may settle after UI cancellation.
- Remove owned test threads/processes and preserve the original AVD; physical phone remains unavailable.

## Task 1: Bounded preview and ownership

**Files:** `PortalBackupManager.kt`, `BackupViewModel.kt`, `BackupCard.kt`, app version and new `PortalBackupPreviewBudgetTest`, `BackupPreviewLifecycleTest`, `BackupPreviewUiTest`.

- [x] Reproduce old declared-size behavior through unchanged inspector and missing Cancel on accepted old APK.
- [x] Add streamed whole-archive budget/cancellation and per-operation preview ownership.
- [x] Run full JVM/build gates; independent worker reviews production lifecycle and fixture cleanup.
- [x] Resolve review findings once, then run actual VM/Card/file/FIFO checks at system100%/130%.
- [x] Verify exact candidate readback, source-AVD and fixture cleanup; record scoped evidence and commit.
