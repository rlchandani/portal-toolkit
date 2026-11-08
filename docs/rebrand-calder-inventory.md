# Rebrand inventory: Portal Toolkit -> Calder Pocket, Immortal -> Calder

Scope: `app/src/main` (res/, Kotlin, manifest), searched for `Portal Toolkit|Toolkit|Immortal|immortal`.
"Portal" meaning the Meta hardware device is unchanged everywhere.

## Visible copy changed (29 strings)

| File | Before | After |
|---|---|---|
| `res/values/strings.xml`, `res/values-en/strings.xml` `app_name` | Portal Toolkit | Calder Pocket (launcher label; also the share-sheet label, because `ShareActivity` inherits the application label) |
| `res/values-en/strings.xml` `logcat_export_subject` | Portal Toolkit logcat | Calder Pocket logcat |
| `res/values/strings.xml` `logcat_export_subject` (ro) | Logcat Portal Toolkit | Logcat Calder Pocket |
| `ui/AppBrandHeader.kt` (2 texts) | "Portal" "Toolkit" | "Calder" "Pocket" |
| `ui/controlcenter/CompactPortalHeader.kt:112` | fallback "Immortal" | "Calder" |
| `ui/controlcenter/RegistrySettingsPanel.kt:94` | Connect to an Immortal Portal... | Connect to a Calder Portal... |
| `ui/controlcenter/ControlCenterScreen.kt:214` | valid Immortal snapshot | valid Calder snapshot |
| `ui/controlcenter/ControlCenterScreen.kt:306` | ‹ Immortal control center | ‹ Calder control center |
| `ui/controlcenter/ControlCenterScreen.kt:455` | Phone remote in Immortal | Phone remote in Calder |
| `ui/controlcenter/ControlCenterScreen.kt:552` | the Immortal catalog | the Calder catalog |
| `ui/controlcenter/ControlCenterScreen.kt:670` | In Immortal, enable Phone remote | In Calder, enable Phone remote |
| `ui/controlcenter/LayoutEditorPanel.kt:188` | Add an Immortal widget to get started. | Add a Calder widget to get started. |
| `ui/controlcenter/LayoutEditorPanel.kt:221` | Add Immortal widget | Add Calder widget |
| `ui/routines/RoutinesScreen.kt:68` | Update Immortal on $room | Update Calder on $room |
| `data/routines/RoutineText.kt:82` | Closed from Toolkit at | Closed from Calder Pocket at |
| `data/dinner/DinnerModels.kt:81` | Open Toolkit near the Portals | Open Calder Pocket near the Portals (label only; wire value `notsetup` unchanged) |
| `data/companion/CompanionClient.kt:292` | Enable Immortal's accessibility service | Enable Calder's accessibility service |
| `data/companion/CompanionRepository.kt:555,749` | Update Immortal ... | Update Calder ... |
| `data/companion/CompanionGroup.kt:307` | Update Immortal and retry | Update Calder and retry |
| `share/ShareJob.kt:107` | Toolkit stopped while sending | Calder Pocket stopped while sending |
| `share/ShareCopy.kt:73` | Toolkit sends ... | Calder Pocket sends ... |
| `share/ShareIntentParser.kt:42` | Portal Toolkit can share photos... | Calder Pocket can share photos... |
| `share/SharePicker.kt:91,149,150` | Portal Toolkit / Toolkit | Calder Pocket |
| `share/ShareFailures.kt:49` | needs an Immortal update | needs a Calder update |

Notification channel name ("Sharing to Portals") and notification titles contain no brand; unchanged.

## Internal, intentionally unchanged

- `CatalogAction.kt:15` package names `com.immortal.launcher(.debug)` (Portal launcher package id).
- `ControlCenterScreen.kt:145,156` settings domain id `"immortal"` (API field from the Portal).
- `CompanionDiscovery.kt:76,109` NSD service type `_immortal-remote._tcp.` (protocol).
- `HouseholdListIds.kt:25` `"immortal-list-id:"` and `CompanionHousehold.kt:40` `"immortal-household-fp"` hash salts (changing breaks ids/fingerprints).
- `CompanionPhotos.kt:42`, `PhotoSourcesPanel.kt:99` `/sdcard/Pictures/Immortal` (real folder path on the Portal).
- `DinnerRepository.kt:167` log tag `ToolkitDinner`.
- KDoc/comments mentioning "Toolkit" (`DinnerScreen.kt`, `RoutinesScreen.kt`, `HouseholdListScreen.kt`, `ShareCopy.kt`, `ShareIntentParser.kt`, `SenderName.kt`, `ShareSettingsSection.kt`).
- Theme names `Theme.PortalInstaller*`, applicationId/package `com.portalinstaller`.

## Ambiguous, left for a human

1. Phone-side save folders `Pictures/PortalToolkit` (`MirrorViewModel.kt:288`) and `Downloads/PortalToolkit` (`FileManagerViewModel.kt:272`), plus the matching copy `mirror_saved` ("Saved to Pictures/PortalToolkit." / "Salvat in Poze/PortalToolkit."). Visible to users in their gallery/files, but renaming moves where new files land and splits existing ones. Left unchanged; copy still matches the real path.
2. `select_photos_hint` mentions `Pictures/PortalInstaller` (en + ro). Older name, a path; unchanged.
3. Settings export default filename `immortal-<timestamp>.json` (`ControlCenterScreen.kt:410`). Visible in the save dialog; it is a file name, so unchanged. Could become `calder-<timestamp>.json` if import does not rely on the prefix.
4. The `values/` (Romanian default) strings contain no other brand mentions; Kotlin hard-coded copy is English-only (not moved into string resources in this pass to keep the diff copy-only).
