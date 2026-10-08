# Google Photos import — one-time Google setup

The "Google Photos" button in the Screensaver tab uses Google's **Photos Picker API**.
Before it works, you (the Google account owner) must do a one-time setup in Google Cloud.
For personal / household use you keep the project in **Testing** mode, so **no Google
verification or security review is needed**.

Nothing here is a secret — the app is matched to your project by its package name
(`com.portalinstaller`) plus the app-signing certificate fingerprint (SHA-1). There is
**no client ID or key to paste into the code.**

---

## 1. Create a Google Cloud project

1. Go to https://console.cloud.google.com/
2. Top bar → project dropdown → **New Project**. Name it e.g. `portal-toolkit`. Create.
3. Make sure that new project is selected in the top bar.

## 2. Enable the Photos Picker API

1. https://console.cloud.google.com/apis/library
2. Search **"Photos Picker API"** → open it → **Enable**.
   (It may also be listed under "Google Photos Picker API".)

## 3. Configure the OAuth consent screen

1. https://console.cloud.google.com/auth/overview (or APIs & Services → OAuth consent screen)
2. User type: **External** → Create.
3. Fill required fields: App name (`Portal Toolkit`), your email for support + developer
   contact. You can skip logo/homepage for testing. Save and continue.
4. **Scopes**: you can leave this empty here — the app requests the picker scope at
   runtime. Save and continue.
5. **Test users**: click **Add users** and add **your own Google account** (the one whose
   photos you want). Add any household accounts too (up to 100). Save.
6. Leave **Publishing status = Testing**. Do **not** click "Publish app" (publishing would
   trigger Google's verification/CASA review — unnecessary for personal use).

## 4. Get your app's SHA-1 fingerprint

The debug build is signed with the Android debug keystore. From the `portal-toolkit`
project root run:

```bash
./gradlew signingReport
```

Look for the `debug` variant and copy its **SHA1:** value (format `AA:BB:CC:...`).

For the 1.5.2-control-center APK built on 2026-10-08, the verified signing
certificate SHA-1 is:

```text
E6:53:20:F6:66:FE:95:BA:B7:47:62:9A:6B:F6:1F:F2:0A:7F:71:98
```

This is a public certificate fingerprint, not a credential. A build made with
another signing key needs its own matching Android OAuth client.

(Equivalent without Gradle:
`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`)

## 5. Create the Android OAuth client ID

1. https://console.cloud.google.com/apis/credentials
2. **Create credentials** → **OAuth client ID**.
3. Application type: **Android**.
4. Name: `portal-toolkit debug`.
5. Package name: `com.portalinstaller`
6. SHA-1 certificate fingerprint: paste the value from step 4.
7. Create.

> When you later build a **release** APK signed with a different keystore, add a second
> Android OAuth client with that keystore's SHA-1 (same package name).

## 6. Use it

1. Open Portal Toolkit → **Settings** → **Device tools**, establish its Wi-Fi/ADB
   connection, then open the **Screensaver** tab.
2. Tap **Google Photos**.
3. First time: a Google sign-in / "grant access to your photos" screen appears → allow.
4. Google's photo picker opens. Browse into the album you want, select the photos
   (up to 2000), and confirm.
5. Return to the app — it downloads the selected photos and pushes them into the Portal's
   screensaver folder. Photos whose filename already exists in the folder are skipped.

## Notes / limits (Google policy, not the app)

- The app **cannot list your albums** — Google removed that capability for third-party
  apps in March 2025. You browse and select inside Google's own picker instead.
- Access tokens last ~1 hour; each import gets a fresh one, so there's nothing to keep
  signed in. In Testing mode a background refresh token would expire after 7 days, but
  this one-time-import flow never relies on one.
- Downloaded photos keep their EXIF **except GPS location**, which Google strips.
