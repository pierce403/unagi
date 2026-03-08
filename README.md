# unagi

Android app for quick Bluetooth awareness: scan nearby devices, review the list, tap into local history, and relaunch without losing what you saw.

The landing page is intended for `unagi.ninja`, and the publishable debug APK is staged at `downloads/unagi-debug.apk`.

## Why it exists

Quick situational awareness for nearby "things" (headphones, trackers, consoles, dev boards, etc.).

Foreground alerts for "let me know when X is nearby."

## MVP scope

- BLE scan and device list
- Device detail screen with history
- Local-only storage (no cloud)

## Non-goals (MVP)

- No connecting or pairing
- No "tracking" UI (no directional finder)
- No background scanning

## Tooling constraints

- VS Code workflow only (no Android Studio)
- Build and run via Gradle and Android command-line tools
- Android SDK Command-line Tools are supported for non-Android-Studio setups (see Android Developers docs)

## Prereqs

- JDK 17 (Android Gradle Plugin requires Java 17 to run)
- Android SDK Command-line Tools and Platform Tools (adb)

## How to build/run (high-level)

- Configure ANDROID_HOME (and/or ANDROID_SDK_ROOT) and add platform-tools to PATH
- Install required SDK packages via sdkmanager
- Use Gradle Wrapper and a pinned AGP/Gradle version pairing (avoid dynamic versions like 8.+)

## Toolchain setup (CLI-only)

- Run `scripts/setup-android-sdk` or follow `docs/SETUP_ANDROID.md`
- AGP is pinned to 8.13.2 and Gradle to 8.13 (see compatibility table in Android Developers docs)

## Build and install (CLI)

- `./gradlew assembleDebug`
- `./gradlew installDebug`
- `adb devices` to confirm device connection
- `scripts/stage-apk` to copy the built debug APK to `downloads/unagi-debug.apk` for the website
- `scripts/update-vendor-prefixes` refreshes the bundled IEEE MA-L / MA-M / MA-S vendor-prefix asset at `app/src/main/assets/vendor_prefixes.txt.gz`
- `scripts/update-bluetooth-assigned-numbers` refreshes the bundled Bluetooth SIG company/service registries at `app/src/main/assets/bluetooth_company_identifiers.txt.gz` and `app/src/main/assets/bluetooth_service_uuids.txt.gz`
- The main screen shows the installed app version prominently in the toolbar and a dedicated build banner so testers can confirm which APK is running
- Because the app targets SDK 35, system-bar insets must be handled explicitly; the main, diagnostics, and detail toolbars now pad below the status bar on Android 15+
- The Android package identity is now `ninja.unagi`; older `com.thingalert` installs will not upgrade in place and should be uninstalled manually before testing the new build
- The main controls banner can be collapsed by tapping its header, and the overflow menu now includes a persisted `Compact device cards` toggle for denser scanning sessions

## Permissions notes (MVP)

- Bluetooth scanning permission model differs by Android version; unagi now requests WiGLE-style scan permissions: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, and `ACCESS_COARSE_LOCATION`
- Using `neverForLocation` can filter some BLE beacons; unagi currently does not set this flag to avoid filtering
- On Android 11 and below, BLE results can still depend on location services being enabled in addition to permission grant
- GrapheneOS note: the APK manifest does not request sensor-class permissions; Nearby devices is the permission to grant on Android 12+

## Troubleshooting scans

- If a scan fails to start, open Diagnostics to inspect BLE/classic startup results, the last BLE error code, and permission/Bluetooth snapshots
- Use `Copy scan debug report` in Diagnostics to capture app version, device/build info, persisted device inventory, and recent scan events for bug reports
- `unagi` now requests the same scan-relevant Bluetooth/location permissions that WiGLE does; if scans still return zero callbacks, the remaining gap is more likely ROM/profile behavior than a missing manifest permission
- `unagi` now prefers BLE advertised local names over generic Bluetooth device names, so BLE peripherals should surface the short broadcast name they actually expose
- Vendor labels come from a bundled offline IEEE prefix database; locally administered/randomized BLE addresses are called out explicitly instead of being mis-labeled with a guessed vendor
- Unnamed BLE devices now surface manufacturer-company IDs and advertised service UUID labels from bundled Bluetooth SIG assigned-number registries, with the raw JSON still available in Device Details for deeper inspection
- Device identity and classification are now separated: list/detail views show address type, vendor-source confidence, likely device category, and the evidence used for that classification without treating those hints as stable identity
- Classic discoveries now keep public-address vendor confidence, while BLE discoveries downgrade OUI trust when the address is randomized or uncertain
- Device Details now includes an opt-in `Query device info (BLE)` action that stops the app scan first, opens a short-lived GATT connection, reads safe Device Information Service fields, and stores the result separately from passive observations
- Active BLE query results are local-only and feed both the detail screen and the copyable Diagnostics report with DIS availability, service discovery, read counts, GATT status, and any returned manufacturer/model/PnP fields
- Passive vendor decoders now attach human-readable hints for Apple, Google/Fast Pair, Microsoft, Samsung, Nordic, and Tile-style payloads so randomized or unnamed devices expose better context without being treated as stable identities
- The main search field now matches normalized MAC and OUI fragments, so `001122` and `00:11:22` both work as live filters
- The Alerts screen lets you match devices by OUI, full MAC, or Bluetooth name, choose an emoji and sound preset, and receive a detail notification when a rule fires
- On Android 13+, grant notification permission from the Alerts screen if you want system notifications in addition to the audible alert
- If a scan runs but returns zero devices, try Compatibility mode from Diagnostics and compare the callback counters before retrying
- Compatibility mode uses balanced BLE scanning, skips classic discovery, and keeps the session alive longer for conservative testing
- “Bluetooth LE scanner unavailable” usually means Bluetooth is off, restricted, or unavailable in the current profile
- On Android 11 and below, confirm both Location permission and location services before treating a zero-result scan as a platform bug

## Privacy stance

- Store sightings locally
- No uploading nearby device identifiers
- Clear "scanning is active" indicator in-app
