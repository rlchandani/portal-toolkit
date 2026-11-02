# Display Image Decoding Plan

**Goal:** Reduce native companion bitmap allocation to the existing display use.

**Scope:** Photo grid (512px maximum side), photo preview (1024px), app icons
(256px). Keep original file bytes, session-owned HTTP/cache rules, fit/crop,
existing error/retry and UI navigation. Portal software/configuration is unchanged.

**Approach:** Read bitmap bounds without allocation, compute a Long-safe
power-of-two sample against ceiling-rounded dimensions, decode ARGB8888 and
validate the actual result. Preserve small images and alpha. Use the existing IO
paths; do not add an image library or a second network/cache implementation.

**Evidence:** Official Android large-bitmap and Compose image optimization
documentation; the existing raw photo/icon decode sites. Generated 4096×3072 JPEG
fits the current transport byte limit but expands to 48MiB with the old decoder.
Actual allocation remains to be measured; no owner-image/OOM report is invented.

**Original clock:** 21:14:39–21:44:39 UTC, including discovery and cleanup.

- [x] Native old-decoder allocation/budget failure reproduced.
- [x] Sampling policy and tests; native decoder and existing call sites integrated.
- [x] Full JVM/app/test gates and independent final source review.
- [x] Native decoder allocation and photo preview/retry/cancel acceptance.
- [x] Exact installed emulator APK and owned/source-AVD cleanup verified.
- [x] Scoped receipt/backlog and local commits.
