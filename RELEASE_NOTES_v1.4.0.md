# Portal Toolkit v1.4.0

Phase 3 of the v1.2–v1.4 roadmap turns the toolkit into a complete manager: a
two-way file browser, a purpose-built screensaver manager that replaces the old
Photos tab, a richer view of the apps installed on the Portal, and real
byte-level progress with cancel across every long-running transfer.

## New features

### Two-way file manager
- Browse the Portal's filesystem from a new **Files** section under Tools, with
  a breadcrumb path and up-navigation.
- Pull files to the phone, push files back, move, rename, delete, and create
  folders — all from the same view.
- Multi-select with a running count and total size; deletions ask for
  confirmation showing how much will be removed.
- An advanced-mode switch relaxes the default safety scoping for power users;
  path-traversal is guarded so operations stay inside the intended directory.
- Partial pushes clean up after themselves if a transfer fails midway.

### Screensaver manager (replaces Photos)
- The old Photos tab is now a dedicated **Screensaver** manager.
- Scans the Portal for image folders, ranks them by image count, and always
  lists the legacy `Pictures/PortalInstaller` folder.
- The chosen folder is remembered per device.
- A storage meter shows folder size against free space before you upload.
- Thumbnail grid backed by a local disk cache keyed on path, mtime, and size,
  so thumbnails self-invalidate and never grow unbounded.
- Uploads dedupe by name + size, with an explicit "upload anyway" override for
  intentional duplicates, and a summary of uploaded / skipped / failed.
- New uploads are announced to the device gallery via a media scan.

### Rich portal app list
- Apps installed on the Portal now show their real icon and label, not just the
  package name.
- Icons and labels are extracted once per app version (by pulling `base.apk`)
  and cached under a `package@versionCode` key, so an update refreshes them
  automatically.
- Sort by name or size, and act on any app directly: Open, Force stop,
  Clear data (with confirmation), or Uninstall.

### Real progress + cancel
- Long-running transfers report real byte progress through counting okio
  streams.
- Install, profile apply, screensaver upload, file transfer, and backup /
  restore can all be cancelled mid-flight.
- Applying a profile now reports a per-item outcome — which packages succeeded
  and which failed, with reasons.

## Under the hood
- Counting `Source`/`Sink` wrappers surface transferred-vs-total bytes as a
  reusable `TransferProgress`.
- A shared ADB error formatter (`AdbErrors`) gives consistent, user-facing
  messages across managers.
- An `ls -la` parser turns raw shell output into typed `RemoteFile` entries
  (files, directories, symlinks, size).
- `foldProfileResults` reduces per-package results into a single `Done` state,
  and long operations hold cancellable `Job`s in their view models.

## Reliability
- New unit tests cover the `ls -la` parser (golden output), the dedupe matcher,
  the path-safety guard, thumbnail cache keying, app-metadata cache keying, and
  the per-item profile-apply state reducer.

## Compatibility
- Portal devices remain capped at Android 10 (API 29).
