# Companion display-image decoding — 2 November 2026

Candidate **1.7.7-music-preview / code 19** reads image bounds before allocating
photo-grid, preview and app-icon bitmaps. Maximum sides are512,1024 and256px.
Long-safe ceiling rounding selects Android-compatible power-of-two samples;
actual output is checked before publication. Small images and alpha are retained.
Existing IO loaders, original bytes, HTTP/session cache, crop/fit and Retry remain.
The preview's owning composition now observes its state before the dialog boundary.

## Measured native scope

A generated4096×3072 JPEG of **273,259bytes** fits the existing transport limit.
The source-equivalent old raw decoder allocated **50,331,648bytes /48MiB** and
failed the native display-budget assertion. This was a neutral extraction of the
existing decoder body, not an old-APK UI or actual owner-photo/OOM reproduction.

| Display use | Actual decoded size | Bitmap allocation | Reduction vs raw fixture |
|---|---|---|---|
| Grid |512×384|786,432bytes /0.75MiB|64×|
| Preview |1024×768|3,145,728bytes /3MiB|16×|
| Icon |256×192|196,608bytes /0.1875MiB|256×|

The current Portal already generates512px thumbnails. This improves phone
robustness against oversized/older or incorrect responses; ordinary thumbnails
are not claimed to have a memory reduction. The generated512×384 control retained
identical pixels/allocation. Five native pairs measured medians
**0.196334ms raw /0.210958ms bounded**.
These are decoder samples, not frame-rate, total heap, GPU or physical-phone proof.

## Verification and limits

All **651 JVM /81suites** passed, including19 sampling methods and a2,890-case
boundary matrix. Debug and AndroidTest builds and signer continuity passed.
The final candidate in a fresh owned API 34 clone passed **13unique native methods /
14 executions**:5decoder cases,8preview workflows at actual100% text and the large
preview at actual130%. Existing retry, invalid input, cancellation and device
switch cases are included. Generated component captures were visually reviewed;
complete dialog/page, physical phone, actual icon-list and live server acceptance
are not inferred from them.

Earlier spinner timeouts were retained. Observation showed a normally completed
IO job while the dialog remained Loading. A separate rapid-font negative caught
rendered100% while130% was requested. Fixtures now wait on actual activity
resources and IO completion, retain image/error assertions and capture failures.
The parent state read is explicit and independently reviewed. Final passing
checks do not prove the earlier failures' root cause or universal flake elimination.

Final APK: **14,738,973bytes**, SHA256 `1fae356ced42470f0775eb382bf64fcacde4c7f88ebe0072f64f818a1e08aa47`.
Complete installed emulator readback matches it; same signer as accepted1.7.6.
Both owned clones terminated and were removed, donor AVD unchanged each time,
unrelated emulator/Family transports retained, independent agent closed.
Original **21:14:39–21:44:39UTC** clock includes both clones and cleanup.
No owner photo, Portal configuration/APK, GitHub or Home Assistant operation.

Guidance: [Android large bitmap loading](https://developer.android.com/topic/performance/graphics/load-bitmap)
and [Compose image optimization](https://developer.android.com/develop/ui/compose/graphics/images/optimization).
