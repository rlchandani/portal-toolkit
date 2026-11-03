# Companion photo collection activation — 3 November 2026

Version **1.7.8/code20** is installed on the Pixel over wireless ADB. The phone
now disables **Use for slideshow** when the displayed photo list failed to refresh
or has not completed loading. Retained thumbnails remain available for review;
a successful complete refresh enables the existing action again. Busy, empty
and partial-list guards remain.

The original discovery clock is **00:58:23–01:28:23 UTC**. This is a focused
phone correction; no Portal source, photo, APK or configuration changed.
Both rooms retain the accepted Portal **1.90/code84**.

| Evidence | Result |
| --- | --- |
| Original native app | 3 new regression failures within 8 methods; captured repeat also failed |
| Updated actual Android panel | 8/8 methods at actual100% and130% system text,16 executions |
| Recovery | Existing Refresh callback, cleared error, completed metadata and one activation callback |
| Full JVM | 651tests /81suites; zero failures, errors or skips |
| Builds | Signed debug and instrumentation assemblies passed |
| APK identity | Full installed SHA matches on owned emulator and physical Pixel |
| Pixel preservation | All3 saved companion files equal; original foreground Activity remains |
| QA cleanup | Disposable emulator removed; source AVD contents/metadata unchanged |
| Independent review | No actionable blocker; production caller passes retained error/listing state |

Native suite runner durations: **7.612s at100%**, **7.233s at130%**.
These are test durations, not application latency measurements.
Screenshots show generated metadata and a null-thumbnail placeholder in the
real photo-library component. They do not show owner images or full-app styling.

[Before](http://127.0.0.1:8891/roadmap/images/photo-activation-before.png),
[error state](http://127.0.0.1:8891/roadmap/images/photo-activation-error.png),
[130% error state](http://127.0.0.1:8891/roadmap/images/photo-activation-error-large.png),
[130% recovered state](http://127.0.0.1:8891/roadmap/images/photo-activation-recovered-large.png).

The phone's baseline1.7.0 APK and private configuration capture are retained
outside the repository for rollback. No phone app was launched or instrumented.
The pixel installation is delivery evidence, not physical UI or real-network
source-write acceptance. No automatic retry, polling, whole-app performance
claim, GitHub action or Home Assistant operation was added.

Artifact: **14,738,977bytes**; package `com.portalinstaller`.

SHA256: `9e6d1fdab7468963b9a6d4f53526452ba78aa41f3629128a9eb4ab9d6421702c`.

Signer: `4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`.

Private receipts: `/private/tmp/portal-companion-photo-activation-20261103/`.
Phone source branch: `codex/photo-activation-readiness-20261103`.
