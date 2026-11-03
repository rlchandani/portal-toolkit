# Portal Toolkit

Portal Toolkit is an Android app for managing Meta/Facebook Portal devices directly from a phone.

It connects to a Portal over Wi-Fi ADB or USB OTG and makes common setup and maintenance tasks much easier.

The current release is **v1.1.0**.

## Features

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

- Meta/Facebook Portal Mini

Other Portal generations may work, but have not been fully tested. Feedback and compatibility reports are welcome.

## Use case

Portal Toolkit is meant for people still repurposing Portal devices and who want a simpler phone-based tool for setup, testing, and maintenance.

It is not meant to replace projects like Immortal, but to complement them as a bootstrap, provisioning, and utility tool.

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

## Development checks

Use JDK 21 and an Android SDK with platform 35 and build tools 35.0.0 installed, then run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --rerun-tasks
```

The GitHub Actions workflow is configured for pushes, pull requests and manual runs.
It uploads any available JVM test reports, including after failures, and the debug APK after successful checks.
These checks cover host tests and debug assembly; device and USB/Wi-Fi connection testing is separate.
