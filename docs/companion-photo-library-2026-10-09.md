# Existing Portal photo-library recovery

## Problem and behavior

The original photo manager was retained under Settings → Device tools → Screensaver.
The new Photos page listed only `/sdcard/Pictures/Immortal`, even though existing photos
were in shared storage and `/sdcard/Pictures/PortalInstaller`.

Toolkit 1.5.3 now defaults migrated pairings to All photos. A recursive metadata request
collects public photo files and provides discovered folder choices. Folder browsing and
All photos can be selected without typing paths; manual path entry remains available.
Browsing never activates a slideshow source. Imports retain their designated destination.

Selected views persist independently for each room. Older recursive responses cannot
overwrite a newer choice. Exact discovered folder names, including trailing whitespace,
survive requests and persistence. A capability upgrade triggers a refresh; the UI labels
the actual loaded scope rather than relabeling an old shallow listing as All photos.

Partial listings show a warning and cannot be activated as a complete collection.
Failures retain visible photos and a retry message. Thumbnail producers include file size
as well as path and modification time so refreshes do not retain a replaced image.

## Runtime and performance constraints

The Portal API performs no image decoding during discovery and holds no upload/thumbnail
lock. Its cooperative limits are 1,000 photos, 512 directories, 20,000 entries, depth 8,
and 500 ms. Hidden paths, symlinks and Android app storage are excluded. Reaching a limit
is reported explicitly as `truncated`; this is not an unlimited or private-app photo index.

Toolkit requests discovery on the visible Photos page, not during idle status polling.
It displays 24 thumbnails per page and reuses existing bounded image loading and caching.
Old Portal builds retain shallow folder browsing until they advertise `photoLibrary`.

## Verification on 2026-10-09

- The original default-listing regression failed before implementation.
- Deferred-response and distinct-folder-name regressions failed before review fixes.
- Portal: 881 JVM tests passed; debug build passed and was deployed to both rooms with
  the installed APK hash verified.
- Toolkit: all 463 JVM tests and debug/test APK builds passed.
- All 17 generated-fixture emulator workflows passed, covering collections, paging, confirmed deletion,
  preview failures/retries and thumbnail replacement.
- Both physical Portals returned 12 photos in two folders, without truncation.
- Desktop paired requests, 20 per room: Family median 62.08 ms, p95 108.88 ms;
  Living median 61.25 ms, p95 81.99 ms.
- The physical Pixel metadata test passed: 20 requests per room, Family median 150 ms,
  p95 231 ms; Living median 106 ms, p95 118 ms.
- Full slideshow configuration snapshots remained unchanged after deployment and reads.
- The 303.57-second physical soak completed 100 requests per room with zero failures,
  consistently returning 12 photos. Family median/p95 was 48.44/67.31 ms;
  Living median/p95 was 55.81/74.11 ms. Maximums were 80.92 and 176.09 ms.

These timings measure metadata HTTP requests, not thumbnail rendering, slideshow FPS,
or overall device performance. The visible real-Pixel workflow requires unlocking the
phone and remains pending. The raw finite soak summary is retained privately.
The broader performance and UX audit remains active.
