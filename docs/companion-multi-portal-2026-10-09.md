# Android multi-Portal controls

Current Android development build: **1.6.0-control-center** (`versionCode 10`).

Settings and Photos have a pinned **Manage Portals** summary. Open it to check saved
Portals, **Select all**, or clear the selection. The first group defaults to every saved
pairing; explicit choices, including an empty selection, survive app restarts in the
private `noBackupFilesDir/companion/companion-targets.json` store. It contains ids only,
is written atomically with owner-only access, and is separate from pairing credentials.
A corrupt selection fails closed and lets the user choose targets again.

The top device menu still chooses the **active Portal** for displayed values, photo
browsing, pointer input, and other detail reads. Changing either selection cannot redirect
work already queued. Group progress remains visible after active-device changes.

## Settings

Changing a scalar registry row sends just its domain and changed key/value to each
captured target. Each target's schema is fetched and validated independently, and each
requested key must appear in the server's `applied` response, including HTTP 200 responses.
Unsupported, offline, rejected, and expired pairings appear as individual failures.
The coordinator checks device identity when advertised, uses at most two concurrent
settings requests, and orders rapid edits and retries per target.

Rows display values from the explicitly named active Portal. A short pinned warning
explains that other rooms may differ. This is reference
editing, not a claim that all rooms have identical settings. Group editing does not
copy snapshots, names, sources, or untouched settings.

Credentials, connection URLs, photo sources, connection details, fleet names (including `fleet.name`),
and per-device names remain labeled **Active Portal only**. Snapshot restore,
slideshow source activation, photo deletion, browsing, and remote pointer actions also
remain active-device operations.

Each operation shows per-room confirmation or failure. **Retry failed only** keeps the
original targets and patch; a partially applied patch retries only unapplied keys.
An old retry cannot overwrite a newer edit to the same room/key, even after its progress
card is dismissed. A fresh edit can explicitly apply a desired value again.

## Photos

**Add photos to selected Portals** captures targets when the picker opens. Every selected
image goes to each target's own captured, exact saved `photoFolder`; browsing or changing
selection cannot redirect a retry. No slideshow source is activated by import.

Imports prepare a fresh streaming source for every image/target request, with at most
two transfers concurrently. Unknown sizes spool one image to private disk with the
existing 20 MiB bound and cleanup; collections are never buffered in memory. Existing
photo-library discovery, request revisions, image bounds, and caches remain in use.

The progress ledger tracks image/target pairs. Each room always shows its counts;
image results use bounded pages with failures first. Byte progress is coalesced per image
and room around 100 ms, with exact terminal counts; status changes publish immediately.
This bounds UI updates without making a transfer-throughput claim. Retrying only failures does not resend
confirmed images to successful rooms. **Cancel queued work** prevents requests that
have not been posted; in-flight requests can finish and posted photos are not undone.
If a connection drops after posting, completion is unconfirmed: refresh that room before
retrying. The server has no upload idempotency token, so an ambiguous response cannot
prove whether the file was stored. Retry plans and URI providers live only in memory;
app-process loss requires choosing photos again. Target selection persists independently.

## Verification

`CompanionGroupTest` exercises the actual repository and coordinator through an in-memory
HTTP transport, covering capture, persistence, active reads, partial settings responses,
ordering, concurrency, upload destinations/streams, failure-only retries, cancellation,
credential policy, and removed pairings. `ManagePortalsUiTest` supplies device-free Compose
fixtures for checkboxes, Select all, per-room partial outcomes, and cancellation wording.
Physical paired-Portal and UI runtime checks are owned by the parent integration task.
