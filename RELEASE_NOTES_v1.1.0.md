# Portal Toolkit v1.1.0

Feature release following v1.0.0.

## Added

- Backup and restore of third-party APKs and selected Portal settings
- Backup preview and defensive archive validation
- App profiles for installing groups of applications
- Saved Portal renaming and improved device identity detection
- More reliable USB ADB connection and permission handling
- Wireless ADB activation verification through a real TCP connection
- System OTA update blocking with reversible enable/disable controls
- Improved Portal app list with version and remote APK size information
- Romanian and English interface strings

## Improved

- Portal validation during Wi-Fi connections
- Restore progress and early-stop reporting
- USB device detection no longer treats arbitrary single USB devices as Portal
- Backup actions require an actual ADB connection

## Verification

- `:app:assembleDebug` passes with Android Studio's bundled JDK.
