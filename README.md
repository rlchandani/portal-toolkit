# Portal Toolkit

Portal Toolkit is an Android app for managing Meta/Facebook Portal devices directly from a phone.

The Immortal control center pairs over local Wi-Fi for everyday controls, photo
uploads, settings, home layouts, routines, catalog apps, and Jarvis management.
Advanced device tools retain Wi-Fi ADB and USB OTG for provisioning and maintenance.

The current development build is **1.5.2-control-center**. See
[Companion setup and capabilities](docs/companion-control-center.md) and the
[physical-device validation record](docs/companion-extended-validation-2026-10-08.md).

## Features

- Native Home, Remote, Photos, Apps, and Settings in one continuous interface
- Saved pairing for multiple Immortal Portals and local network discovery
- Touchpad, keyboard, navigation, media, and volume controls
- Photo import with retained per-image outcomes, folder activation, and slideshow preview
- Complete server-provided settings registry, search, and an Advanced toggle
- Home widget ordering and resizing, with stale-save protection
- Saved scenes and local weekday schedules
- Credential-free settings/layout snapshot files
- Catalog installs and updates with retained operation results
- Jarvis model, microphone status, and opt-in local wake management
- Install APKs from apps already on the phone
- Install custom APK files directly to the Portal
- Upload photos
- Browse installed Portal apps with package, version, and size information
- Uninstall apps already installed on the Portal
- Enable Wireless ADB from USB and verify the connection
- Detect Portal devices on the local network
- View basic device info such as storage and RAM
- Back up and restore selected Portal settings and third-party APKs
- Preview backups and validate backup archives before restoring
- Create app profiles for installing groups of applications
- Rename saved Portal devices
- Block or re-enable system OTA updates
- Improved USB ADB connection and device validation
- Mobile-friendly UI designed for quick Portal management

## Tested on

- Meta/Facebook Portal Mini in the original toolkit
- Portal+ on Android 10 with the Immortal companion API, controlled by a physical
  Google Pixel 9 Pro Fold over Wi-Fi

The validation record distinguishes real device checks, synthetic regression
tests, and integrations that still require owner setup. Other Portal generations
may work; compatibility reports are welcome.

## Use case

Portal Toolkit is meant for people still repurposing Portal devices and who want a simpler phone-based tool for setup, testing, and maintenance.

It complements Immortal as both a daily companion and a provisioning utility.

## Download

Download the latest APK from [GitHub Releases](https://github.com/jurax-sys/portal-toolkit/releases).

## Version 1.1.0

This release adds backup and restore, app profiles, OTA update controls, Wireless ADB verification, improved device detection, and a faster Portal app list.

## Requirements

- An Android phone with USB OTG support for USB connections
- Wireless ADB enabled on the Portal for Wi-Fi connections
- The phone and Portal on the same local network when using Wi-Fi ADB

## Disclaimer

Use it at your own risk. This is an independent community tool and is not affiliated with Meta.
