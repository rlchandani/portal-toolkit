# Google Photos → Portal screensaver (Picker API) — Design

Date: 2026-09-17
Repo: portal-toolkit (`com.portalinstaller`, versionName 1.4.0)
Branch base: `roadmap-v1.2-1.4`

## Goal

Let the user copy photos from their **Google Photos** into the Portal's screensaver
folder. One-time copy (not continuous sync). Personal / household use only.

## Reality constraint (2026)

Google removed the broad Library API scopes on **2025-03-31**. Third-party apps can
**no longer list a user's albums or read their library** programmatically. The only
sanctioned path to a user's real Google Photos is the **Picker API**:

1. App gets an OAuth access token for scope
   `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`.
2. App creates a picker **session** → gets a `pickerUri`.
3. App opens `pickerUri` in a Custom Tab. User picks photos in **Google's** hosted UI
   (they can browse into an album and select its contents; up to 2000 items).
4. App polls the session until `mediaItemsSet == true`.
5. App lists the picked `mediaItems`, downloads each `baseUrl + "=d"` (with
   `Authorization: Bearer <token>`), and pushes to the Portal over ADB.
6. App deletes the session.

So "list my albums, tap one" is impossible. The delivered UX is: **tap "Add from
Google Photos" → Google picker opens → select album's photos → they sync.**

## What gets removed (the broken MediaStore attempt)

- `data/screensaver/GooglePhotosAlbum.kt` (GooglePhotosAlbumReader + models) — DELETE
- `test/.../GooglePhotosAlbumTest.kt` — DELETE
- `ScreensaverViewModel`: remove `loadAlbums()` + `syncAlbum()` + the album fields on
  `ScreensaverState` (`albums`, `albumsLoading`, `albumsError`) and the imports.
- `ScreensaverTab`: remove `GooglePhotosSection` composable + its call site + import.
- `AndroidManifest.xml`: remove `READ_MEDIA_IMAGES` + `READ_EXTERNAL_STORAGE`
  (the picker needs no media permission). Keep `INTERNET`.

## New components

| File | Purpose |
|------|---------|
| `data/photos/PickerModels.kt` | Data classes + `org.json` parsing for the session and picked-item responses. `downloadUrl(baseUrl) = "$baseUrl=d"`. Pure/testable. |
| `data/photos/PhotosPickerClient.kt` | The REST calls against `https://photospicker.googleapis.com`: `createSession`, `getSession` (poll), `listMediaItems` (paged), `deleteSession`, plus `download(baseUrl)`. HTTP engine injected as a `(Request) -> Response` seam so JVM tests can feed canned JSON. Uses OkHttp in production. |
| `data/photos/PhotosAuth.kt` | Wraps `Identity.getAuthorizationClient` / `AuthorizationRequest` for the picker scope. Returns either a token or a `PendingIntent` resolution the UI must launch. |
| `viewmodel/ScreensaverViewModel` (additions) | `startGooglePhotosImport()`, `onAuthResolved(token)`, `onPickerReturned()`; emits one-shot `PickerEvent`s (`NeedsAuthResolution(pendingIntent)`, `OpenPicker(uri)`). Reuses the existing dedupe + `fileManager.push` + `UploadSummaryState` + `historyStore`. |
| `ui/.../ScreensaverTab` | New **"Add from Google Photos"** button beside "Add photos". |
| `ui/MainScreen` | `rememberLauncherForActivityResult(StartIntentSenderForResult)` for the auth resolution; a `CustomTabsIntent` to open the picker; a lifecycle `ON_RESUME` observer to call `onPickerReturned()`. |

New Gradle deps: `com.google.android.gms:play-services-auth`, `androidx.browser:browser`,
`com.squareup.okhttp3:okhttp`. `org.json` is built into Android; add
`implementation("org.json:json:…")` is NOT needed for main (only tests already have it).

## Config the user must create (one-time, Google side)

- Google Cloud project → enable **Photos Picker API**.
- OAuth consent screen: **External**, publishing status **Testing**, add own Google
  account as **test user** (no verification/CASA needed in testing mode).
- OAuth **Android** client ID: package `com.portalinstaller` + debug keystore SHA-1.
- The client ID is not a secret (Android IDs are public); stored as a string resource
  placeholder `google_oauth_server_client_id` (web client id for token requests). App
  builds without it but sign-in fails until filled. A setup guide doc ships alongside.

## Data flow

`tap button` → VM `startGooglePhotosImport()` → PhotosAuth.authorize()
 → (if resolution) emit `NeedsAuthResolution` → UI launches PendingIntent → `onAuthResolved(token)`
 → VM `createSession(token)` → emit `OpenPicker(pickerUri)` → UI opens Custom Tab
 → user picks in Google UI → returns to app → UI `ON_RESUME` → VM `onPickerReturned()`
 → poll `getSession` until `mediaItemsSet` → `listMediaItems` (paged)
 → for each: `download(baseUrl=d, Bearer)` → temp file → dedupe vs Portal folder
   → `fileManager.push` → `UploadSummaryState.Uploading` progress
 → `UploadSummaryState.Done` + `historyStore.add` → `deleteSession` → `refreshContents`.

## Error handling

- Auth cancelled/failed → `UploadSummaryState.Failed` + snackbar; no session created.
- Not connected to Portal → guard like existing upload (`_notConnected`).
- No internet on phone → download/create fails → `Failed` with clear message.
- User picked nothing / session timed out (`mediaItemsSet` never true within timeout)
  → `Done(0,0,0)` "No photos selected".
- Per-item download failure → increment `failed`, continue (matches existing pipeline).
- Token/baseUrl ~60 min expiry → keep imports prompt; on 401 mark remaining failed with
  a "sign in again" message.
- Always `deleteSession` in a `finally`.

## Testing

- `PickerModelsTest` (JVM, uses existing `org.json:json` test dep): parse a sample
  session response, parse a paged mediaItems response, `downloadUrl` appends `=d`,
  dedupe key contract.
- `PhotosPickerClientTest` (JVM): inject a fake HTTP engine returning canned JSON;
  assert createSession/getSession/listMediaItems map fields correctly and that the
  Bearer header + `=d` are set on requests.
- OAuth / Custom Tab / real network are manual/instrumented (can't unit test).

## Out of scope

- Continuous/background sync (user chose one-time copy).
- Album enumeration inside the app (impossible per Google policy).
- Distribution to non-household users (would require Google verification + CASA).
